package cn.wowspeeder.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PayloadCipher} 的行为与引用计数测试。
 *
 * <p>「过线」用 {@code ByteBufUtil.getBytes(record) → wrappedBuffer} 模拟：记录在网上走一遭后，
 * WS 帧解码器（{@code ByteBufUtil.readBytes}）重新交付的就是这样一个<b>单段连续</b>缓冲，
 * 所以这里先拆成字节再包回来，与真实入站路径等价。
 */
public class PayloadCipherTest {

    private static final String PASSWORD = "889900";
    /** 固定盐让密文可复现，方便断言；线上用的是 PayloadCipher.newSalt() 的随机盐。 */
    private static final byte[] SALT = "saltsalt".getBytes(StandardCharsets.US_ASCII);

    private PayloadCipher clientTx; // 客户端出站（c2s）
    private PayloadCipher serverRx; // 服务端入站（c2s）
    private PayloadCipher serverTx; // 服务端出站（s2c）
    private PayloadCipher clientRx; // 客户端入站（s2c）

    @BeforeEach
    public void setUp() throws Exception {
        SecretKeySpec master = PskDeriver.getInstance(PASSWORD).masterKey();
        SecretKeySpec c2s = PayloadCipher.newCipher(master, SALT, "c2s");
        SecretKeySpec s2c = PayloadCipher.newCipher(master, SALT, "s2c");
        clientTx = new PayloadCipher(c2s, SALT);
        serverRx = new PayloadCipher(c2s, SALT);
        serverTx = new PayloadCipher(s2c, SALT);
        clientRx = new PayloadCipher(s2c, SALT);
    }

    @AfterEach
    public void tearDown() {
        // 各用例自己负责释放，这里不做全局操作
    }

    // ---------- 正常往返 ----------

    @Test
    public void roundtripHeapBuffer() throws Exception {
        byte[] data = "PING-OVER-WS".getBytes(StandardCharsets.UTF_8);
        ByteBuf plain = Unpooled.wrappedBuffer(data.clone());

        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);
        ByteBuf out = serverRx.open(rematerialize(record));

        byte[] decoded = new byte[data.length];
        out.readBytes(decoded);
        assertArrayEquals(data, decoded, "过线往返后必须还原出原始明文");

