package cn.wowspeeder;

import cn.wowspeeder.websocket.WebSocketClientHandler;
import cn.wowspeeder.websocket.WebSocketByteBufCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SWLocal（客户端）入站链路的引用计数回归测试。
 *
 * <p>复刻的异常现场：
 * <pre>
 * io.netty.util.IllegalReferenceCountException: refCnt: 0, decrement: 1
 *   at io.netty.buffer.DefaultByteBufHolder.release(DefaultByteBufHolder.java:117)
 *   at io.netty.channel.SimpleChannelInboundHandler.channelRead(SimpleChannelInboundHandler.java:112)
 *   at io.netty.handler.codec.MessageToMessageDecoder.channelRead(MessageToMessageDecoder.java:102)
 * </pre>
 *
 * <p>被复刻的链路与 {@code SWLocalTcpProxyHandler} 里构建的一致（握手响应在测试里直接以
 * 聚合好的 FullHttpResponse 投喂，所以省掉 HttpObjectAggregator）：
 * <pre>
 * HttpClientCodec
 *   -&gt; WebSocketClientHandler (SimpleChannelInboundHandler&lt;Object&gt;，返回后自动 release)
 *     -&gt; WebSocketByteBufCodec (MessageToMessageCodec)
 *       -&gt; relay (SimpleChannelInboundHandler&lt;ByteBuf&gt;，writeAndFlush(msg.retain()))
 * </pre>
 *
 * <p>关键点：{@code DefaultWebSocketFrame} 没有独立引用计数，{@code frame.release()}
 * 就是 {@code content.release()}。所以「谁 retain、谁 release」必须严格配对，多一次少一次
 * 都会在链路末尾炸成 IllegalReferenceCountException。
 */
public class WebSocketRefCountTest {

    private static final String SUBPROTOCOL = "test-proto";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * 与 SWLocalTcpProxyHandler 中的 relay 等价：拿到 ByteBuf 后 retain 一次交给写操作。
     *
     * <p>WS 通道与 SOCKS 通道共用同一个 event loop（{@code proxyClient.group(clientChannel.eventLoop())}），
     * 小包在 {@code clientChannel.writeAndFlush()} 内部就会写完并释放引用，所以这里用一个立即完成的
     * write 来复刻这个时序——这个时序正是原来那处 double release 能暴露出来的前提。
     */
    private static final class RelayLikeHandler extends SimpleChannelInboundHandler<ByteBuf> {
        final AtomicInteger received = new AtomicInteger();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            ByteBuf forWrite = msg.retain();
            // 写操作同步完成并释放它自己那份引用
            forWrite.release();
            received.incrementAndGet();
        }
    }

    /** 在编码器之前把出站握手请求截下来，这样测试里不必去解析已编码的字节。 */
    private static final class HandshakeRequestSpy extends ChannelDuplexHandler {
        final AtomicReference<HttpRequest> request = new AtomicReference<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof HttpRequest) {
                request.set((HttpRequest) msg);
            }
            super.write(ctx, msg, promise);
        }
    }

    /** 搭出客户端入站链路并把 WS 握手推到完成状态。 */
    private static final class ClientPipeline {
        final EmbeddedChannel channel;
        final WebSocketClientHandler clientHandler;
        final RelayLikeHandler relay = new RelayLikeHandler();

        ClientPipeline() throws Exception {
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    new URI("ws://127.0.0.1:80/websocket"), WebSocketVersion.V13, SUBPROTOCOL, true,
                    new DefaultHttpHeaders(), 65536 * 5);
            clientHandler = new WebSocketClientHandler(handshaker);
            HandshakeRequestSpy spy = new HandshakeRequestSpy();
            channel = new EmbeddedChannel(
                    new HttpClientCodec(), clientHandler, spy, new WebSocketByteBufCodec(), relay);

            // channelActive -> handshaker.handshake()：管道里必须有 HttpResponseDecoder/HttpClientCodec，
            // 否则 Netty 只会把 handshakeFuture 置为失败而不发请求。
            HttpRequest request = spy.request.get();
            assertNotNull("channelActive 应当已经发出 WS 握手请求", request);
            String key = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_KEY);
            assertNotNull("握手请求里必须带 Sec-WebSocket-Key", key);

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS);
            response.headers().set(HttpHeaderNames.UPGRADE, "websocket");
            response.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
            response.headers().set(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, acceptOf(key));
            response.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, SUBPROTOCOL);
            channel.writeInbound(response);

            // checkException() 为 void：管道里记录过异常时会直接把原始异常抛出来
            channel.checkException();
            assertTrue("握手应当成功，否则后续用例没有意义", clientHandler.handshakeFuture().isSuccess());
        }

        private static String acceptOf(String key) throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        }
    }

    /**
     * 下行二进制帧：WebSocketClientHandler 转发时必须 retain。
     *
     * <p>修复前（{@code ctx.fireChannelRead(frame)} 不带 retain）release 次数比 retain 多 1，
     * 最后 WebSocketClientHandler 的自动 release 会作用在 refCnt=0 的 buffer 上，直接抛异常
     * 并连带 proxyChannelClose() 断掉整条隧道。
     */
    @Test
    public void binaryFrameForwardingMustKeepRefCntBalanced() throws Exception {
        ClientPipeline pipeline = new ClientPipeline();
        ByteBuf payload = Unpooled.buffer();
        payload.writeBytes(new byte[]{1, 2, 3, 4});
        assertEquals("入站帧的初始引用计数", 1, payload.refCnt());

        pipeline.channel.writeInbound(new BinaryWebSocketFrame(payload));

        pipeline.channel.checkException();
        assertEquals("帧应当被转发到 relay", 1, pipeline.relay.received.get());
        assertEquals("retain/release 必须配平，链路走完后不应残留引用", 0, payload.refCnt());

        pipeline.channel.close();
    }

    /**
     * Close 帧：原实现在分支里手动 {@code frame.release()}，而 SimpleChannelInboundHandler
     * 返回后还会自动 release 一次，必然二次释放（这条与是否 inline 完成 write 无关，是必现的）。
     */
    @Test
    public void closeFrameMustNotBeReleasedTwice() throws Exception {
        ClientPipeline pipeline = new ClientPipeline();
        CloseWebSocketFrame frame = new CloseWebSocketFrame(1000, "bye");
        ByteBuf content = frame.content();
        assertEquals("入站帧的初始引用计数", 1, content.refCnt());

        pipeline.channel.writeInbound(frame);

        pipeline.channel.checkException();
        assertEquals("Close 帧的内容必须被释放干净", 0, content.refCnt());

        pipeline.channel.close();
    }
}
