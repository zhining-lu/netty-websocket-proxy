package cn.wowspeeder.socks5;


import cn.wowspeeder.sw.SWCommon;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.socksx.SocksMessage;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.*;

@ChannelHandler.Sharable
public final class SocksServerHandler extends SimpleChannelInboundHandler<SocksMessage> {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(SocksServerHandler.class);

    public static final SocksServerHandler INSTANCE = new SocksServerHandler();

    private SocksServerHandler() {
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, SocksMessage socksRequest) throws Exception {
        switch (socksRequest.version()) {
            case SOCKS5:
                if (socksRequest instanceof Socks5InitialRequest) {
                    // auth support example
                    //ctx.pipeline().addFirst(new Socks5PasswordAuthRequestDecoder());
                    //ctx.write(new DefaultSocks5AuthMethodResponse(Socks5AuthMethod.PASSWORD));

                    ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
                    ctx.write(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));
                } else if (socksRequest instanceof Socks5CommandRequest) {
                    Socks5CommandRequest socks5CmdRequest = (Socks5CommandRequest) socksRequest;
                    if (socks5CmdRequest.type() == Socks5CommandType.CONNECT) {
//                      ctx.pipeline().addLast(new SocksServerConnectHandler());
                        ctx.pipeline().remove(this);
                        //ss-local just res SUCCESS
                        ctx.channel().writeAndFlush(new DefaultSocks5CommandResponse(
                                Socks5CommandStatus.SUCCESS,
                                Socks5AddressType.IPv4,
                                "0.0.0.0",
                                0));

                        ctx.channel().attr(SWCommon.REMOTE_DES_SOCKS5).set(socks5CmdRequest);

//                      ctx.fireChannelRead(socksRequest);

                    } else if (socks5CmdRequest.type() == Socks5CommandType.UDP_ASSOCIATE) {
                        //本项目只实现了 TCP 转发。旧代码在这里回 SUCCESS 并给出一个根本没有监听的
                        //中继地址，客户端会认为 UDP 可用，随后所有数据报被静默丢弃。这里如实返回
                        //COMMAND_UNSUPPORTED，让客户端立刻回退到 TCP。
                        ctx.pipeline().remove(this);
                        ctx.channel().writeAndFlush(new DefaultSocks5CommandResponse(
                                Socks5CommandStatus.COMMAND_UNSUPPORTED,
                                Socks5AddressType.IPv4,
                                "0.0.0.0",
                                0)).addListener(ChannelFutureListener.CLOSE);
                    } else {
                        ctx.close();
                    }

                } else {
                    ctx.close();
                }
                break;
            case UNKNOWN:
                ctx.close();
                break;
        }
//        ReferenceCountUtil.release(socksRequest);

    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
        ctx.fireChannelReadComplete();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable throwable) {
//        throwable.printStackTrace();
//        SocksServerUtils.closeOnFlush(ctx.channel());
        ctx.fireExceptionCaught(throwable);
    }
}