package cn.wowspeeder;

import cn.wowspeeder.sw.AsyncDnsAddressResolverGroup;
import cn.wowspeeder.sw.BackPressure;
import cn.wowspeeder.sw.NettyTransport;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 第二批优化（中等成本）的回归测试：异步 DNS、写队列背压、传输层选择。
 */
public class Batch2Test {

    private final MultiThreadIoEventLoopGroup group =
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

    @After
    public void tearDown() {
        group.shutdownGracefully();
        AsyncDnsAddressResolverGroup.INSTANCE.clearCache();
        NettyTransport.init(true, false);
    }

    // ------------------------------------------------------------------
    // 异步 DNS
    // ------------------------------------------------------------------

    /**
     * 改动前 {@code new InetSocketAddress(host, port)} 会在构造器里就同步查 DNS，
     * 而这两处构造都发生在 event loop 线程上（服务端解析完 token、本地 SOCKS 通道 initChannel）。
     * 换成 {@code createUnresolved} 以后地址保持未解析状态，交给 AddressResolver 处理。
     */
    @Test
    public void plainInetSocketAddressResolvesEagerlyButCreateUnresolvedDoesNot() {
        assertFalse("new InetSocketAddress(...) 构造时就查了 DNS —— 这正是要避免的",
                new InetSocketAddress("localhost", 80).isUnresolved());
        assertTrue("createUnresolved 不做任何 DNS",
                InetSocketAddress.createUnresolved("localhost", 80).isUnresolved());
    }

    /**
     * 未解析地址必须被判为「未解析」，否则 Bootstrap.doResolveAndConnect0 会跳过 resolver、
     * 直接拿一个没有 IP 的地址去 connect。这条断言保证配置里的 resolver 真的会被用到。
     */
    @Test
    public void unresolvedAddressGoesThroughResolver() throws Exception {
        EventLoop loop = group.next();
        AddressResolver<InetSocketAddress> resolver = AsyncDnsAddressResolverGroup.INSTANCE.getResolver(loop);

        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("localhost", 8080);
        assertFalse("未解析地址必须走 resolver 分支", resolver.isResolved(unresolved));

        InetSocketAddress resolved = resolver.resolve(unresolved).sync().getNow();
        assertFalse("解析后不应仍是 unresolved", resolved.isUnresolved());
        assertEquals("端口必须原样保留", 8080, resolved.getPort());
        assertEquals(InetAddress.getByName("localhost"), resolved.getAddress());
    }

    /**
     * 命中缓存时不应再产生一次解析，并且复用同一个 InetAddress 实例。
     */
    @Test
    public void resolveResultIsCachedAndReused() throws Exception {
        AsyncDnsAddressResolverGroup resolverGroup = AsyncDnsAddressResolverGroup.INSTANCE;
        resolverGroup.clearCache();
        AddressResolver<InetSocketAddress> resolver = resolverGroup.getResolver(group.next());

        InetSocketAddress first = resolver.resolve(
                InetSocketAddress.createUnresolved("localhost", 80)).sync().getNow();
        assertEquals("一次未命中解析应当只留下一条缓存", 1, resolverGroup.cacheSize());

        InetSocketAddress second = resolver.resolve(
                InetSocketAddress.createUnresolved("localhost", 90)).sync().getNow();
        assertEquals("命中缓存不应再新增条目", 1, resolverGroup.cacheSize());
        assertSame("缓存命中应当复用同一个 InetAddress 实例", first.getAddress(), second.getAddress());
        assertEquals("端口仍然按本次请求拼装", 90, second.getPort());
    }

    /**
     * IP 字面量属于快路径：既不查 DNS，也不该污染缓存（否则目标填 IP 时缓存会无意义地膨胀）。
     */
    @Test
    public void ipLiteralBypassesResolverAndCache() throws Exception {
        AsyncDnsAddressResolverGroup resolverGroup = AsyncDnsAddressResolverGroup.INSTANCE;
        resolverGroup.clearCache();
        AddressResolver<InetSocketAddress> resolver = resolverGroup.getResolver(group.next());

        InetSocketAddress resolved = resolver.resolve(
                InetSocketAddress.createUnresolved("127.0.0.1", 80)).sync().getNow();

        assertFalse(resolved.isUnresolved());
        assertEquals(80, resolved.getPort());
        assertEquals("IP 字面量不应进入缓存", 0, resolverGroup.cacheSize());
    }

    /**
     * 解析失败要被负缓存住，避免目标长期不可达时每条连接都去打一次 DNS。
     * <p>若所在网络的 DNS 把不存在的域名也解析出地址（部分运营商劫持行为），本用例自动跳过。
     */
    @Test
    public void unknownHostIsNegativeCached() throws Exception {
        AsyncDnsAddressResolverGroup resolverGroup = AsyncDnsAddressResolverGroup.INSTANCE;
        resolverGroup.clearCache();
        AddressResolver<InetSocketAddress> resolver = resolverGroup.getResolver(group.next());

        InetSocketAddress bad = InetSocketAddress.createUnresolved(
                "batch2-no-such-host-" + System.nanoTime() + ".invalid", 80);

        boolean failed;
        try {
            resolver.resolve(bad).sync();
            failed = false;
        } catch (Exception expected) {
            failed = true;
        }
        Assume.assumeTrue("当前 DNS 把不存在的域名也解析了，跳过负缓存断言", failed);

        assertEquals("解析失败也要留下一条负缓存", 1, resolverGroup.cacheSize());

        boolean failedAgain;
        try {
            resolver.resolve(bad).sync();
            failedAgain = false;
        } catch (Exception expected) {
            failedAgain = true;
        }
        assertTrue("第二次仍然必须失败", failedAgain);
        assertEquals("第二次应当直接吃缓存，不再新增条目", 1, resolverGroup.cacheSize());
    }

