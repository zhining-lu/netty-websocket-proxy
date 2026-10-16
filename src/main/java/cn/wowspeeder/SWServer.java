package cn.wowspeeder;

import cn.wowspeeder.config.Config;
import cn.wowspeeder.config.ConfigLoader;
import cn.wowspeeder.sw.NettyTransport;
import cn.wowspeeder.sw.ProxyStats;
import cn.wowspeeder.sw.SWCommon;
import cn.wowspeeder.sw.SWServerTcpProxyHandler;
import cn.wowspeeder.sw.SocketOptions;
import cn.wowspeeder.websocket.WebSocketServerInitializer;
import io.netty.channel.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import io.netty.bootstrap.ServerBootstrap;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SWServer {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(SWServer.class);

    private static SWServer SWServer = new SWServer();
    static final boolean SSL = System.getProperty("ssl") != null;

    /** 启动时按配置创建，见 {@link #start(String)}。 */
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public static SWServer getInstance() {
        return SWServer;
    }

    private SWServer() {

    }

    public void start(String configPath) throws Exception {
        final Config config = ConfigLoader.load(configPath);
        logger.info("load config !");

        NettyTransport.init(config.isUseEpoll(), config.isSoReuseport());
        // socket 缓冲区默认不设，交给内核按实测 BDP 自动调优；只有配置显式给了值才设。
        SocketOptions.configure(config.getReceiveBuffer(), config.getSendBuffer());
        bossGroup = NettyTransport.newEventLoopGroup(config.getBossThreads(), "sw-server-boss");
        workerGroup = NettyTransport.newEventLoopGroup(config.getWorkerThreads(), "sw-server-worker");
        // 周期性打印活跃连接数 / 目标建连成功率 / DNS 缓存命中率，间隔见 stats_interval_seconds
        ProxyStats.startReporter(workerGroup, config.getStatsIntervalSeconds());

        for (Map.Entry<Integer, String> portPassword : config.getPortPassword().entrySet()) {
            startSingle(config.getServer(), portPassword.getKey(), portPassword.getValue());
        }
    }

    private void startSingle(String server, Integer port, String password) throws Exception {
        // Configure SSL.
        final SslContext sslCtx;
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        ServerBootstrap tcpBootstrap = new ServerBootstrap();
        tcpBootstrap.group(bossGroup, workerGroup).channel(NettyTransport.serverChannelClass())
                .option(ChannelOption.SO_BACKLOG, 5120)
                // SO_RCVBUF/SO_SNDBUF 只设在 childOption（已接受的连接）上。
                // 父 channel 上的那行 SO_RCVBUF 对连接无作用，已删除；实际是否设置见下方 applyChild。
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                // 隧道侧写缓冲的水位线：撞到高水位就暂停读目标，避免慢客户端把数据堆在服务端进程里
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, SocketOptions.WRITE_BUFFER_WATER_MARK)
                .childOption(ChannelOption.SO_LINGER, 1) //关闭时等待1s发送关闭
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ctx) throws Exception {
                        logger.debug("channel initializer");

                        ctx.attr(SWCommon.PASSWORD).set(password);

                        ctx.pipeline()
                                //timeout
                                .addLast("timeout", new IdleStateHandler(0, 0, SWCommon.TCP_PROXY_IDEL_TIME, TimeUnit.SECONDS) {
                                    @Override
                                    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                                        ctx.close();
                                        return super.newIdleStateEvent(state, first);
                                    }
                                });

                        //ss-websocket
                        ctx.pipeline().addLast(new WebSocketServerInitializer(sslCtx))
                                //ss-proxy
                                .addLast("ssTcpProxy", new SWServerTcpProxyHandler());

                    }
                });

        // socket 缓冲区：默认这里什么都不做，内核自动调优效果更好（原因见 SocketOptions）
        SocketOptions.applyChild(tcpBootstrap);

        if (NettyTransport.isSoReusePortEnabled()) {
            tcpBootstrap.option(NettyTransport.soReusePortOption(), true);
        }

//            logger.info("TCP Start At Port " + config.get_localPort());
        tcpBootstrap.bind(server, port).sync();
        logger.info("listen at {}:{}", server, port);
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


    public static void main(String[] args) throws InterruptedException {
        try {
            getInstance().start("conf/config-example-server.json");
        } catch (Exception e) {
            logger.error("start websocket server failed", e);
            getInstance().stop();
            System.exit(-1);
        }
    }

}
