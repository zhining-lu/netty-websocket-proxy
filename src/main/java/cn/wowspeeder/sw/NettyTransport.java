package cn.wowspeeder.sw;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * 传输层与事件循环的统一出口。
 *
 * <p>解决两个问题：
 * <ol>
 *   <li><b>线程数写死</b>：原来直接 {@code new NioEventLoopGroup()}，worker 线程数固定为
 *       {@code 2 * CPU}。代理场景里线程数应当跟并发连接数走，而不是跟机器核数走 ——
 *       小机器上 2*CPU 可能不够，大机器上又纯属浪费。现在可由配置指定。</li>
 *   <li><b>Linux 上没用上 epoll</b>：Java NIO 在每个平台上都是 poll/select 语义，
 *       只有在 Linux 上换成 epoll 才能拿到边缘触发与更低的系统调用开销。</li>
 * </ol>
 *
 * <p><b>回退策略：</b>{@link Epoll#isAvailable()} 在 native 库缺失时会返回 false
 * （Netty 内部已经把 {@code UnsatisfiedLinkError} 吃掉），此时自动回退 NIO，
 * 不会因为配置写了 epoll 就起不来。要真正用上 epoll，需要把
 * {@code netty-transport-native-epoll} 的 {@code linux-x86_64} classifier 包放进 classpath。
 *
 * <p><b>注意：</b>这里选出的 event loop 线程刻意<b>不是</b> daemon —— 原来的
 * {@code main()} 在 {@code bind().sync()} 之后就直接返回了，全靠 Netty 的非 daemon 线程
 * 把 JVM 撑住；改成 daemon 会让进程启动完立刻退出。
 */
public final class NettyTransport {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NettyTransport.class);

    /** native 库是否真的可用（与配置无关）。 */
    private static final boolean EPOLL_AVAILABLE;

    /** 本次进程最终选用的传输：由 {@link #init(boolean, boolean)} 决定。 */
    private static volatile boolean epoll;

    private static volatile boolean soReusePort;

    static {
        boolean available;
        try {
            available = Epoll.isAvailable();
        } catch (Throwable t) {
            available = false;
        }
        EPOLL_AVAILABLE = available;
        epoll = available;
    }

    private NettyTransport() {
    }

    /**
     * 按配置决定本次进程使用哪种传输。应在启动时调用一次。
     *
     * @param preferEpoll 配置是否希望使用 epoll；native 不可用时会被忽略
     * @param wantReusePort 是否开启 SO_REUSEPORT（只在 epoll 下有意义）
     */
    public static void init(boolean preferEpoll, boolean wantReusePort) {
        epoll = preferEpoll && EPOLL_AVAILABLE;
        soReusePort = wantReusePort && epoll;
        logger.info("transport={} (native epoll available={}), soReusePort={}",
                epoll ? "epoll" : "nio", EPOLL_AVAILABLE, soReusePort);
    }

    public static boolean isEpoll() {
        return epoll;
    }

    public static boolean isSoReusePortEnabled() {
        return soReusePort;
    }

    /**
     * @param threads 0 表示交给 Netty 决定（{@code 2 * CPU}）
     * @param threadNamePrefix 线程名前缀，便于 jstack / 日志排查
     */
    public static EventLoopGroup newEventLoopGroup(int threads, String threadNamePrefix) {
        int nThreads = Math.max(threads, 0);
        DefaultThreadFactory threadFactory = new DefaultThreadFactory(threadNamePrefix);
        return epoll
                ? new EpollEventLoopGroup(nThreads, threadFactory)
                : new NioEventLoopGroup(nThreads, threadFactory);
    }

    public static Class<? extends ServerChannel> serverChannelClass() {
        return epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    public static Class<? extends Channel> clientChannelClass() {
        return epoll ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    /**
     * SO_REUSEPORT 只有 epoll 后端认；在 NIO 上设置会直接抛
     * {@code ChannelException: Unknown channel option}，所以调用前必须判 {@link #isSoReusePortEnabled()}。
     */
    public static ChannelOption<Boolean> soReusePortOption() {
        return EpollChannelOption.SO_REUSEPORT;
    }
}
