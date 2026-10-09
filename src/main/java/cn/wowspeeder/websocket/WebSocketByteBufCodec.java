package cn.wowspeeder.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.List;

/**
 * 隧道载荷与 WebSocket 二进制帧之间的编解码。
 *
 * <p>出站 {@code ByteBuf} 包成 {@link BinaryWebSocketFrame}，入站帧还原成 {@code ByteBuf}。
 * 隧道两端（本地 {@code SWLocalTcpProxyHandler}、服务端 {@code WebSocketServerInitializer}）
 * 用的就是这一个类 —— 它们原本各有一份逐字相同的实现，没有存在的理由。
 *
 * <p><b>引用计数约定（改这个类之前务必读完）：</b>
 * <ul>
 *   <li>{@code MessageToMessageDecoder} 会在 {@code decode} 返回后 release 一次入站 frame。
 *       而 {@code DefaultWebSocketFrame} 没有独立引用计数（{@code release()} 就是
 *       {@code content.release()}），所以这里必须 <b>只 retain 一次</b>，
 *       把这一份引用交给下游 relay 消费。</li>
 *   <li>上游若是 {@code WebSocketClientHandler}（{@code SimpleChannelInboundHandler}，
 *       返回后自动 release），它转发帧时已经自己 retain 过一次；
 *       这里再多 retain 一次就会让引用计数多 1，最终在下游释放后于上游的自动 release 处
 *       炸成 {@code IllegalReferenceCountException: refCnt: 0}。</li>
 *   <li>出站方向相反：{@code new BinaryWebSocketFrame(msg)} 会接管 {@code msg}，
 *       这里 retain 是为编码器消费准备的那一份。</li>
 * </ul>
 * 两侧的回归测试见 {@code P0RegressionTest} 与 {@code WebSocketRefCountTest}。
 */
public class WebSocketByteBufCodec extends MessageToMessageCodec<WebSocketFrame, ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        out.add(new BinaryWebSocketFrame(msg).retain());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame msg, List<Object> out) throws Exception {
        out.add(msg.content().retain());
    }
}
