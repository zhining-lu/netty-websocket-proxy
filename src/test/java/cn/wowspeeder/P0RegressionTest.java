package cn.wowspeeder;

import cn.wowspeeder.encryption.Base64Encrypt;
import cn.wowspeeder.websocket.WebSocketByteBufCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertNotNull(out, "decode 应当产出一个 ByteBuf");
        assertEquals(1, out.refCnt(), "decode 只允许 retain 一次");

        // 下游的 relay handler 只会 release 一次
        out.release();
        assertEquals(0, out.refCnt(), "消费者 release 之后引用计数必须归零，否则就是泄漏");
    }

    @Test
    public void base64EncryptRoundTripPerPassword() throws Exception {
        Base64Encrypt encrypt = Base64Encrypt.getInstance("889900");
        String plain = "www.baidu.com:443";

        String token = encrypt.getEncString(plain);
        assertEquals(plain, encrypt.getDesString(token));

        // 同一密码必须复用同一实例（PBKDF2 的 12 万次迭代不应落在每条连接的热路径上）
        assertSame(encrypt, Base64Encrypt.getInstance("889900"));
        // 与旧 DES/ECB 实现的关键差别：每次加密都用随机 IV，同一明文得到不同密文
        assertNotEquals(token, encrypt.getEncString(plain), "每次加密应使用随机 IV，密文不应固定");
    }

    /**
     * 旧实现是 DES/ECB + 固定密钥，同一明文永远得到同一密文，攻击者录制一个 token 就能重放。
     * 改用 AES-GCM + 随机 IV 后，同一明文的密文必须每次都不同，但都能解回原文。
     */
    @Test
    public void tokenIsNotDeterministic() throws Exception {
        Base64Encrypt encrypt = Base64Encrypt.getInstance("889900");
        String plain = "www.baidu.com:443";

        String first = encrypt.getEncString(plain);
        String second = encrypt.getEncString(plain);
        assertNotEquals(first, second, "随机 IV 应让同一明文的密文不同");
        assertEquals(plain, encrypt.getDesString(first));
        assertEquals(plain, encrypt.getDesString(second));
    }

    /**
     * GCM 带认证：篡改密文必须被拒绝，而不是返回一段“能解开的错误明文”。
     */
    @Test
    public void tamperedTokenIsRejected() throws Exception {
        Base64Encrypt encrypt = Base64Encrypt.getInstance("889900");
        String token = encrypt.getEncString("www.baidu.com:443");

        char[] chars = token.toCharArray();
        // 翻转最后一个字符，落在 GCM tag 范围内
        chars[chars.length - 1] = chars[chars.length - 1] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThrows(Exception.class, () -> encrypt.getDesString(tampered),
                "篡改后的 token 必须解密失败");
    }

    /**
     * 修复前 Base64Encrypt 是「一个静态实例 + setKey 改写内部 key」，
     * 多端口并发时会出现 A 端口用 B 端口密钥解密的情况。
     */
    @Test
    public void base64EncryptIsolatesDifferentPasswords() throws Exception {
        Base64Encrypt a = Base64Encrypt.getInstance("889900");
        Base64Encrypt b = Base64Encrypt.getInstance("889901");
        assertNotSame(a, b, "不同密码必须对应不同实例");

        String plain = "www.baidu.com:443";
        String tokenB = b.getEncString(plain);
        try {
            assertNotEquals(plain, a.getDesString(tokenB), "不同密码的密文不应能互相解开");
        } catch (Exception expected) {
            // 解密直接失败同样说明密码已隔离
        }
    }

    /**
     * token 要直接拼进 {@code ?token=...}，不能含换行，也不能含普通 Base64 的 {@code + / =}
     * （{@code +} 在 query 里会被解码成空格）。
     */
    @Test
    public void tokenMustBeUrlSafeAndSingleLine() throws Exception {
        Base64Encrypt encrypt = Base64Encrypt.getInstance("889900");
        String longHost = "a-very-long-sub-domain-name-for-testing.the.example.com:443";

        String token = encrypt.getEncString(longHost);
        assertFalse(token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0, "密文中不应出现 CR/LF");
        assertFalse(token.indexOf('+') >= 0 || token.indexOf('/') >= 0 || token.indexOf('=') >= 0,
                "token 必须是 base64url（不含 + / =）：" + token);
        assertEquals(longHost, encrypt.getDesString(token));
    }
}
