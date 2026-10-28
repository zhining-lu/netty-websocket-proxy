package cn.wowspeeder.encryption;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * {@link Hkdf} 的 RFC 5869 官方测试向量（SHA-256）。
 *
 * <p>用例 1：常规输入；用例 3：salt 与 info 均为空（依赖「缺省视为全 0 / 空」的 RFC 语义）。
 * 这两组向量一旦改动实现（比如换了 hash 截断方式）立刻会红，是协议兼容性的保险丝。
 */
public class HkdfTest {

    private static byte[] repeat(int b, int n) {
        byte[] a = new byte[n];
        java.util.Arrays.fill(a, (byte) b);
        return a;
    }

    private static byte[] seq(int from, int to) {
        byte[] a = new byte[to - from + 1];
        for (int i = 0; i < a.length; i++) {
            a[i] = (byte) (from + i);
        }
        return a;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    public void rfc5869TestCase1() {
        byte[] ikm = repeat(0x0b, 22);
        byte[] salt = seq(0x00, 0x0c);
        byte[] info = seq(0xf0, 0xf9);

        byte[] okm = Hkdf.deriveKey(ikm, salt, info, 42);

        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a"
                + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                + "34007208d5b887185865"), okm, "RFC 5869 Test Case 1 的 OKM 必须逐字节一致");
    }

    @Test
    public void rfc5869TestCase3EmptySaltAndInfo() {
        byte[] ikm = repeat(0x0b, 22);

        byte[] okm = Hkdf.deriveKey(ikm, new byte[0], new byte[0], 42);

        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31"
                + "b8a11f5c5ee1879ec3454e5f3c738d2d"
                + "9d201395faa4b61a96c8"), okm, "RFC 5869 Test Case 3 的 OKM 必须逐字节一致");
    }

    /** 长输出（多轮 expand）也要连续正确：一次派生 82 字节，前 42 字节应与用例 1 相同。 */
    @Test
    public void longOutputIsPrefixConsistent() {
        byte[] ikm = repeat(0x0b, 22);
        byte[] salt = seq(0x00, 0x0c);
        byte[] info = seq(0xf0, 0xf9);

        byte[] okm42 = Hkdf.deriveKey(ikm, salt, info, 42);
        byte[] okm82 = Hkdf.deriveKey(ikm, salt, info, 82);

        byte[] prefix = new byte[42];
        System.arraycopy(okm82, 0, prefix, 0, 42);
        assertArrayEquals(okm42, prefix, "expand 的前缀必须稳定（T(i) 链式派生的定义）");
    }
}
