package cn.wowspeeder.encryption;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PskDeriver} 的金标测试（KAT）。
 *
 * <p>下面两个十六进制期望值来自「改动前 Base64Encrypt 里的 PBKDF2 实现」（盐
 * {@code netty-websocket-proxy/token-encryption/v2}，120000 轮，32 字节），用于钉死派生算法：
 * 一旦有人动了盐/迭代次数/实现，token 与隧道载荷两端的密钥都会变，新旧对端全部失联 ——
 * 所以这里宁可“死”也不要灵活。
 */
public class PskDeriverTest {

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    public void derivedMasterKeyMustMatchLegacyAlgorithm() throws Exception {
        SecretKeySpec k1 = PskDeriver.getInstance("889900").masterKey();
        assertEquals("090d401b3b3f9e9ffae95d58aaee545a88d20dde2bfb1ea076ba08747d6dbca4",
                hex(k1.getEncoded()), "889900 的主密钥必须与历史实现一致（KAT）");

        SecretKeySpec k2 = PskDeriver.getInstance("test-password").masterKey();
        assertEquals("f6fe98728e3be11a6a44608ea97da6518f0b19914c13dc90852e79ffcd2bdef2",
                hex(k2.getEncoded()), "test-password 的主密钥必须与历史实现一致（KAT）");
    }

    /** 同密码必须拿到同一个缓存实例；不同密码绝不能串台（历史踩过 setKey 复用的坑）。 */
    @Test
    public void instancesAreCachedPerPassword() throws Exception {
        SecretKeySpec a = PskDeriver.getInstance("889900").masterKey();
        SecretKeySpec b = PskDeriver.getInstance("889901").masterKey();

        assertSame(PskDeriver.getInstance("889900"), PskDeriver.getInstance("889900"),
                "同密码返回同一缓存实例");
        assertArrayEquals(a.getEncoded(), PskDeriver.getInstance("889900").masterKey().getEncoded());
        // 889901 的密钥与 889900 必须不同
        org.junit.jupiter.api.Assertions.assertNotEquals(hex(a.getEncoded()), hex(b.getEncoded()));
    }

    /** null 密码必须立刻失败，而不是等到第一条连接才炸。 */
    @Test
    public void nullPasswordFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> PskDeriver.getInstance(null));
    }

    /** 密码字符集必须是 UTF-8：非 ASCII 密码在两端平台默认编码不同时会派生出不同密钥。 */
    @Test
    public void nonAsciiPasswordIsStable() throws Exception {
        String password = "密码-№42";
        byte[] first = PskDeriver.getInstance(password).masterKey().getEncoded();
        // 再取一次（缓存命中）必须逐字节相同；这里只验证稳定性，不验证值本身（值随算法钉死）
        byte[] again = PskDeriver.getInstance(password).masterKey().getEncoded();
        assertArrayEquals(first, again);
        assertEquals(new String(first, StandardCharsets.ISO_8859_1).length(), 32,
                "主密钥必须是 32 字节");
    }
}
