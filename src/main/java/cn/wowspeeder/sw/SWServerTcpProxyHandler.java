package cn.wowspeeder.sw;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SWServerTcpProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(SWServerTcpProxyHandler.class);

    private Channel clientChannel;
    private Channel remoteChannel;
    private Bootstrap proxyClient;
    private List<ByteBuf> clientBuffs;

    public SWServerTcpProxyHandler() {
    }


    @Override
    protected void channelRead0(ChannelHandlerContext clientCtx, ByteBuf msg) throws Exception {

        if (this.clientChannel == null) {
            this.clientChannel = clientCtx.channel();
        }
        logger.debug("channel id {},readableBytes:{}", clientChannel.id().toString(), msg.readableBytes());
//        if (msg.readableBytes() == 0) return;
        proxy(clientCtx, msg);
    }

    private void proxy(ChannelHandlerContext clientCtx, ByteBuf msg) {
        logger.debug("channel id {},pc is null {},{}", clientChannel.id().toString(), (remoteChannel == null), msg.readableBytes());
        connectTarget();
        if (clientChannel == null) {
            // 目标连接发起时同步失败，通道已被 proxyChannelClose 关闭；
            // 消息不再缓存，交给 SimpleChannelInboundHandler 自动释放，避免泄漏。
            return;
        }

        if (remoteChannel == null) {
            if (clientBuffs == null) {
                clientBuffs = new ArrayList<>();
            }
            clientBuffs.add(msg.retain());
//            logger.debug("channel id {},add to client buff list", clientChannel.id().toString());
        } else {
            if (clientBuffs == null) {
                remoteChannel.writeAndFlush(msg.retain());
            } else {
                clientBuffs.add(msg.retain());
            }
//            logger.debug("channel id {},remote channel write {}", clientChannel.id().toString(), msg.readableBytes());
        }
    }

    /**
     * 握手阶段提前建连的入口：由 {@link cn.wowspeeder.websocket.HttpHandShakeRequestHandler} 在解析出目标地址后立即调用。
     * <p>
     * 这样「SWServer → 目标」的 TCP 握手能与 WebSocket 握手、首包上传并行推进，
     * 省掉原本必须等首份业务数据才建连的约 1 个 RTT 串行等待。
     */
    public void connectTargetOnHandshake(Channel clientChannel) {
        if (this.clientChannel == null) {
            this.clientChannel = clientChannel;
        }
        connectTarget();
    }

    /**
     * 建立到 {@link SWCommon#REMOTE_DES} 的连接，幂等：已经发起过就直接返回。
     * <p>
     * 连接建立前到达的数据统一缓存在 {@link #clientBuffs}，连上后一次性回放；
     * 因此无论本方法是被握手阶段提前调用，还是被首份数据触发，都不会丢数据。
     */
    private void connectTarget() {
        if (remoteChannel != null || proxyClient != null) {
            return;
        }
        InetSocketAddress clientRecipient = clientChannel.attr(SWCommon.REMOTE_DES).get();
        if (clientRecipient == null) {
            logger.error("channel id {}, REMOTE_DES is null, can not connect target", clientChannel.id().toString());
            proxyChannelClose();
            return;
        }

        proxyClient = new Bootstrap();//
        proxyClient.group(clientChannel.eventLoop()).channel(NettyTransport.clientChannelClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60 * 1000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.SO_RCVBUF, 2 * 1024 * 1024)// 读缓冲区为2M
                .option(ChannelOption.SO_SNDBUF, 2 * 1024 * 1024)// 发送缓冲区为2M
                // 代理场景不应使用 Nagle：本端攒小包与对端 delayed ACK 叠加会白白多出约 40ms
                .option(ChannelOption.TCP_NODELAY, true)
                // 目标侧写缓冲的水位线，配合 BackPressure 把背压传回隧道
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, SWCommon.WRITE_BUFFER_WATER_MARK)
                // 目标地址是未解析的 InetSocketAddress（见 HttpHandShakeRequestHandler），
                // 交给异步解析器，避免在 event loop 上做同步 DNS
                .resolver(AsyncDnsAddressResolverGroup.INSTANCE)
                .handler(
                        new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) throws Exception {
                                ch.pipeline()
                                        .addLast("timeout", new IdleStateHandler(0, 0, SWCommon.TCP_PROXY_IDEL_TIME, TimeUnit.SECONDS) {
                                            @Override
                                            protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                                                logger.debug("{} state:{}", clientRecipient.toString(), state.toString());
                                                proxyChannelClose();
                                                return super.newIdleStateEvent(state, first);
                                            }
                                        })
                                        .addLast("tcpProxy", new SimpleChannelInboundHandler<ByteBuf>() {
                                            @Override
                                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
                                                clientChannel.writeAndFlush(msg.retain());
                                            }

                                            @Override
                                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                                // 目标连上时先对齐一次水位：隧道可能在建连期间就已经不可写
                                                BackPressure.link(clientChannel, ctx.channel());
                                            }

                                            @Override
                                            public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
                                                // 目标写缓冲积压（数据从隧道涌向目标）=> 暂停读隧道
                                                BackPressure.link(clientChannel, ctx.channel());
                                                super.channelWritabilityChanged(ctx);
                                            }

                                            @Override
                                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                                super.channelInactive(ctx);
                                                proxyChannelClose();
                                            }

                                            @Override
                                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//                                                    super.exceptionCaught(ctx, cause);
                                                proxyChannelClose();
                                            }
                                        });
                            }
                        }
                );
        try {
            proxyClient
                    .connect(clientRecipient)
                    .addListener((ChannelFutureListener) future -> {
                        try {
                            if (future.isSuccess()) {
                                logger.debug("channel id {}, {}<->{}<->{} connect  {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), future.channel().localAddress().toString(), clientRecipient.toString(), future.isSuccess());
                                remoteChannel = future.channel();
                                if (clientBuffs != null) {
                                    // 缓存的首包一次 syscall 批量写入，最后统一 flush，避免 N 次 writeAndFlush
                                    for (ByteBuf buf : clientBuffs) {
                                        remoteChannel.write(buf);
                                    }
                                    remoteChannel.flush();
                                    clientBuffs = null;
                                }
                                // 建连期间隧道可能已经变成不可写，这里补一次对齐
                                BackPressure.link(remoteChannel, clientChannel);
                            } else {
                                logger.error("channel id {}, {}<->{} connect {},cause {}", clientChannel.id().toString(), clientChannel.remoteAddress().toString(), clientRecipient.toString(), future.isSuccess(), future.cause());
                                proxyChannelClose();
                            }
                        } catch (Exception e) {
                            proxyChannelClose();
                        }
                    });
        } catch (Exception e) {
            logger.error("connect internet error", e);
            proxyChannelClose();
        }
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        proxyChannelClose();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        // 隧道写缓冲积压（数据从目标涌向隧道）=> 暂停读目标，让背压传到目标服务器
        BackPressure.link(remoteChannel, ctx.channel());
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        //        super.exceptionCaught(ctx,cause);
        logger.debug("client channel exception", cause);
        proxyChannelClose();
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
//            logger.error("close channel error", e);
        }
    }
}
