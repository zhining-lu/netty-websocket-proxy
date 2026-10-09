package cn.wowspeeder;

import cn.wowspeeder.encryption.Base64Encrypt;
import cn.wowspeeder.websocket.WebSocketByteBufCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * P0 修复的回归测试。
 */
public class P0RegressionTest {

    /**
     * WebSocketByteBufCodec.decode 只允许 retain 一次。
     *
     * <p>注意：这不是「泄漏修复」，而是引用计数配平的一半。MessageToMessageDecoder 会 release
     * 一次入站 frame，而 DefaultWebSocketFrame 没有独立引用计数（release() == content.release()），
     * 所以 decode 里 retain 一次正好把这份引用交给下游 relay 消费。另一半在上游
     * WebSocketClientHandler：它作为 SimpleChannelInboundHandler 会自动 release，转发帧时必须
     * 自己 retain（见 WebSocketRefCountTest）。两边任何一边多 retain 一次，都会在链路末尾
     * 变成「多一次 release」并抛 IllegalReferenceCountException。
     */
    @Test
    public void localFrameHandlerRetainsExactlyOnce() {
        ByteBuf payload = Unpooled.buffer();
        payload.writeBytes(new byte[]{1, 2, 3, 4});

        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketByteBufCodec());
        channel.writeInbound(new BinaryWebSocketFrame(payload));

        ByteBuf out = channel.readInbound();
        assertNotNull("decode 应当产出一个 ByteBuf", out);
        assertEquals("decode 只允许 retain 一次", 1, out.refCnt());

        // 下游的 relay handler 只会 release 一次
        out.release();
        assertEquals("消费者 release 之后引用计数必须归零，否则就是泄漏", 0, out.refCnt());
    }

    @Test
    public void base64EncryptRoundTripPerPassword() throws Exception {
        Base64Encrypt encrypt = Base64Encrypt.getInstance("889900");
        String plain = "www.baidu.com:443";

        String token = encrypt.getEncString(plain);
        assertEquals(plain, encrypt.getDesString(token));

        // 同一密码必须复用同一实例（避免在每条连接的热路径上重建 DES 密钥）
        assertSame(encrypt, Base64Encrypt.getInstance("889900"));
        // DES/ECB + 固定密钥 => 同一明文产生同一密文，协议格式保持不变
        assertEquals(token, encrypt.getEncString(plain));
    }

    /**
     * 修复前 Base64Encrypt 是「一个静态实例 + setKey 改写内部 key」，
     * 多端口并发时会出现 A 端口用 B 端口密钥解密的情况。
     */
    @Test
    public void base64EncryptIsolatesDifferentPasswords() throws Exception {
        Base64Encrypt a = Base64Encrypt.getInstance("889900");
        Base64Encrypt b = Base64Encrypt.getInstance("889901");
        assertNotSame("不同密码必须对应不同实例", a, b);

        String plain = "www.baidu.com:443";
        String tokenB = b.getEncString(plain);
        try {
            assertNotEquals("不同密码的密文不应能互相解开", plain, a.getDesString(tokenB));
        } catch (Exception expected) {
            // 解密直接失败同样说明密码已隔离
        }
    }

    /**
     * 原实现用 sun.misc.BASE64Encoder，密文每 76 字符会插入换行，
     * 调用方不得不用 replaceAll("\r|\n", "") 兜底。改用 java.util.Base64 后不应再出现换行。
     */
    @Test
    public void tokenMustNotContainLineBreaks() throws Exception {
        Base64Encrypt encrypt = Base64Encrypt.getInstance("889900");
        String longHost = "a-very-long-sub-domain-name-for-testing.the.example.com:443";

        String token = encrypt.getEncString(longHost);
        assertTrue("用例要求密文长度超过 76 字符，实际 " + token.length(), token.length() > 76);
        assertFalse("密文中不应出现 CR/LF", token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0);
        assertEquals(longHost, encrypt.getDesString(token));
    }
}