    // ------------------------------------------------------------------
    // 写队列背压
    // ------------------------------------------------------------------

    /**
     * 复刻真实 handler 里的联动：下游不可写 -> 暂停读上游；下游恢复可写 -> 重新读上游。
     * <p>这里用 1KB/2KB 的水位线和一次 4KB 的未 flush 写入把下游顶成不可写，
     * 走的完全是 Netty 自己的 ChannelOutboundBuffer 水位判定，不是手工触发事件。
     */
    @Test
    public void backPressureSuspendsUpstreamReadsWhileDownstreamIsUnwritable() {
        final EmbeddedChannel upstream = new EmbeddedChannel();
        EmbeddedChannel downstream = new EmbeddedChannel();

        downstream.config().setWriteBufferWaterMark(
                new io.netty.channel.WriteBufferWaterMark(1024, 2048));
        downstream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
                BackPressure.link(upstream, ctx.channel());
                super.channelWritabilityChanged(ctx);
            }
        });

        assertTrue("初始应当是可读的", upstream.config().isAutoRead());

        // 只 write 不 flush：数据留在写缓冲里，越过高水位 -> 下游变成不可写
        Channel down = downstream;
        down.pipeline().write(Unpooled.wrappedBuffer(new byte[4096]));

        assertFalse("4KB 超过 2KB 高水位，下游必须变成不可写", down.isWritable());
        assertFalse("下游不可写时上流读取必须被掐断", upstream.config().isAutoRead());

        // flush 排空写缓冲 -> 回到低水位以下 -> 下游恢复可写
        down.flush();

        assertTrue("排空后下游应当恢复可写", down.isWritable());
        assertTrue("下游恢复可写后上流读取必须打开", upstream.config().isAutoRead());

        downstream.finishAndReleaseAll();
        upstream.finishAndReleaseAll();
    }

    // ------------------------------------------------------------------
    // 传输层选择
    // ------------------------------------------------------------------

    @Test
    public void transportFallsBackToNioAndNeverBreaksStartup() {
        // 允许两种结果，但必须自洽：Native 不可用时一定要回退 NIO
        NettyTransport.init(true, false);
        assertEquals("epoll 是否可用由 native 库决定", io.netty.channel.epoll.Epoll.isAvailable(),
                NettyTransport.isEpoll());
        assertEquals("SO_REUSEPORT 只在 epoll 下生效", NettyTransport.isEpoll(),
                NettyTransport.isSoReusePortEnabled());

        if (NettyTransport.isEpoll()) {
            assertEquals(io.netty.channel.epoll.EpollServerSocketChannel.class,
                    NettyTransport.serverChannelClass());
        } else {
            assertEquals(NioServerSocketChannel.class, NettyTransport.serverChannelClass());
            assertEquals(io.netty.channel.socket.nio.NioSocketChannel.class,
                    NettyTransport.clientChannelClass());
        }
    }

    @Test
    public void useEpollFalseForcesNio() {
        NettyTransport.init(false, true);
        assertFalse("配置显式关掉 epoll 时必须用 NIO", NettyTransport.isEpoll());
        assertFalse("NIO 下不能开 SO_REUSEPORT（会让 bind 直接抛 Unknown channel option）",
                NettyTransport.isSoReusePortEnabled());
        assertEquals(NioServerSocketChannel.class, NettyTransport.serverChannelClass());
    }

    /**
     * 线程数可配：这里只验证「指定了就按指定来」以及线程不是 daemon ——
     * 原实现靠 Netty 的非 daemon 线程撑住 JVM（main 里 bind().sync() 之后就 return 了），
     * 改成 daemon 会让进程启动完立刻退出。
     */
    @Test
    public void eventLoopThreadCountAndNamingAreConfigurable() throws Exception {
        MultithreadEventExecutorGroup fixed =
                (MultithreadEventExecutorGroup) NettyTransport.newEventLoopGroup(3, "batch2-worker");
        try {
            assertEquals(3, fixed.executorCount());

            final AtomicReference<String> threadName = new AtomicReference<String>();
            final AtomicReference<Boolean> daemon = new AtomicReference<Boolean>();
            fixed.submit(new Runnable() {
                @Override
                public void run() {
                    Thread current = Thread.currentThread();
                    threadName.set(current.getName());
                    daemon.set(current.isDaemon());
                }
            }).sync();

            assertTrue("线程名应带上前缀，便于 jstack 排查: " + threadName.get(),
                    threadName.get().startsWith("batch2-worker"));
            assertFalse("event loop 线程不能是 daemon，否则 main 返回后 JVM 会直接退出",
                    daemon.get());
        } finally {
            fixed.shutdownGracefully();
        }
    }

    @Test
    public void zeroThreadsMeansNettyDefault() {
        MultithreadEventExecutorGroup def =
                (MultithreadEventExecutorGroup) NettyTransport.newEventLoopGroup(0, "batch2-default");
        try {
            assertTrue("0 表示交给 Netty 决定（2 * CPU），必须大于 0", def.executorCount() > 0);
        } finally {
            def.shutdownGracefully();
        }
    }
}
