package cn.wowspeeder;

import cn.wowspeeder.encryption.Base64Encrypt;
import cn.wowspeeder.sw.SWCommon;
import cn.wowspeeder.websocket.HttpHandShakeRequestHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;

import java.net.InetSocketAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link HttpHandShakeRequestHandler} 的引用计数回归测试。
 *
 * <p>复刻的异常现场：解析 uri 上的 token 失败时（缺参数、token 格式不对、解密后不是 host:port），
 * 原实现直接 {@code throw}，而本类继承的是 {@code ChannelInboundHandlerAdapter} —— 不像
 * {@code SimpleChannelInboundHandler} 那样在返回后自动 release。此时经 {@code HttpObjectAggregator}
 * 聚合出来的 {@code FullHttpRequest} 既不会被转发给下游，也没有人替它 release，
 * ByteBuf 就永久泄漏（Netty 的 {@code ResourceLeakDetector} 会在 GC 时报
 * "LEAK: ByteBuf.release() was not called before it's garbage-collected"，
 * 表现为 long GC 之后堆内存不降反升）。
 *
 * <p>测试里直接投喂聚合好的 {@code FullHttpRequest}，所以 pipeline 上不需要
 * {@code HttpObjectAggregator}，只需要被测 handler 本身。
 */
public class HttpHandShakeRefCountTest {

    /** 服务端密码。所有用例共用同一个密码，避免 Base64Encrypt 的按密码缓存串台。 */
    private static final String PASSWORD = "test-password";

