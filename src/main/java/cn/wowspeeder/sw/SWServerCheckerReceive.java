package cn.wowspeeder.sw;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;

public class SWServerCheckerReceive extends SimpleChannelInboundHandler<Object> {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(SWServerCheckerReceive.class);

    public SWServerCheckerReceive() {
        super(false);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {

        ctx.channel().attr(SWCommon.RemoteAddr).set((InetSocketAddress) ctx.channel().remoteAddress());
        ctx.channel().pipeline().remove(this);
        ctx.fireChannelRead(msg);

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }
}