        releaseAll(plain, record, out);
    }

    @Test
    public void roundtripDirectBuffer() throws Exception {
        byte[] data = new byte[32 * 1024 + 17];
        new java.security.SecureRandom().nextBytes(data);
        ByteBuf plain = Unpooled.directBuffer(data.length).writeBytes(data);

        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);
        ByteBuf out = serverRx.open(rematerialize(record));

        byte[] decoded = new byte[data.length];
        out.readBytes(decoded);
        assertArrayEquals(data, decoded, "direct 缓冲上的就地加解密必须逐字节还原");

        releaseAll(plain, record, out);
    }

    /**
     * 紧凑 direct 载荷（capacity <b>恰好</b>等于明文长度、没有任何尾部可写空间）也必须就地加密。
     * <p>
     * 这是重写前唯一会拷载荷的形态：JDK 8 的 {@code CipherSpi.bufferCrypt} 要求每次 update 的 dst
     * 比入参多 16 字节，凑不出来就只能另分配 {@code len+16} 的缓冲。现在的实现把载荷自己的最后
     * 16 字节留给 {@code doFinal}，这 16 字节正好充当那段余量，于是两代 JDK 上都是同一个实例、
     * 同一块内存。本用例就是这条不变量的守卫——它在 JDK 8 和 JDK 21 上都必须通过。
     */
    @Test
    public void compactDirectPayloadIsAlwaysEncryptedInPlace() throws Exception {
        byte[] data = "compact-direct-payload".getBytes(StandardCharsets.UTF_8);
        // capacity == 明文长度：没有 16 字节余量
        ByteBuf plain = Unpooled.directBuffer(data.length).writeBytes(data);

        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);

        CompositeByteBuf composite = (CompositeByteBuf) record;
        assertEquals(3, composite.numComponents(), "记录 = seq头 + 原载荷 + tag");
        assertSame(plain, composite.internalComponent(1),
                "紧凑 direct 载荷也必须就地加密：载荷组件必须是原 ByteBuf 实例");
        assertEquals(data.length, composite.internalComponent(1).readableBytes(),
                "密文与明文等长");
        assertEquals(PayloadCipher.TAG_BYTES, composite.internalComponent(2).readableBytes(),
                "tag 必须是独立的 16 字节小缓冲");

        ByteBuf out = serverRx.open(rematerialize(record));
        byte[] decoded = new byte[data.length];
        out.readBytes(decoded);
        assertArrayEquals(data, decoded, "就地加解密必须完整还原明文");

        releaseAll(plain, record, out);
    }

    /**
     * 已知答案测试（KAT）：钉死同一份 (key, salt, seq, 明文) 产出的线上字节。
     * <p>
     * 真正的价值在<b>跨 JRE 互通</b>：客户端可能跑 JDK 8（或 Android/Conscrypt），服务端跑
     * JDK 21。两个 JRE 分别执行本用例都通过，即证明它们产出的记录逐字节相同；任何实现漂移
     * （字段顺序、AAD 内容、tag 位置、尾部补写逻辑）都会立刻失败。
     * <p>
     * 金标值在 JDK 8.0_391 与 JDK 21.0.11 上分别生成并核对一致。
     */
    @Test
    public void knownAnswerVectorIsStableAcrossJdks() throws Exception {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) i;
        }
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        byte[] salt = "SALTSALT".getBytes(StandardCharsets.US_ASCII);

        // 32 字节：16 的整数倍，update 一次写完
        assertKat(key, salt, "0123456789abcdef0123456789abcdef",
                "00000000384f8c3612ecfd1748805f93d5cd18285d9513931015b986836a99b3ae7fbbf"
                        + "2427a99ea838319b8d9e585f93f83962c");
        // 34 字节：尾部由 doFinal 返回再补写，覆盖「载荷长度非 16 倍数」的分支
        assertKat(key, salt, "netty-websocket-proxy KAT payload!",
                "00000000661bca715ff4bc4512ca5192ddcc09631dd64ed85d00c4f0ef7388b0b477b1f5"
                        + "ac72ffdaeccf7835cd1b1c2cf7dae8b77aa7");
    }

    private static void assertKat(SecretKeySpec key, byte[] salt, String text, String expectedHex)
            throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ByteBuf plain = Unpooled.wrappedBuffer(data.clone());
        ByteBuf record = new PayloadCipher(key, salt).seal(UnpooledByteBufAllocator.DEFAULT, plain);
        try {
            assertEquals(expectedHex, ByteBufUtil.hexDump(record),
                    "记录字节与金标不一致：线上格式或算法参数被改动，两端会互不兼容");
        } finally {
            record.release();
            plain.release();
        }
    }

    /** 单层 composite 输入（两个 heap 段）：走 nioBuffers() 分段 update 的防御路径。 */
    @Test
    public void roundtripMultiComponentPayload() throws Exception {
        byte[] part1 = "hello-".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "composite".getBytes(StandardCharsets.UTF_8);
        // 先留一份明文快照：seal 会就地改写 part1/part2，期望值必须在加密前拷出来
        byte[] snapshot1 = part1.clone();
        byte[] snapshot2 = part2.clone();
        CompositeByteBuf plain = Unpooled.compositeBuffer(2)
                .addComponent(true, Unpooled.wrappedBuffer(part1))
                .addComponent(true, Unpooled.wrappedBuffer(part2));

        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);
        ByteBuf out = serverRx.open(rematerialize(record));

        byte[] decoded = new byte[snapshot1.length + snapshot2.length];
        out.readBytes(decoded);
        byte[] expected = new byte[decoded.length];
        System.arraycopy(snapshot1, 0, expected, 0, snapshot1.length);
        System.arraycopy(snapshot2, 0, expected, snapshot1.length, snapshot2.length);
        assertArrayEquals(expected, decoded, "分段载荷也必须完整加解密");

        releaseAll(plain, record, out);
    }

    /**
     * 围绕 tag 长度（16 字节）的边界长度：0/1/15/16/17/31/32/33/4095/4096/4097。
     * <p>
     * 新实现把载荷最后 16 字节交给 {@code doFinal}（这 16 字节同时充当 JDK 8 要求的 dst 余量），
     * 因此 {@code len <= 16} 时整条走 doFinal，{@code len > 16} 时由 update 处理前 len-16 字节。
     * 而 update 又可能把不足一个分组的尾巴留在内部缓冲里（由 doFinal 一并吐出，补写位置是
     * {@code written} 而不是 {@code head}），所以这些长度必须逐个验证「密文等长 + 完整还原」。
     * <p>
     * 用紧凑 direct 缓冲（capacity == len）跑，等于同时要求每一条都就地完成。
     */
    @Test
    public void lengthsAroundTagBoundaryRoundtrip() throws Exception {
        int[] lengths = {0, 1, 15, 16, 17, 31, 32, 33, 4095, 4096, 4097};
        for (int n : lengths) {
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                data[i] = (byte) (i * 31 + 7);
            }
            ByteBuf plain = Unpooled.directBuffer(Math.max(n, 1), Math.max(n, 1));
            plain.writeBytes(data);

            ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);
            assertEquals(n + PayloadCipher.HEADER_BYTES + PayloadCipher.TAG_BYTES,
                    record.readableBytes(), "记录长度必须是 4 + n + 16，n=" + n);
            if (n > 0) {
                // 空载荷没有内容可拷，Netty 会把它作为 0 长度切片放进 composite，不必断言身份
                assertSame(plain, ((CompositeByteBuf) record).internalComponent(1),
                        "长度 " + n + " 的载荷也必须就地加密");
            }

            ByteBuf out = serverRx.open(rematerialize(record));
            byte[] decoded = new byte[n];
            out.readBytes(decoded);
            assertArrayEquals(data, decoded, "长度 " + n + " 的往返必须完整还原");

            releaseAll(plain, record, out);
        }
    }

    @Test
    public void emptyPayloadRoundtrip() throws Exception {
        ByteBuf plain = Unpooled.EMPTY_BUFFER;

        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);
        ByteBuf out = serverRx.open(rematerialize(record));

        assertEquals(0, out.readableBytes(), "空载荷的记录解出来还是空");

        // EMPTY_BUFFER 不能 release（unreleasable），只清记录与切片
        record.release();
        out.release();
    }

    /** 连续多条记录：seq 递增、密钥流连续，逐条都能解开。 */
    @Test
    public void multipleRecordsInSequence() throws Exception {
        for (int i = 0; i < 3; i++) {
            byte[] data = ("msg-" + i).getBytes(StandardCharsets.UTF_8);
            ByteBuf plain = Unpooled.wrappedBuffer(data.clone());
            ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);
            ByteBuf out = serverRx.open(rematerialize(record));

            byte[] decoded = new byte[data.length];
            out.readBytes(decoded);
            assertArrayEquals(data, decoded, "第 " + i + " 条记录必须能解开");

            releaseAll(plain, record, out);
        }
    }

    // ---------- 零拷贝断言 ----------

    /** 「不改变零拷贝」的可执行断言：密文必须写在原缓冲里，而不是拷出来的新 buffer。 */
    @Test
    public void sealMustEncryptInPlaceWithoutCopyingPayload() throws Exception {
        byte[] data = new byte[4096];
        ByteBuf plain = Unpooled.wrappedBuffer(data); // 包装外部数组，检验写的是不是同一块内存

        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);

        CompositeByteBuf composite = (CompositeByteBuf) record;
        assertEquals(3, composite.numComponents(), "记录 = seq头 + 原载荷 + tag");
        assertSame(plain, composite.internalComponent(1),
                "载荷组件必须是原 ByteBuf 实例（就地加密，零拷贝）");

        // 明文为全 0 时密文（GCM/CTR 键流异或）必不可能还是全 0 —— 证明确实改写了原内存
        byte[] after = new byte[data.length];
        plain.duplicate().readBytes(after);
        boolean allZero = true;
        for (byte b : after) {
            allZero &= b == 0;
        }
        assertFalse(allZero, "加密必须就地改写原缓冲");

        record.release();
        plain.release();
    }

    // ---------- 破坏性用例：全部必须失败，且绝不能吐出明文 ----------

    @Test
    public void tamperedCiphertextMustFail() throws Exception {
        ByteBuf plain = Unpooled.wrappedBuffer("secret-bytes".getBytes(StandardCharsets.UTF_8));
        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);

        byte[] bytes = ByteBufUtil.getBytes(record);
        bytes[8] ^= 0x01; // 密文区第一个字节
        assertThrows(GeneralSecurityException.class, () -> serverRx.open(Unpooled.wrappedBuffer(bytes)),
                "密文被篡改必须验签失败");

        record.release();
        plain.release();
    }

    @Test
    public void tamperedTagMustFail() throws Exception {
        ByteBuf plain = Unpooled.wrappedBuffer("secret-bytes".getBytes(StandardCharsets.UTF_8));
        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);

        byte[] bytes = ByteBufUtil.getBytes(record);
        bytes[bytes.length - 1] ^= 0x01; // tag 最后一个字节
        assertThrows(GeneralSecurityException.class, () -> serverRx.open(Unpooled.wrappedBuffer(bytes)),
                "tag 被篡改必须验签失败");

        record.release();
        plain.release();
    }

    /** 用错方向/错密码派生的密钥去解，必须失败（防反射、防串台）。 */
    @Test
    public void wrongDirectionKeyMustFail() throws Exception {
        ByteBuf plain = Unpooled.wrappedBuffer("secret-bytes".getBytes(StandardCharsets.UTF_8));
        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);

        // clientRx 是 s2c 方向的密钥，解 c2s 记录必须失败 —— 这正是反射攻击的形态
        assertThrows(GeneralSecurityException.class, () -> clientRx.open(rematerialize(record)),
                "用错方向密钥必须失败");

        record.release();
        plain.release();
    }

    @Test
    public void wrongPasswordMustFail() throws Exception {
        SecretKeySpec other = PskDeriver.getInstance("different-password").masterKey();
        PayloadCipher attacker = new PayloadCipher(
                PayloadCipher.newCipher(other, SALT, "c2s"), SALT);

        ByteBuf plain = Unpooled.wrappedBuffer("secret-bytes".getBytes(StandardCharsets.UTF_8));
        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);

        byte[] bytes = ByteBufUtil.getBytes(record);
        assertThrows(GeneralSecurityException.class, () -> attacker.open(Unpooled.wrappedBuffer(bytes)),
                "密码不对必须失败");

        record.release();
        plain.release();
    }

    @Test
    public void replayedOrSkippedSequenceMustFail() throws Exception {
        ByteBuf p1 = Unpooled.wrappedBuffer("first".getBytes(StandardCharsets.UTF_8));
        ByteBuf r1 = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, p1);
        ByteBuf o1 = serverRx.open(rematerialize(r1));
        o1.release();

        ByteBuf p2 = Unpooled.wrappedBuffer("second".getBytes(StandardCharsets.UTF_8));
        ByteBuf r2 = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, p2);

        // 重放第 1 条：seq 回退（期望 1，实际 0），必须被拒
        byte[] replay = ByteBufUtil.getBytes(r1);
        assertThrows(GeneralSecurityException.class, () -> serverRx.open(Unpooled.wrappedBuffer(replay)),
                "重放旧记录（seq 回退）必须被拒");

        // 跳过第 2 条直接用 seq=2：也必须被拒（低字节 +1 = seq 2）
        byte[] skipped = ByteBufUtil.getBytes(r2);
        skipped[3] += 1;
        assertThrows(GeneralSecurityException.class, () -> serverRx.open(Unpooled.wrappedBuffer(skipped)),
                "seq 跳号必须被拒");

        releaseAll(p1, r1, r2, p2);
    }

    @Test
    public void truncatedOrShortRecordMustFail() throws Exception {
        assertThrows(GeneralSecurityException.class, () -> serverRx.open(Unpooled.wrappedBuffer(new byte[10])),
                "过短记录必须在解析阶段就被拒");
        assertThrows(GeneralSecurityException.class, () -> serverRx.open(Unpooled.wrappedBuffer(new byte[0])),
                "空记录必须在解析阶段就被拒");
    }

    /** 非连续（composite）记录：按协议错误断连，绝不能悄悄合并拷贝。 */
    @Test
    public void nonContiguousRecordMustFail() throws Exception {
        ByteBuf plain = Unpooled.wrappedBuffer("secret-bytes".getBytes(StandardCharsets.UTF_8));
        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);
        byte[] bytes = ByteBufUtil.getBytes(record);

        CompositeByteBuf fake = Unpooled.compositeBuffer(2)
                .addComponent(true, Unpooled.wrappedBuffer(bytes, 0, 5))
                .addComponent(true, Unpooled.wrappedBuffer(bytes, 5, bytes.length - 5));

        assertThrows(GeneralSecurityException.class, () -> serverRx.open(fake),
                "多段缓冲的记录必须按协议错误拒绝");

        fake.release();
        record.release();
        plain.release();
    }

    /** 错误长度的盐必须在构造期就拒绝，而不是等到首帧才炸。 */
    @Test
    public void badSaltMustFailFast() throws Exception {
        SecretKeySpec master = PskDeriver.getInstance(PASSWORD).masterKey();
        assertThrows(IllegalArgumentException.class,
                () -> new PayloadCipher(master, new byte[4]));
        assertThrows(IllegalArgumentException.class,
                () -> new PayloadCipher(master, null));
        assertThrows(IllegalArgumentException.class,
                () -> PayloadCipher.newCipher(master, new byte[7], "c2s"));
    }

    // ---------- 引用计数 ----------

    /** seal retain 一次、open 的切片 retain 一次：各方释放后所有引用归零。 */
    @Test
    public void refCountsBalance() throws Exception {
        ByteBuf plain = Unpooled.wrappedBuffer("refcount".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, plain.refCnt());

        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);
        assertEquals(2, plain.refCnt(), "seal 应对载荷 retain 一次（交给 composite）");

        ByteBuf wire = rematerialize(record);
        ByteBuf out = serverRx.open(wire);
        // 切片没有独立引用计数：out.refCnt() 就是父缓冲（wire）的计数 —— retain 后为 2
        assertEquals(2, wire.refCnt(), "open 的切片应持有 wire 的一份引用");

        plain.release();
        record.release();
        out.release();
        wire.release();
        assertEquals(0, plain.refCnt(), "各方释放后载荷必须归零（无泄漏、无重复释放）");
    }

    /** open 失败时绝不能返回任何引用（下游拿不到半截明文）。 */
    @Test
    public void failedOpenMustNotLeakOrReturnPlain() throws Exception {
        ByteBuf plain = Unpooled.wrappedBuffer("secret-bytes".getBytes(StandardCharsets.UTF_8));
        ByteBuf record = clientTx.seal(UnpooledByteBufAllocator.DEFAULT, plain);
        byte[] bytes = ByteBufUtil.getBytes(record);
        bytes[8] ^= 0x01;

        try {
            serverRx.open(Unpooled.wrappedBuffer(bytes));
            throw new AssertionError("必须抛异常");
        } catch (GeneralSecurityException expected) {
            // open 内部在任何输出前就抛出，没有 retain 过的引用外泄
        }

        record.release();
        plain.release();
    }

    // ---------- 工具 ----------

    /** 模拟「过线」：记录序列化成字节后，由 WS 帧解码器重新交付的单段连续缓冲。 */
    private static ByteBuf rematerialize(ByteBuf record) {
        byte[] bytes = ByteBufUtil.getBytes(record);
        ByteBuf wire = Unpooled.wrappedBuffer(bytes);
        assertNotEquals(0, wire.nioBufferCount());
        return wire;
    }

    private static void releaseAll(ByteBuf... bufs) {
        // 释放顺序无所谓（引用计数互相独立），关键是每一方都要释放
        for (ByteBuf buf : bufs) {
            buf.release();
        }
    }
}