    /** 把下游收到的消息记下来，用于确认正常路径下 msg 确实被转发了（且没被提前释放）。 */
    private static final class DownstreamCapture extends ChannelInboundHandlerAdapter {
        Object received;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            this.received = msg;
        }
    }

    private static FullHttpRequest newRequest(String uri) {
        ByteBuf content = Unpooled.buffer();
        content.writeBytes("body".getBytes());
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri, content);
    }

    private static EmbeddedChannel newChannel(DownstreamCapture capture) {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpHandShakeRequestHandler(), capture);
        channel.attr(SWCommon.PASSWORD).set(PASSWORD);
        return channel;
    }

    /**
     * 投喂请求并返回 handler 抛出的异常。
     *
     * <p>注意本版 netty（4.1.42）的 {@code EmbeddedChannel} 会把 handler 在 channelRead 里抛出的
     * 异常直接从 {@code writeInbound} 抛出，{@code checkException()} 不会再看到它，
     * 所以只能在 writeInbound 外面接。
     */
    private static Throwable writeInboundExpectingFailure(EmbeddedChannel channel, FullHttpRequest request) {
        try {
            channel.writeInbound(request);
        } catch (Throwable t) {
            return t;
        }
        fail("解析失败路径必须抛出异常");
        return null;
    }

    /** 断言失败路径的通用部分：msg 被释放、没有交给下游。 */
    private static void assertReleasedAndNotForwarded(FullHttpRequest request, DownstreamCapture capture) {
        assertEquals("解析失败后 msg 必须被释放，否则就是一次 ByteBuf 泄漏", 0, request.refCnt());
        assertNull("失败路径不应把请求交给下游", capture.received);
    }

    /**
     * uri 里完全没有 {@code ?}：{@code split} 后 length == 1，直接抛 UnsupportedOperationException。
     * 修复前 msg 的引用计数停在 1（泄漏）。
     */
    @Test
    public void missingQueryArgMustReleaseRequest() {
        DownstreamCapture capture = new DownstreamCapture();
        EmbeddedChannel channel = newChannel(capture);
        FullHttpRequest request = newRequest("/websocket");
        assertEquals("投喂前引用计数", 1, request.refCnt());

        Throwable t = writeInboundExpectingFailure(channel, request);
        assertTrue("期望 UnsupportedOperationException，实际：" + t, t instanceof UnsupportedOperationException);
        assertTrue("应当是参数不正确的提示，实际：" + t.getMessage(),
                t.getMessage().contains("url args not right"));
        assertReleasedAndNotForwarded(request, capture);

        channel.close();
    }

    /**
     * 有 {@code ?} 但 query 不是 {@code token=xxx} 的形式：同样抛异常，同样必须释放。
     */
    @Test
    public void malformedQueryArgMustReleaseRequest() {
        DownstreamCapture capture = new DownstreamCapture();
        EmbeddedChannel channel = newChannel(capture);
        FullHttpRequest request = newRequest("/websocket?foo=bar");
        assertEquals("投喂前引用计数", 1, request.refCnt());

        Throwable t = writeInboundExpectingFailure(channel, request);
        assertTrue("期望 UnsupportedOperationException，实际：" + t, t instanceof UnsupportedOperationException);
        assertReleasedAndNotForwarded(request, capture);

        channel.close();
    }

    /**
     * token 不是合法 Base64：{@code Base64.getDecoder().decode()} 直接抛
     * IllegalArgumentException —— 异常发生在本类为「自己没交出去」的 msg 兜底释放之后仍必须生效。
     */
    @Test
    public void invalidBase64TokenMustReleaseRequest() {
        DownstreamCapture capture = new DownstreamCapture();
        EmbeddedChannel channel = newChannel(capture);
        FullHttpRequest request = newRequest("/websocket?token=@@not-base64@@");
        assertEquals("投喂前引用计数", 1, request.refCnt());

        Throwable t = writeInboundExpectingFailure(channel, request);
        assertTrue("期望 IllegalArgumentException，实际：" + t, t instanceof IllegalArgumentException);
        assertReleasedAndNotForwarded(request, capture);

        channel.close();
    }

    /**
     * token 能正常解密，但明文不是 {@code host:port}（这里加密的是 "not-a-host-port"）：
     * 抛「密码不对」的 UnsupportedOperationException，同样必须释放。
     */
    @Test
    public void malformedPlainTokenMustReleaseRequest() throws Exception {
        DownstreamCapture capture = new DownstreamCapture();
        EmbeddedChannel channel = newChannel(capture);
        String token = Base64Encrypt.getInstance(PASSWORD).getEncString("not-a-host-port");
        FullHttpRequest request = newRequest("/websocket?token=" + token);
        assertEquals("投喂前引用计数", 1, request.refCnt());

        Throwable t = writeInboundExpectingFailure(channel, request);
        assertTrue("期望 UnsupportedOperationException，实际：" + t, t instanceof UnsupportedOperationException);
        assertTrue("应当是密码不对的提示，实际：" + t.getMessage(),
                t.getMessage().contains("password is not right"));
        assertReleasedAndNotForwarded(request, capture);

        channel.close();
    }

    /**
     * 正常握手请求：msg 必须原封不动交给下游（引用计数仍是 1，绝不能在这里被释放掉，
     * 否则下游的 WebSocketServerProtocolHandler 会拿到一个已释放的请求），
     * 同时 handler 要把自己从 pipeline 上摘掉。
     */
    @Test
    public void validTokenMustForwardRequestWithoutReleasingIt() throws Exception {
        DownstreamCapture capture = new DownstreamCapture();
        EmbeddedChannel channel = newChannel(capture);
        String token = Base64Encrypt.getInstance(PASSWORD).getEncString("127.0.0.1:443");
        FullHttpRequest request = newRequest("/websocket?token=" + token);
        assertEquals("投喂前引用计数", 1, request.refCnt());

        channel.writeInbound(request);

        channel.checkException();
        assertSame("正常路径必须把同一个请求对象交给下游", request, capture.received);
        assertEquals("交给下游时引用计数必须还是 1（不能多释放）", 1, request.refCnt());
        assertEquals("uri 里的 query 部分应当被去掉", "/websocket", ((HttpRequest) capture.received).uri());
        assertEquals("目标地址应当写入 channel 属性",
                InetSocketAddress.createUnresolved("127.0.0.1", 443),
                channel.attr(SWCommon.REMOTE_DES).get());
        assertNull("解析完成后 handler 应当把自己从 pipeline 上移除",
                channel.pipeline().get(HttpHandShakeRequestHandler.class));

        // 交给下游后由下游负责释放
        ReferenceCountUtil.release(capture.received);
        assertEquals("下游释放后引用计数归零", 0, request.refCnt());

        channel.close();
    }
}
