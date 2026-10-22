package cn.wowspeeder.sw;

import cn.wowspeeder.encryption.Base64Encrypt;
import cn.wowspeeder.websocket.WebSocketByteBufCodec;
import cn.wowspeeder.websocket.WebSocketClientHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
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
import java.util.concurrent.TimeUnit;

public class SWLocalTcpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(SWLocalTcpProxyHandler.class);

    private InetSocketAddress ssServer;
    private Socks5CommandRequest remoteAddr;
    private Channel clientChannel;
    private Channel remoteChannel;
    private Bootstrap proxyClient;
    private String password;
    private List<ByteBuf> clientBuffs;


    public SWLocalTcpProxyHandler(String server, Integer port, String password) {
        this.password = password;
        // 刻意用 createUnresolved：new InetSocketAddress(server, port) 会在构造时就同步查 DNS，
        // 而本构造器是在 SOCKS 通道 initChannel 里被调用的（跑在 event loop 上）。
        // 解析交给 AsyncDnsAddressResolverGroup 在独立线程池里做，见下面 proxyClient 的 resolver()。
        ssServer = InetSocketAddress.createUnresolved(server, port);
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
            ProxyStats.tunnelAttempt();
            // url =  ws://127.0.0.1:8080/websocket?token=<base64url(IV||AES-256-GCM(host:port)||tag)>
            // token 用的是 URL 安全 Base64 且不含换行，可以直接拼进 query。
            Base64Encrypt base64 = Base64Encrypt.getInstance(password);

            String url = "ws://"+ssServer.getHostString()+":"+ssServer.getPort()+"/websocket?token="+ base64.getEncString(remoteAddr.dstAddr()+":"+remoteAddr.dstPort());
            // 打印完整 url（含 token）。token 里已加密了目标地址，排查时能直接复用整条 url 重放请求。
            logger.info("channel id {}, connect url: {}", clientChannel.id().toString(), url);
            URI uri = new URI(url);

            final SslContext sslCtx = getSslContext(uri);

            // Connect with V13 (RFC 6455 aka HyBi-17). You can change it to V08 or V00.
            // If you change it to V00, ping is not supported and remember to change
            // HttpResponseDecoder to WebSocketHttpResponseDecoder in the pipeline.
            final WebSocketClientHandler handler =
                    new WebSocketClientHandler(
                            WebSocketClientHandshakerFactory.newHandshaker(
                                    uri, WebSocketVersion.V13, null, true,new DefaultHttpHeaders(), 65536 * 5));


            proxyClient = new Bootstrap();//
            proxyClient.group(clientChannel.eventLoop()).channel(NettyTransport.clientChannelClass())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60 * 1000)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    // proxyClient 的 SO_RCVBUF/SO_SNDBUF 不再硬编码。
                    // 原来写的 2MB 会被内核先截断到 net.core.wmem_max（默认 208KB）再翻倍，
                    // 实际生效值由系统参数决定；更糟的是设置它会关闭内核的 BDP 自动调优，
                    // 在高速链路上反而成为瓶颈。默认不设，交给内核自适应。
                    // 需要时用配置项 receive_buffer / send_buffer 显式开启，见 SocketOptions。
                    // 代理场景不应使用 Nagle：本端攒小包与对端 delayed ACK 叠加会白白多出约 40ms
                    .option(ChannelOption.TCP_NODELAY, true)
                    // 隧道侧写缓冲的水位线，配合 BackPressure 把背压传到浏览器
                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK, SocketOptions.WRITE_BUFFER_WATER_MARK)
                    // ssServer 是未解析地址，异步解析避免阻塞 event loop
                    .resolver(AsyncDnsAddressResolverGroup.INSTANCE)
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
                                            .addLast(new HttpObjectAggregator(65536 * 5))
                                            .addLast(handler)
                                            .addLast(new WebSocketByteBufCodec());

