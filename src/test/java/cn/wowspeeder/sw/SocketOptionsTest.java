package cn.wowspeeder.sw;

import cn.wowspeeder.config.Config;
import io.netty.bootstrap.AbstractBootstrapConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SO_RCVBUF / SO_SNDBUF 配置行为的回归测试。
 *
 * <p>核心断言：<b>默认（不配置）时一个选项都不设</b>。
 * 这是刻意设计 —— 一旦调用 setsockopt，内核会给 socket 打上
 * {@code SOCK_*_LOCK} 标记并关闭 BDP 自动调优，所以「不设」必须是默认行为。
 */
public class SocketOptionsTest {

    /** 每个用例前后都把静态状态复位，避免互相污染。 */
    private void reset() {
        SocketOptions.configure(null, null);
    }

    /**
     * Netty 4.1 的 {@code AbstractBootstrapConfig} 只暴露 {@code options()} 这个 Map，
     * 没有按 key 取值的便捷方法，这里补一个。
     */
    private static Object option(AbstractBootstrapConfig<?, ?> config, ChannelOption<?> key) {
        return config.options().get(key);
    }

    private static Object childOption(ServerBootstrapConfig config, ChannelOption<?> key) {
        return config.childOptions().get(key);
    }

    @After
    public void tearDown() {
        reset();
    }

    @Test
    public void defaultStateDoesNotSetAnything() {
        reset();
        assertFalse("默认必须不是「显式配置」状态", SocketOptions.isExplicit());
        assertEquals("未配置时 SO_RCVBUF 应为 0（表示不设）", 0, SocketOptions.receiveBuffer());
        assertEquals("未配置时 SO_SNDBUF 应为 0（表示不设）", 0, SocketOptions.sendBuffer());

        Bootstrap bootstrap = new Bootstrap();
        SocketOptions.applySend(bootstrap);
        assertNull("默认不应给 Bootstrap 设置 SO_SNDBUF",
                option(bootstrap.config(), ChannelOption.SO_SNDBUF));
        assertNull("默认不应给 Bootstrap 设置 SO_RCVBUF",
                option(bootstrap.config(), ChannelOption.SO_RCVBUF));

        ServerBootstrap server = new ServerBootstrap();
        SocketOptions.applyChild(server);
        assertNull("默认不应给 ServerBootstrap 的 child 设置 SO_SNDBUF",
                childOption(server.config(), ChannelOption.SO_SNDBUF));
        assertNull("默认不应给 ServerBootstrap 的 child 设置 SO_RCVBUF",
                childOption(server.config(), ChannelOption.SO_RCVBUF));
    }

    @Test
    public void nullAndNonPositiveMeanUnset() {
        SocketOptions.configure(null, null);
        assertEquals(0, SocketOptions.receiveBuffer());
        assertEquals(0, SocketOptions.sendBuffer());

        SocketOptions.configure(0, -1);
        assertEquals("0 表示不设", 0, SocketOptions.receiveBuffer());
        assertEquals("负数表示不设", 0, SocketOptions.sendBuffer());
        assertFalse(SocketOptions.isExplicit());

        SocketOptions.configure(-4096, -4096);
        assertFalse(SocketOptions.isExplicit());
    }

    @Test
    public void configuredValuesAreAppliedToBootstrap() {
        SocketOptions.configure(512 * 1024, 1024 * 1024);
        assertTrue(SocketOptions.isExplicit());
        assertEquals(512 * 1024, SocketOptions.receiveBuffer());
        assertEquals(1024 * 1024, SocketOptions.sendBuffer());

        Bootstrap bootstrap = new Bootstrap();
        SocketOptions.applySend(bootstrap);
        assertEquals(Integer.valueOf(512 * 1024),
                option(bootstrap.config(), ChannelOption.SO_RCVBUF));
        assertEquals(Integer.valueOf(1024 * 1024),
                option(bootstrap.config(), ChannelOption.SO_SNDBUF));    }

    @Test
    public void configuredValuesAreAppliedToChildOptions() {
        SocketOptions.configure(256 * 1024, null);
        ServerBootstrap server = new ServerBootstrap();
        SocketOptions.applyChild(server);

        assertEquals("只配了 rcvbuf，sndbuf 必须保持不设",
                Integer.valueOf(256 * 1024),
                childOption(server.config(), ChannelOption.SO_RCVBUF));
        assertNull("只配了 rcvbuf，sndbuf 必须保持不设",
                childOption(server.config(), ChannelOption.SO_SNDBUF));
    }

