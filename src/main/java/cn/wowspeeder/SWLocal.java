package cn.wowspeeder;

import cn.wowspeeder.config.Config;
import cn.wowspeeder.config.ConfigLoader;
import cn.wowspeeder.socks5.SocksServerHandler;
import cn.wowspeeder.sw.SWCommon;
import cn.wowspeeder.sw.SWLocalTcpProxyHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
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


    private static EventLoopGroup bossGroup = new NioEventLoopGroup();
    private static EventLoopGroup workerGroup = new NioEventLoopGroup();

    private static SWLocal SWLocal = new SWLocal();

    public static SWLocal getInstance() {
        return SWLocal;
    }

    private SWLocal() {

    }

    public void start(String configPath) throws Exception {
        final Config config = ConfigLoader.load(configPath);
        logger.info("load config !");

        for (Map.Entry<Integer, String> portPassword : config.getPortPassword().entrySet()) {
            startSingle(config.getLocalAddress(), config.getLocalPort(),
                    config.getServer(),
                    portPassword.getKey(),
                    portPassword.getValue());
        }
    }

    private void startSingle(String socks5Server, Integer socks5Port, String server, Integer port, String password) throws Exception {
        ServerBootstrap tcpBootstrap = new ServerBootstrap();

        //local socks5  server ,tcp
        tcpBootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_RCVBUF, 2 * 1024 * 1024)// 接收缓冲区为2M
                .childOption(ChannelOption.SO_RCVBUF, 2 * 1024 * 1024)// 接收缓冲区为2M
                .childOption(ChannelOption.SO_SNDBUF, 2 * 1024 * 1024)// 发送缓冲区为2M
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, false)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {

                    @Override
                    protected void initChannel(NioSocketChannel ctx) throws Exception {
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
                                .addLast(new SWLocalTcpProxyHandler(server, port, password));
                    }
                });

//            logger.info("TCP Start At Port " + config.get_localPort());
        tcpBootstrap.bind(socks5Server, socks5Port).sync();
        logger.info("listen at {}:{}", socks5Server, socks5Port);
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

    public static void main(String[] args) throws Exception {
        try {
            getInstance().start("conf/config-example-client.json");
        } catch (Exception e) {
            e.printStackTrace();
            getInstance().stop();
            System.exit(-1);
        }
    }

}
