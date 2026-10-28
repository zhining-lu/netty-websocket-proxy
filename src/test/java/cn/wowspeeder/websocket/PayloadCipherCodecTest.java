package cn.wowspeeder.websocket;

import cn.wowspeeder.encryption.PayloadCipher;
import cn.wowspeeder.encryption.PskDeriver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayloadCipherCodec} 的管线级测试（EmbeddedChannel）。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li>只有 ByteBuf 会被密封 —— 客户端握手用的 {@code FullHttpRequest} 必须原样放行，
 *       否则 {@code WebSocketClientHandshaker.handshake()} 发出去的请求会被当成载荷加密掉；</li>
 *   <li>两端用同一 salt 派生的方向密钥互通，且引用计数与 {@code WebSocketByteBufCodec} 同一套约定；</li>
 *   <li>服务端在握手前处于未初始化状态，此时收/发载荷一律失败断连（fail-closed）。</li>
 * </ol>
 */
public class PayloadCipherCodecTest {

    private static final String PASSWORD = "889900";
    private static final byte[] SALT = "saltsalt".getBytes(StandardCharsets.US_ASCII);

    /** 记录下游收到的入站明文，用于引用计数与内容断言。 */
    private static final class DownstreamCapture extends ChannelInboundHandlerAdapter {
        ByteBuf received;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            this.received = (ByteBuf) msg;
        }
    }

    /**
     * 复刻 relay / ssTcpProxy 的异常行为：收到 exceptionCaught 就关连接。
     * 真实管线里 close 由这两个转发 handler 负责（codec 自己的 exceptionCaught 只兜上游的错），
     * 测试里用一个最小替身来验证 fail-closed。
     */
    private static final class FailClosedMonitor extends ChannelInboundHandlerAdapter {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private static PayloadCipherCodec clientCodec() throws Exception {
        return PayloadCipherCodec.forClient(PASSWORD, SALT);
    }

    private static PayloadCipherCodec serverCodec() throws Exception {
        PayloadCipherCodec codec = PayloadCipherCodec.forServer(PASSWORD);
        codec.initServerKeys(SALT);
        return codec;
    }

    /** 模拟「过线」：WS 帧解码器交付的是单段连续缓冲。 */
    private static ByteBuf rematerialize(ByteBuf record) {
        return Unpooled.wrappedBuffer(ByteBufUtil.getBytes(record));
    }

    @Test
    public void clientToServerRoundtripThroughPipelines() throws Exception {
        EmbeddedChannel clientCh = new EmbeddedChannel(clientCodec());
        EmbeddedChannel serverCh = new EmbeddedChannel(serverCodec());

        byte[] data = "c2s-over-tunnel".getBytes(StandardCharsets.UTF_8);
        ByteBuf plain = Unpooled.wrappedBuffer(data.clone());
        assertTrue(clientCh.writeOutbound(plain.retainedDuplicate()), "出站写入必须成功");
        ByteBuf record = clientCh.readOutbound();
        assertNotNull(record, "出站必须产出加密记录");

        // 注意：4.2 的 writeInbound 返回值只表示「是否有消息抵达 channel 尾部」，
        // 被 DownstreamCapture 消费掉就返回 false —— 与成败无关，不能拿来断言。
        DownstreamCapture capture = new DownstreamCapture();
        serverCh.pipeline().addLast(capture);
        serverCh.writeInbound(rematerialize(record));
        assertNotNull(capture.received, "下游必须收到解密后的明文");

        byte[] decoded = new byte[data.length];
        capture.received.readBytes(decoded);
        assertArrayEquals(data, decoded, "客户端→服务端必须逐字节还原");

        // 引用计数：下游那一份由测试释放；记录本体由出站管线释放
        capture.received.release();
        record.release();
        plain.release();
        clientCh.finishAndReleaseAll();
        serverCh.finishAndReleaseAll();
    }

    @Test
    public void serverToClientRoundtripThroughPipelines() throws Exception {
        EmbeddedChannel clientCh = new EmbeddedChannel(clientCodec());
        EmbeddedChannel serverCh = new EmbeddedChannel(serverCodec());

        byte[] data = "s2c-over-tunnel".getBytes(StandardCharsets.UTF_8);
        ByteBuf plain = Unpooled.wrappedBuffer(data.clone());
        assertTrue(serverCh.writeOutbound(plain.retainedDuplicate()));
        ByteBuf record = serverCh.readOutbound();

        DownstreamCapture capture = new DownstreamCapture();
        clientCh.pipeline().addLast(capture);
        clientCh.writeInbound(rematerialize(record));

        byte[] decoded = new byte[data.length];
        capture.received.readBytes(decoded);
        assertArrayEquals(data, decoded, "服务端→客户端必须逐字节还原");

        capture.received.release();
        record.release();
        plain.release();
        clientCh.finishAndReleaseAll();
        serverCh.finishAndReleaseAll();
    }

    /** 握手请求（FullHttpRequest）绝不能被当成载荷加密掉 —— 放行原对象。 */
    @Test
    public void nonByteBufOutboundMustPassThrough() throws Exception {
        EmbeddedChannel clientCh = new EmbeddedChannel(clientCodec());

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/websocket?token=x");
        assertTrue(clientCh.writeOutbound(request), "非 ByteBuf 出站必须放行");

        Object out = clientCh.readOutbound();
        assertSame(request, out, "FullHttpRequest 必须原样通过，不得封装/改写");
        assertEquals(1, request.refCnt(), "放行时引用计数必须保持 1");

        request.release();
        clientCh.finishAndReleaseAll();
    }

    /** 认证失败时：下游收不到任何数据，channel 关闭，入站消息由框架释放。 */
    @Test
    public void tamperedInboundMustFailClosed() throws Exception {
        EmbeddedChannel clientCh = new EmbeddedChannel(clientCodec());
        EmbeddedChannel serverCh = new EmbeddedChannel(serverCodec(), new FailClosedMonitor());

        ByteBuf plain = Unpooled.wrappedBuffer("secret".getBytes(StandardCharsets.UTF_8));
        assertTrue(clientCh.writeOutbound(plain.retainedDuplicate()));
        ByteBuf record = clientCh.readOutbound();

        byte[] bytes = ByteBufUtil.getBytes(record);
        bytes[6] ^= 0x01;
        DownstreamCapture capture = new DownstreamCapture();
        serverCh.pipeline().addLast(capture);

        // FailClosedMonitor 会捕获异常并关连接（复刻 relay 的行为），所以 writeInbound 不会抛；
        // 断言的 observable 结果是：下游没拿到数据 + channel 已关闭。
        serverCh.writeInbound(Unpooled.wrappedBuffer(bytes));
        assertNullCapture(capture);
        assertTrue(!serverCh.isActive(), "fail-closed：管线异常后连接必须关闭");

        record.release();
        plain.release();
        clientCh.finishAndReleaseAll();
        serverCh.finishAndReleaseAll();
    }

    /** 服务端握手前（未初始化）：收/发载荷一律失败 —— 保证不会出现半加密的明文隧道。 */
    @Test
    public void uninitializedServerCodecMustFailClosed() throws Exception {
        EmbeddedChannel serverCh = new EmbeddedChannel(
                PayloadCipherCodec.forServer(PASSWORD), new FailClosedMonitor());
        DownstreamCapture capture = new DownstreamCapture();
        serverCh.pipeline().addLast(capture);

        // 两个方向都试一遍：要么抛异常，要么（被 Monitor 关闭后）通道进入关闭流程，
        // observable 的结果是「下游永远拿不到数据 + channel 关闭」。
        try {
            serverCh.writeInbound(Unpooled.wrappedBuffer(new byte[64]));
        } catch (Throwable expected) {
            // EmbeddedChannel 4.2 可能直接把异常抛出
        }
        assertNullCapture(capture);
        assertTrue(!serverCh.isActive(), "未初始化时收到数据帧必须 fail-closed");

        try {
            serverCh.writeOutbound(Unpooled.wrappedBuffer(new byte[64]));
        } catch (Throwable expected) {
            // 出站方向同样不允许悄悄放行
        }

        serverCh.finishAndReleaseAll();
    }

    /** 两端 salt 不一致（含中间人替换）：认证失败，绝不能解出明文。 */
    @Test
    public void saltMismatchMustFail() throws Exception {
        EmbeddedChannel clientCh = new EmbeddedChannel(clientCodec());
        EmbeddedChannel serverCh = new EmbeddedChannel(
                PayloadCipherCodec.forClient(PASSWORD, "misssalt".getBytes(StandardCharsets.US_ASCII)),
                new FailClosedMonitor());

        ByteBuf plain = Unpooled.wrappedBuffer("secret".getBytes(StandardCharsets.UTF_8));
        assertTrue(clientCh.writeOutbound(plain.retainedDuplicate()));
        ByteBuf record = clientCh.readOutbound();

        byte[] bytes = ByteBufUtil.getBytes(record);
        DownstreamCapture capture = new DownstreamCapture();
        serverCh.pipeline().addLast(capture);
        serverCh.writeInbound(Unpooled.wrappedBuffer(bytes));
        assertNullCapture(capture);
        assertTrue(!serverCh.isActive(), "salt 不一致必须认证失败并关连接");

        record.release();
        plain.release();
        clientCh.finishAndReleaseAll();
        serverCh.finishAndReleaseAll();
    }

    /** 编解码器派生的方向密钥必须与 PayloadCipher 直接派生的一致（防止两套派生漂移）。 */
    @Test
    public void codecUsesPayloadCipherKeyDerivation() throws Exception {
        SecretKeySpec master = PskDeriver.getInstance(PASSWORD).masterKey();
        PayloadCipher manualTx = new PayloadCipher(
                PayloadCipher.newCipher(master, SALT, "c2s"), SALT);

        EmbeddedChannel serverCh = new EmbeddedChannel(serverCodec());
        ByteBuf plain = Unpooled.wrappedBuffer("interop".getBytes(StandardCharsets.UTF_8));
        ByteBuf record = manualTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);

        DownstreamCapture capture = new DownstreamCapture();
        serverCh.pipeline().addLast(capture);
        serverCh.writeInbound(rematerialize(record));
        assertNotNull(capture.received, "手工 seal 的记录必须能被管线里的 codec 解开");

        byte[] decoded = new byte[capture.received.readableBytes()];
        capture.received.readBytes(decoded);
        assertArrayEquals("interop".getBytes(StandardCharsets.UTF_8), decoded);

        capture.received.release();
        record.release();
        plain.release();
        serverCh.finishAndReleaseAll();
    }

    private static void assertNullCapture(DownstreamCapture capture) {
        if (capture.received != null) {
            throw new AssertionError("失败路径绝不能把明文交给下游");
        }
    }
}