//                                    pipeline.addLast("ssCipherCodec", new SSCipherCodec());
                                    pipeline.addLast("relay", new SimpleChannelInboundHandler<ByteBuf>() {
                                                @Override
                                                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
                                                    clientChannel.writeAndFlush(msg.retain());
                                                }

                                                @Override
                                                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                                    ProxyStats.tunnelOpened();
                                                    // 隧道连上时先对齐一次水位：浏览器一侧可能已经不可写
                                                    BackPressure.link(clientChannel, ctx.channel());
                                                }

                                                @Override
                                                public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
                                                    // 隧道写缓冲积压（数据从浏览器涌向隧道）=> 暂停读浏览器
                                                    BackPressure.link(clientChannel, ctx.channel());
                                                    super.channelWritabilityChanged(ctx);
                                                }

                                                @Override
                                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                                    super.channelInactive(ctx);
                                                    ProxyStats.tunnelClosed();
                                                    proxyChannelClose();
                                                }

                                                @Override
                                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//                                                    super.exceptionCaught(ctx, cause);
                                                    logger.debug("relay exception", cause);
                                                    proxyChannelClose();
                                                }
                                            })
                                    ;
                                }
                            }
                    );
            // socket 缓冲区：默认不设，内核自动调优更好（原因见 SocketOptions）
            SocketOptions.applySend(proxyClient);
            try {

                proxyClient
                        .connect(ssServer).addListener((ChannelFutureListener)f -> {

                            if(f.isSuccess()){
//                                logger.debug("channel id {}, {}<->{}<->{} connect  {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), f.channel().localAddress().toString(), ssServer.toString(), f.isSuccess());
                                handler.handshakeFuture()
                                        .addListener((ChannelFutureListener) future -> {
                                            try {
                                                if (future.isSuccess()) {
                                                    remoteChannel = future.channel();
                                                    // 先记账：终态判定不依赖 clientChannel。浏览器可能在建连完成前就断开，
                                                    // 此时 channelInactive -> proxyChannelClose() 已把 clientChannel 置为 null；
                                                    // 若在记账之前访问 clientChannel 会抛 NPE 被下面的 catch 吞掉，导致这次尝试
                                                    // 永远既不算成功也不算失败（线上 attempt=60/success=56/fail=0 的 4 个缺口即源于此）。
                                                    ProxyStats.tunnelSucceeded();

                                                    // 浏览器在建连期间已断开：隧道本身建成功（已计入成功），但已无对端可用，
                                                    // 直接关闭，避免留下悬挂连接。
                                                    if (clientChannel == null) {
                                                        logger.debug("client closed before tunnel ready, {}<->{} handshake {}", future.channel().localAddress().toString(), ssServer.toString(), future.isSuccess());
                                                        proxyChannelClose();
                                                        return;
                                                    }

                                                    logger.debug("channel id {}, {}<->{}<->{} handshake  {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), future.channel().localAddress().toString(), ssServer.toString(), future.isSuccess());

                                                    //write remaining bufs
                                                    if (clientBuffs != null) {
                                                        // 缓存的首包一次 syscall 批量写入，最后统一 flush，避免 N 次 writeAndFlush
                                                        for (ByteBuf buf : clientBuffs) {
                                                            remoteChannel.write(buf);
                                                        }
                                                        remoteChannel.flush();
                                                        clientBuffs = null;
                                                    }
                                                    // 建连期间浏览器侧可能已经不可写，这里补一次对齐
                                                    BackPressure.link(remoteChannel, clientChannel);
                                                } else {
                                                    // 先记账：同上，不依赖 clientChannel
                                                    ProxyStats.tunnelFailed();
                                                    if (clientChannel == null) {
                                                        logger.error("client closed, {} handshake {},cause {}", ssServer.toString(), future.isSuccess(), future.cause());
                                                    } else {
                                                        logger.error("channel id {}, {}<->{} handshake {},cause {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), ssServer.toString(), future.isSuccess(), future.cause());
                                                    }
                                                    proxyChannelClose();
                                                }
                                            } catch (Exception e) {
                                                proxyChannelClose();
                                            }
                                        });
                            }else{
                                logger.error("channel id {}, {}<->{} connect {},cause {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), ssServer.toString(), f.isSuccess(), f.cause());
                                ProxyStats.tunnelFailed();
                                proxyChannelClose();
                            }

                });

             } catch (Exception e) {
                logger.error("connect internet error", e);
                ProxyStats.tunnelFailed();
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
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ProxyStats.socksOpened();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        ProxyStats.socksClosed();
        proxyChannelClose();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        // 浏览器写缓冲积压（数据从隧道涌向浏览器）=> 暂停读隧道
        BackPressure.link(remoteChannel, ctx.channel());
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("socks channel exception", cause);
        proxyChannelClose();
    }

    private SslContext getSslContext(URI uri) throws SSLException {
        SslContext sslCtx;

        String scheme = uri.getScheme() == null? "ws" : uri.getScheme();

        if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
            logger.error("Only WS(S) is supported.");
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
