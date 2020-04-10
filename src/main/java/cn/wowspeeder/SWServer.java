package cn.wowspeeder;

import cn.wowspeeder.config.Config;
import cn.wowspeeder.config.ConfigLoader;
import cn.wowspeeder.sw.SWCommon;
import cn.wowspeeder.sw.SWServerCheckerReceive;
import cn.wowspeeder.sw.SWServerCheckerSend;
import cn.wowspeeder.sw.SWServerTcpProxyHandler;
import cn.wowspeeder.websocket.WebSocketServerInitializer;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SWServer {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(SWServer.class);

    private static EventLoopGroup bossGroup = new NioEventLoopGroup();
    private static EventLoopGroup workerGroup = new NioEventLoopGroup();

    private static SWServer SWServer = new SWServer();
    static final boolean SSL = System.getProperty("ssl") != null;

    public static SWServer getInstance() {
        return SWServer;
    }

    private SWServer() {

    }

    public void start(String configPath) throws Exception {
        final Config config = ConfigLoader.load(configPath);
        logger.info("load config !");

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
        tcpBootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 5120)
                .option(ChannelOption.SO_RCVBUF, 32 * 1024)// 读缓冲区为32k
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, false)
                .childOption(ChannelOption.SO_LINGER, 1) //关闭时等待1s发送关闭
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ctx) throws Exception {
//                            ctx.pipeline().addLast(new SSTcpHandler(config));
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

                        //ss
                        ctx.pipeline()
//                                .addLast(new LoggingHandler(LogLevel.INFO))
                                //ss-in
                                .addLast("ssCheckerReceive", new SWServerCheckerReceive())
                                //ss-out
                                .addLast("ssCheckerSend", new SWServerCheckerSend())
                                //ss-websocket
                                .addLast(new WebSocketServerInitializer(sslCtx))
                                //ss-cypt
//                                .addLast("ssCipherCodec", new SSCipherCodec())
                                //ss-proxy
                                .addLast("ssTcpProxy", new SWServerTcpProxyHandler());

                    }
                });

//            logger.info("TCP Start At Port " + config.get_localPort());
        tcpBootstrap.bind(server, port).sync();
        logger.info("listen at {}:{}", server, port);
    }

    public void stop() {
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
            e.printStackTrace();
            getInstance().stop();
            System.exit(-1);
        }
    }

}
