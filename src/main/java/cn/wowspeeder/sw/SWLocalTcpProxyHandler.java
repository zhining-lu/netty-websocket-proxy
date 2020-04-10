package cn.wowspeeder.sw;

import cn.wowspeeder.encryption.Base64Encrypt;
import cn.wowspeeder.websocket.WebSocketClientHandler;
import cn.wowspeeder.websocket.WebSocketLocalFrameHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;

public class SWLocalTcpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(SWServerTcpProxyHandler.class);

    private InetSocketAddress ssServer;
    private Socks5CommandRequest remoteAddr;
    private Channel clientChannel;
    private Channel remoteChannel;
    private Bootstrap proxyClient;
    private String password;
    private List<ByteBuf> clientBuffs;


    public SWLocalTcpProxyHandler(String server, Integer port, String password) {
        this.password = password;
        ssServer = new InetSocketAddress(server, port);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, ByteBuf msg) throws Exception {

        if (this.clientChannel == null) {
            this.clientChannel = clientCtx.channel();
            this.remoteAddr = clientChannel.attr(SWCommon.REMOTE_DES_SOCKS5).get();
        }
        logger.debug("channel id {},readableBytes:{}", clientChannel.id().toString(), msg.readableBytes());
//        if (msg.readableBytes() == 0) return;
        proxy(clientCtx, msg);
    }

    private void proxy(ChannelHandlerContext clientCtx, ByteBuf msg) throws Exception {
        logger.debug("channel id {},pc is null {},{}", clientChannel.id().toString(), (remoteChannel == null), msg.readableBytes());
        if (remoteChannel == null && proxyClient == null) {
            // url =  ws://127.0.0.1:8080/websocket?token=123.456.44.7:8080
            Base64Encrypt base64 = Base64Encrypt.getInstance(password);

            String url = "ws://"+ssServer.getHostString()+":"+ssServer.getPort()+"/websocket?token="+ base64.getEncString(remoteAddr.dstAddr()+":"+remoteAddr.dstPort());
            logger.info("============"+ remoteAddr.dstAddr() +"====" + url);
            URI uri = new URI(url);

            final SslContext sslCtx = getSslContext(uri);

            // Connect with V13 (RFC 6455 aka HyBi-17). You can change it to V08 or V00.
            // If you change it to V00, ping is not supported and remember to change
            // HttpResponseDecoder to WebSocketHttpResponseDecoder in the pipeline.
            final WebSocketClientHandler handler =
                    new WebSocketClientHandler(
                            WebSocketClientHandshakerFactory.newHandshaker(
                                    uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders()));


            proxyClient = new Bootstrap();//
            proxyClient.group(clientChannel.eventLoop()).channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60 * 1000)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.SO_RCVBUF, 32 * 1024)// 读缓冲区为32k
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(
                            new ChannelInitializer<Channel>() {
                                @Override
                                protected void initChannel(Channel ch) throws Exception {
                                    logger.debug("channel initializer");

                                    ch.pipeline()
                                            .addLast("timeout", new IdleStateHandler(0, 0, SWCommon.TCP_PROXY_IDEL_TIME, TimeUnit.SECONDS) {
                                                @Override
                                                protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                                                    logger.debug("{} state:{}", ssServer.toString(), state.toString());
                                                    proxyChannelClose();
                                                    return super.newIdleStateEvent(state, first);
                                                }
                                            });


                                    //ss-out
                                    ChannelPipeline pipeline = ch.pipeline();
                                    if (sslCtx != null) {
                                        pipeline.addLast(sslCtx.newHandler(ch.alloc(), ssServer.getHostString(), ssServer.getPort()));
                                    }

                                    pipeline.addLast(new HttpClientCodec())
                                            .addLast(new HttpObjectAggregator(8192))
                                            .addLast(WebSocketClientCompressionHandler.INSTANCE)
                                            .addLast(handler)
                                            .addLast(new WebSocketLocalFrameHandler());

//                                    pipeline.addLast("ssCipherCodec", new SSCipherCodec());
                                    pipeline.addLast("relay", new SimpleChannelInboundHandler<ByteBuf>() {
                                                @Override
                                                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
                                                    clientChannel.writeAndFlush(msg.retain());
                                                }

                                                @Override
                                                public void channelActive(ChannelHandlerContext ctx) throws Exception {
//                                                    logger.debug("channelActive {}",msg.readableBytes());
                                                }

                                                @Override
                                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                                    super.channelInactive(ctx);
                                                    proxyChannelClose();
                                                }

                                                @Override
                                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//                                                    super.exceptionCaught(ctx, cause);
                                                    cause.printStackTrace();
                                                    proxyChannelClose();
                                                }
                                            })
                                    ;
                                }
                            }
                    );
            try {

                proxyClient
                        .connect(ssServer).addListener((ChannelFutureListener)f -> {

                            if(f.isSuccess()){
//                                logger.debug("channel id {}, {}<->{}<->{} connect  {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), f.channel().localAddress().toString(), ssServer.toString(), f.isSuccess());
                                handler.handshakeFuture()
                                        .addListener((ChannelFutureListener) future -> {
                                            try {
                                                if (future.isSuccess()) {
                                                    logger.debug("channel id {}, {}<->{}<->{} handshake  {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), future.channel().localAddress().toString(), ssServer.toString(), future.isSuccess());
                                                    remoteChannel = future.channel();

                                                    //write remaining bufs
                                                    if (clientBuffs != null) {
                                                        ListIterator<ByteBuf> bufsIterator = clientBuffs.listIterator();
                                                        while (bufsIterator.hasNext()) {
                                                            remoteChannel.writeAndFlush(bufsIterator.next());
                                                        }
                                                        clientBuffs = null;
                                                    }
                                                } else {
                                                    logger.error("channel id {}, {}<->{} handshake {},cause {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), ssServer.toString(), future.isSuccess(), future.cause());
                                                    proxyChannelClose();
                                                }
                                            } catch (Exception e) {
                                                proxyChannelClose();
                                            }
                                        });
                            }else{
//                                logger.error("channel id {}, {}<->{} connect {},cause {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), ssServer.toString(), f.isSuccess(), f.cause());
                                proxyChannelClose();
                            }

                });

             } catch (Exception e) {
                logger.error("connect internet error", e);
                proxyChannelClose();
                return;
            }
        }

        if (remoteChannel == null) {
            if (clientBuffs == null) {
                clientBuffs = new ArrayList<>();
            }
            clientBuffs.add(msg.retain());//
            logger.debug("channel id {},add to client buff list", clientChannel.id().toString());
        } else {
            if (clientBuffs == null) {
                remoteChannel.writeAndFlush(msg.retain());
            } else {
                clientBuffs.add(msg.retain());//
            }
            logger.debug("channel id {},remote channel write {}", clientChannel.id().toString(), msg.readableBytes());
        }
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        proxyChannelClose();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//        super.exceptionCaught(ctx, cause);
        proxyChannelClose();
    }

    private SslContext getSslContext(URI uri) throws SSLException {
        SslContext sslCtx;

        String scheme = uri.getScheme() == null? "ws" : uri.getScheme();

        if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
            System.err.println("Only WS(S) is supported.");
            return null;
        }

        boolean ssl = "wss".equalsIgnoreCase(scheme);

        if (ssl) {
            sslCtx = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } else {
            sslCtx = null;
        }
        return  sslCtx;
    }


    private void proxyChannelClose() {
//        logger.info("proxyChannelClose");
        try {
            if (clientBuffs != null) {
                clientBuffs.forEach(ReferenceCountUtil::release);
                clientBuffs = null;
            }
            if (remoteChannel != null) {
                remoteChannel.close();
                remoteChannel = null;
            }
            if (clientChannel != null) {
                clientChannel.close();
                clientChannel = null;
            }
        } catch (Exception e) {
            logger.error("close channel error", e);
        }
    }
}