    /** 两个方向可以独立开关。 */
    @Test
    public void directionsAreIndependent() {
        SocketOptions.configure(null, 300 * 1024);
        assertEquals(0, SocketOptions.receiveBuffer());
        assertEquals(300 * 1024, SocketOptions.sendBuffer());

        Bootstrap bootstrap = new Bootstrap();
        SocketOptions.applySend(bootstrap);
        assertNull(option(bootstrap.config(), ChannelOption.SO_RCVBUF));
        assertEquals(Integer.valueOf(300 * 1024),
                option(bootstrap.config(), ChannelOption.SO_SNDBUF));
    }

    /** 配置里没写这两个 key 时，getter 必须返回 null（= 不设），而不是 0。 */
    @Test
    public void configDefaultsAreNull() {
        Config config = new Config();
        assertNull("未配置 receive_buffer 时应为 null", config.getReceiveBuffer());
        assertNull("未配置 send_buffer 时应为 null", config.getSendBuffer());

        config.setReceiveBuffer(0);
        config.setSendBuffer(-8);
        assertNull("非正数应归一化为 null", config.getReceiveBuffer());
        assertNull("非正数应归一化为 null", config.getSendBuffer());

        config.setReceiveBuffer(524288);
        assertEquals(Integer.valueOf(524288), config.getReceiveBuffer());
        assertEquals(Integer.valueOf(524288), config.getReceiveBuffer());
    }

    /** 把 Config → SocketOptions 的接线串起来验证一遍。 */
    @Test
    public void configFlowsIntoOptions() {
        Config config = new Config();
        config.setReceiveBuffer(384 * 1024);
        config.setSendBuffer(512 * 1024);
        SocketOptions.configure(config.getReceiveBuffer(), config.getSendBuffer());

        assertTrue(SocketOptions.isExplicit());
        assertEquals(384 * 1024, SocketOptions.receiveBuffer());
        assertEquals(512 * 1024, SocketOptions.sendBuffer());
    }

    /** 水位线常量不应被这次改动带偏。 */
    @Test
    public void waterMarkIsUnchanged() {
        assertNotNull(SocketOptions.WRITE_BUFFER_WATER_MARK);
        assertEquals(256 * 1024, SocketOptions.WRITE_BUFFER_WATER_MARK.low());
        assertEquals(1024 * 1024, SocketOptions.WRITE_BUFFER_WATER_MARK.high());
    }

    /** 常量本身要自洽：低于内核默认上限的值填了也白填，这是文档里的告警依据。 */
    @Test
    public void constantsAreSane() {
        assertEquals(208 * 1024, SocketOptions.KERNEL_DEFAULT_MAX);
        assertEquals(2, SocketOptions.KERNEL_BUFFER_MULTIPLIER);
        assertTrue("推荐最小值必须高于内核默认上限，否则会被截断",
                SocketOptions.RECOMMENDED_MIN > SocketOptions.KERNEL_DEFAULT_MAX);
    }

    /**
     * 冒烟：在真实的 NIO transport 上把选项套进 channel 的 config，
     * 确认 {@code SO_SNDBUF} / {@code SO_RCVBUF} 确实落到 channel 上（而不是被静默丢掉）。
     */
    @Test
    public void appliesToRealChannelConfig() {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            SocketOptions.configure(256 * 1024, 512 * 1024);
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new io.netty.channel.ChannelInboundHandlerAdapter());
            SocketOptions.applySend(bootstrap);

            Channel ch = bootstrap.register().channel();
            try {
                assertEquals("SO_RCVBUF 必须真正落到 channel 的 config 上",
                        Integer.valueOf(256 * 1024), ch.config().getOption(ChannelOption.SO_RCVBUF));
                assertEquals("SO_SNDBUF 必须真正落到 channel 的 config 上",
                        Integer.valueOf(512 * 1024), ch.config().getOption(ChannelOption.SO_SNDBUF));
            } finally {
                ch.close().syncUninterruptibly();
            }
        } finally {
            group.shutdownGracefully();
        }
    }

    /** ServerBootstrap 的父 channel 选项不能影响 child —— 这正是原代码用错 option 的地方。 */
    @Test
    public void parentOptionDoesNotLeakToChild() {
        ServerBootstrap server = new ServerBootstrap();
        server.channel(NioServerSocketChannel.class);
        server.option(ChannelOption.SO_RCVBUF, 2 * 1024 * 1024);

        assertNull("父 channel 的 option 不应出现在 childOption 上（原 bug 的根因）",
                childOption(server.config(), ChannelOption.SO_RCVBUF));
    }
}
