package cn.wowspeeder;

import cn.wowspeeder.config.Config;
import cn.wowspeeder.config.ConfigLoader;
import cn.wowspeeder.socks5.SocksServerHandler;
import cn.wowspeeder.sw.NettyTransport;
import cn.wowspeeder.sw.ProxyStats;
import cn.wowspeeder.sw.SWCommon;
import cn.wowspeeder.sw.SWLocalTcpProxyHandler;
import cn.wowspeeder.sw.SocketOptions;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SWLocal {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(SWLocal.class);


    private static SWLocal SWLocal = new SWLocal();

    /** 启动时按配置创建，见 {@link #start(String)}。 */
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public static SWLocal getInstance() {
        return SWLocal;
    }

    private SWLocal() {

    }

    public void start(String configPath) throws Exception {
        final Config config = ConfigLoader.load(configPath);
        logger.info("load config !");
        // 提前跑一次 PBKDF2 派生 token 密钥，避免第一条连接额外多出约 150ms 握手延迟
        config.warmUpTokenKeys();
        logger.info("payload cipher: {}", config.getPayloadCipher());

        NettyTransport.init(config.isUseEpoll(), config.isSoReuseport());
        // socket 缓冲区默认不设，交给内核按实测 BDP 自动调优；只有配置显式给了值才设。
        SocketOptions.configure(config.getReceiveBuffer(), config.getSendBuffer());
        bossGroup = NettyTransport.newEventLoopGroup(config.getBossThreads(), "sw-local-boss");
        workerGroup = NettyTransport.newEventLoopGroup(config.getWorkerThreads(), "sw-local-worker");
        // 周期性打印活跃连接数 / 隧道建连成功率 / DNS 缓存命中率，间隔见 stats_interval_seconds
        ProxyStats.startReporter(workerGroup, config.getStatsIntervalSeconds());

        for (Map.Entry<Integer, String> portPassword : config.getPortPassword().entrySet()) {
            startSingle(config.getLocalAddress(), config.getLocalPort(),
                    config.getServer(),
                    portPassword.getKey(),
                    portPassword.getValue(),
                    config.isPayloadCipherEnabled());
        }
    }

    private void startSingle(String socks5Server, Integer socks5Port, String server, Integer port, String password,
                             boolean payloadCipherEnabled) throws Exception {
        ServerBootstrap tcpBootstrap = new ServerBootstrap();

        //local socks5  server ,tcp
        tcpBootstrap.group(bossGroup, workerGroup).channel(NettyTransport.serverChannelClass())
                // 注意：SO_RCVBUF/SO_SNDBUF 一律设在 childOption 上。
                // 设在父 channel（监听 socket）上对已接受连接没有任何作用，只是徒增困惑，
                // 原来那行 .option(ChannelOption.SO_RCVBUF, ...) 就是这种情况，已删除。
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                // 浏览器侧写缓冲的水位线：撞到高水位就暂停读隧道，避免慢客户端把数据堆在本地进程里
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, SocketOptions.WRITE_BUFFER_WATER_MARK)
                .childHandler(new ChannelInitializer<Channel>() {

                    @Override
                    protected void initChannel(Channel ctx) throws Exception {
                        logger.debug("channel initializer");
                        ctx.pipeline()
                                //timeout
                                .addLast("timeout", new IdleStateHandler(0, 0, SWCommon.TCP_PROXY_IDEL_TIME, TimeUnit.SECONDS) {
                                    @Override
                                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                                        ctx.close();
                                        return super.newIdleStateEvent(state, first);
                                    }
                                });

                        //socks5
                        ctx.pipeline()
//                                .addLast(new LoggingHandler(LogLevel.INFO))
                                .addLast(new SocksPortUnificationServerHandler())
                                .addLast(SocksServerHandler.INSTANCE)
                                .addLast(new SWLocalTcpProxyHandler(server, port, password, payloadCipherEnabled));
                    }
                });

        // socket 缓冲区：默认这里什么都不做，内核自动调优效果更好（原因见 SocketOptions）
        SocketOptions.applyChild(tcpBootstrap);

        if (NettyTransport.isSoReusePortEnabled()) {
            tcpBootstrap.option(NettyTransport.soReusePortOption(), true);
        }

//            logger.info("TCP Start At Port " + config.get_localPort());
        tcpBootstrap.bind(socks5Server, socks5Port).sync();
        logger.info("listen at {}:{}", socks5Server, socks5Port);
    }

    public void stop() {
        // 停机前补打一次最终统计，避免最后一段时间的数据没被周期任务采到
        ProxyStats.logSummary();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        logger.info("Stop Server!");
    }

    public static void main(String[] args) throws Exception {
        try {
            getInstance().start("conf/config-example-client.json");
        } catch (Exception e) {
            logger.error("start local socks5 proxy failed", e);
            getInstance().stop();
            System.exit(-1);
        }
    }

}
