package cn.wowspeeder.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * 隧道载荷加密：一条 WebSocket Binary 帧 = 一条 AES-256-GCM 记录。载荷<b>始终就地</b>加解密。
 *
 * <h2>线上格式</h2>
 * <pre>
 *   seq(4B) | ciphertext(nB) | tag(16B)
 * </pre>
 * <ul>
 *   <li>IV = {@code connSalt(8B) ‖ seq(4B)}，正好 12 字节 GCM IV；头部这 4 字节 seq 同时作为
 *       AAD 参与认证。{@code connSalt} 是客户端每条连接随机生成的 8 字节盐，经握手 URL 送达服务端，
 *       两端用它派生出<b>方向独立</b>的密钥（见 {@link #newCipher}），所以 IV 不需要在线上重复携带。</li>
 *   <li>接收端要求 seq 严格 +1：重放、乱序、把上行密文反射回下行，全部在认证/序号校验处失败。</li>
 *   <li>密文与明文等长（GCM 内部是 CTR 流），这是能就地改写的前提。</li>
 *   <li>记录是 {@code CompositeByteBuf} 三段拼装：{@code seq 头}、<b>原载荷 ByteBuf 本体</b>、
 *       {@code tag}。两端的 4B/16B 是独立小缓冲，载荷本体不参与任何拷贝。</li>
 * </ul>
 *
 * <h2>零拷贝怎么做到（关键设计，别改回去）</h2>
 * <p>JDK 8 的 {@code CipherSpi#bufferCrypt} 在<b>每次</b> {@code update} 前无条件检查
 * {@code output.remaining() >= engineGetOutputSize(inLen)}，而 GCM 加密的
 * {@code getOutputSize(inLen) == inLen + 16}（给 tag 留的保守估计）。紧凑的 direct 载荷
 * （capacity 恰好等于明文长度）凑不出这 16 字节余量，于是只能另分配一个 {@code len+16} 的缓冲
 * 把密文写过去——那就是唯一一次载荷拷贝。
 * <p>本类的做法是把<b>载荷自己的最后 16 字节</b>留出来，充当那段“尾余量”：
 * <ul>
 *   <li>前 {@code len-16} 字节走 {@code update(src, dst)}。dst 是同一个 ByteBuf 从载荷开头到
 *       <b>载荷末尾</b>的视图，因此 {@code remaining == len == (len-16) + 16}，预检恰好成立
 *       （JDK 8 用 {@code <} 判断，等号合法），而这条视图<b>没有越过载荷边界一个字节</b>。</li>
 *   <li>最后 16 字节走 {@code doFinal(byte[16])}，SunJCE 返回「16 字节密文 + 16 字节 tag」；
 *       密文写回载荷尾部，tag 直接当记录第三段。</li>
 * </ul>
 * <p>收益：不需要探测 JRE 版本/厂商实现（Java 8 / 9+ / Android Conscrypt 走同一条路径），
 * 不需要 {@code ensureWritable}（扩容即拷贝），载荷永远是传进来的那个 ByteBuf 实例。
 * 每记录只有末尾 16 字节经一次临时数组（{@code doFinal} 只接受 {@code byte[]} 入参，
 * tag 又必须由它产出），相对 32KB 帧是 0.05%。
 *
 * <h2>SunJCE 的使用约束（都用探针验证过，别“优化”掉）</h2>
 * <ul>
 *   <li>加密：{@code update(src, dst)} 必须传<b>两个独立视图</b>（{@code src} 与
 *       {@code src.duplicate()}）；传同一个对象会抛 {@code IllegalArgumentException}。</li>
 *   <li>{@code update} 会把不足一个分组的尾巴留在内部缓冲里，所以它返回的 {@code written}
 *       可能小于入参长度；那部分由 {@code doFinal} 一并吐出，写回位置必须是 {@code written}
 *       而不是 {@code head}。（32KB 对齐的载荷测不出这个坑：4080 % 16 == 0 时 update 一次写完。）</li>
 *   <li>解密：<b>绝不能走 {@code update}</b>——GCM 解密的 update 会把密文整块堆进内部 ibuffer
 *       （还额外拷一次）。正确姿势是把「密文+tag」当成一个连续的 src，dst 用同一块内存的
 *       duplicate 视图（limit 截到密文末尾），一次 {@code doFinal(src, dst)} 就地解密并验签。
 *       解密方向的 {@code getOutputSize(inLen) == inLen - 16}，所以它天然不需要尾余量，
 *       紧凑 direct 缓冲在两代 JDK 上都能就地。</li>
 *   <li>同 key 重复 IV 加密会被 {@code GaloisCounterMode.init} 拦下（"Cannot reuse iv for GCM
 *       encryption"），所以 seq 绝不能回绕复用：超过 2^32-1 直接抛异常断连。</li>
 * </ul>
 *
 * <h2>缓冲形态要求</h2>
 * <ul>
 *   <li>解密要求记录连续（{@code nioBufferCount() == 1}）。WS 帧解码器
 *       （{@code ByteBufUtil.readBytes}）产出的就是单个连续缓冲，天然满足；万一是多段，
 *       按协议错误断连，而不是悄悄合并拷贝。</li>
 *   <li>加密支持多段载荷，但 direct 段必须自带 16 字节尾部空间（跨段借不到邻段的内存）。
 *       生产路径上 WS 帧载荷与 TCP 读缓冲都是单段连续，这条只是防御性约束；不满足时抛
 *       {@link GeneralSecurityException}（fail-closed），绝不偷偷拷贝。</li>
 * </ul>
 *
 * <p>实例<b>不是线程安全</b>：每条 channel 每个方向各一份，且与业务 handler 同处一个 event loop，
 * 不要跨线程共享。
 */
public final class PayloadCipher {

	/** 每连接随机盐长度。8 字节随机 + 4 字节 seq 组成 12 字节 GCM IV。 */
	public static final int SALT_BYTES = 8;

	/** 记录头长度：只有 4 字节 seq（IV 的低 32 位），AAD 就是这 4 字节。 */
	public static final int HEADER_BYTES = 4;

	/** GCM 认证标签长度。 */
	public static final int TAG_BYTES = 16;

	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final long MAX_SEQ = 0xFFFFFFFFL;
	private static final String CIPHER = "AES/GCM/NoPadding";

	private static final SecureRandom RANDOM = new SecureRandom();

	/** 生成每连接随机盐（base64url 编码后放进握手 URL 的 salt 参数）。 */
	public static byte[] newSalt() {
		byte[] salt = new byte[SALT_BYTES];
		RANDOM.nextBytes(salt);
		return salt;
	}

	/**
	 * 用 HKDF 从主密钥派生指定方向的 AES-256 密钥。
	 *
	 * @param direction "c2s" / "s2c"：方向标签参与派生，两把密钥独立，防反射
	 */
	public static SecretKeySpec newCipher(SecretKeySpec masterKey, byte[] salt, String direction) {
		if (salt == null || salt.length != SALT_BYTES) {
			throw new IllegalArgumentException("conn salt must be " + SALT_BYTES + " bytes");
		}
		byte[] info = ("netty-websocket-proxy/payload/v1/" + direction)
				.getBytes(StandardCharsets.UTF_8);
		byte[] keyBytes = Hkdf.deriveKey(masterKey.getEncoded(), salt, info, 32);
		return new SecretKeySpec(keyBytes, "AES");
	}

	private final SecretKeySpec key;
	private final byte[] salt;
	private final Cipher cipher;
	/** 下一条记录的序号（从 0 开始）。收发两个方向各自独立计数。 */
	private long nextSeq;

	public PayloadCipher(SecretKeySpec key, byte[] salt) {
		if (salt == null || salt.length != SALT_BYTES) {
			throw new IllegalArgumentException("conn salt must be " + SALT_BYTES + " bytes");
		}
		this.key = key;
		this.salt = salt.clone();
		try {
			this.cipher = Cipher.getInstance(CIPHER);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(CIPHER + " not available", e);
		}
	}

	/**
	 * 加密一条记录。明文被就地改写成密文（同一块内存、同一个 ByteBuf 实例），返回
	 * {@code CompositeByteBuf(seq(4B) ‖ 密文(原缓冲) ‖ tag(16B))}。
	 *
	 * <p>引用计数：调用方（{@code MessageToMessageEncoder}）会在 encode 返回后 release 一次 msg，
	 * 所以这里对 {@code plain} retain 一次并交给 composite 持有。
	 */
	public ByteBuf seal(ByteBufAllocator alloc, ByteBuf plain) throws GeneralSecurityException {
		final int len = plain.readableBytes();
		final int idx = plain.readerIndex();
		final long seq = this.nextSeq;
		if (seq > MAX_SEQ) {
			// 2^32 条记录后 seq 回绕会复用 IV（同 key 同 IV = 灾难），宁可断连重开
			throw new GeneralSecurityException("payload record sequence overflow, reconnect required");
		}
		this.nextSeq = seq + 1;

		byte[] aad = seqBytes(seq);
		cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ivFor(seq)));
		cipher.updateAAD(aad);

		// 末尾 16 字节留给 doFinal：它既是 tag 的“产地”，也顺带充当 JDK 8 要求 update 的 dst
		// 多出来的那 16 字节余量（见类注释）。len <= 16 时不留，整条交给 doFinal。
		final int head = len > TAG_BYTES ? len - TAG_BYTES : 0;
		final int written = head > 0 ? encryptInPlace(plain, idx, head, len) : 0;

		byte[] tailIn = new byte[len - head];
		plain.getBytes(idx + head, tailIn);
		byte[] tailOut = cipher.doFinal(tailIn);
		// tailOut = 尚未吐出的密文（update 内部缓冲的尾巴 + 这 16 字节）+ tag
		int ctTail = tailOut.length - TAG_BYTES;
		if (ctTail > 0) {
			plain.setBytes(idx + written, tailOut, 0, ctTail);
		}

		// tag 直接引用 tailOut 的尾部 16 字节，不再另拷一份
		return alloc.compositeBuffer(3)
				.addComponent(true, Unpooled.wrappedBuffer(aad))
				.addComponent(true, plain.retain())
				.addComponent(true, Unpooled.wrappedBuffer(tailOut, ctTail, TAG_BYTES));
	}

	/**
	 * 解开一条记录。密文被就地改写成明文（同一块内存），返回持有这份内存的切片
	 * （{@code retain} 过一次，交给下游消费）。
	 *
	 * <p>seq 不匹配、记录过短或认证失败都抛 {@link GeneralSecurityException}，
	 * 由调用方关连接——不允许任何未认证的明文往下走。
	 */
	public ByteBuf open(ByteBuf record) throws GeneralSecurityException {
		int total = record.readableBytes();
		if (total < HEADER_BYTES + TAG_BYTES) {
			throw new GeneralSecurityException("record too short: " + total);
		}
		if (record.nioBufferCount() != 1) {
			// WS 帧解码器（ByteBufUtil.readBytes）产出的载荷一定是单个连续缓冲；
			// 真出现多段缓冲说明帧被拆过/拼过，按协议错误处理而不是悄悄合并拷贝。
			throw new GeneralSecurityException("record buffer must be contiguous, components="
					+ record.nioBufferCount());
		}
		long seq = this.nextSeq;
		if (seq > MAX_SEQ) {
			throw new GeneralSecurityException("payload record sequence overflow, reconnect required");
		}
		int rIdx = record.readerIndex();
		int seqInt = record.getInt(rIdx);
		if (seqInt != (int) seq) {
			throw new GeneralSecurityException("record sequence mismatch: got " + seqInt
					+ ", want " + (int) seq);
		}
		int ctLen = total - HEADER_BYTES - TAG_BYTES;

		cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ivFor(seq)));
		cipher.updateAAD(record.nioBuffer(rIdx, HEADER_BYTES));
		// src = [密文 ‖ tag] 连续视图；dst = 同一块内存、limit 截到密文末尾 → 就地解密 + 验签。
		// 一次 doFinal 完成：解密方向的 getOutputSize 是 inLen-16，不需要额外余量，JDK 8 也能就地。
		ByteBuffer src = record.nioBuffer(rIdx + HEADER_BYTES, ctLen + TAG_BYTES);
		ByteBuffer dst = src.duplicate();
		dst.limit(dst.position() + ctLen);
		cipher.doFinal(src, dst);
		this.nextSeq = seq + 1;

		return record.slice(rIdx + HEADER_BYTES, ctLen).retain();
	}

	/**
	 * 就地加密载荷的前 {@code head} 字节（密文写回同一块内存）。
	 *
	 * @param head  要交给 update 的字节数，恒等于 {@code total - TAG_BYTES}
	 * @param total 载荷总长度；dst 视图放宽到载荷末尾，正好提供 JDK 8 要求的 16 字节余量
	 * @return update 实际写出的密文字节数（可能小于 {@code head}，差额由 doFinal 吐出）
	 */
	private int encryptInPlace(ByteBuf buf, int offset, int head, int total)
			throws GeneralSecurityException {
		if (buf.nioBufferCount() == 1) {
			// src 只覆盖前 head 字节；dst 覆盖载荷全部 total 字节。
			// 于是 dst.remaining == total == head + 16，正好等于 JDK 8 要求的
			// engineGetOutputSize(head) == head + 16（它用 < 判断，等号合法），
			// 而 dst 的终点就是载荷末尾，没有借用载荷之外的任何内存。
			// 注意：Netty 的 nioBuffer(index, length) 返回的视图 capacity 就等于 length，
			// 所以不能拿 head 的视图去放宽 limit —— 必须重新取一条覆盖 total 的视图。
			//
			// 关键不变量：src 与 dst 必须从同一个 offset 开始（偏移差恒为 0），
			// 两条视图因此指向同一块底层内存且完全重叠。这是零拷贝的前提，也是安全的前提：
			// GCM 内部的 CTR 密钥流只由 key 与 IV 决定、与明文无关，所以「读 in[i] → 写
			// out[i]」不会破坏任何尚未参与计算的数据，就地覆盖成立。
			// 反例（探针实测，JDK 8 / 21 × SunJCE / BC 结论一致）：只把 dst 起点后移 1 字节，
			// 写 out[i] 就会压到还没读的 in[i+1] —— 64 字节载荷会静默产出错误密文且不抛异常，
			// 1448 字节则因走并行路径、输入先被内部缓冲接走而侥幸正确。也就是说错位不会报错，
			// 只会看长度随机出错，必须靠「共用同一个 offset 变量」在结构上杜绝，不能指望 provider 检查。
			//
			// 另：Cipher.update(in, out) 只禁止 in 与 out 是同一个对象
			// （SunJCE 抛 IllegalArgumentException），并不禁止底层内存重叠；
			// 这里能成立靠的是两份独立视图 + 偏移对齐，属于被测试锁定的实现行为，
			// 换 provider（如 Android Conscrypt）或换 JDK 大版本时必须复跑 KAT 金标。
			ByteBuffer src = buf.nioBuffer(offset, head);
			ByteBuffer dst = buf.nioBuffer(offset, total);
			return cipher.update(src, dst);
		}
		int written = 0;
		for (ByteBuffer part : buf.nioBuffers(offset, head)) {
			if (part.remaining() > 0) {
				written += encryptSegment(part);
			}
		}
		return written;
	}

	/**
	 * 加密多段载荷中的一段，密文写回该段自己的内存。
	 * <p>
	 * heap 段走 {@code byte[]} 版本：{@code in == out} 即真就地，而且绕开了
	 * {@code bufferCrypt} 的输出空间预检。direct 段只能走 ByteBuffer 版本，而 Netty 交给我们的
	 * 段视图 capacity 恰好等于段长（切片语义），凑不出那 16 字节余量——跨段更借不到邻段的内存。
	 * 这种形态只能 fail-closed，绝不偷偷分配新缓冲把载荷拷过去。
	 * <p>
	 * 生产路径上不会走到这里：WS 帧解码器与 TCP 读缓冲交付的都是单段连续 ByteBuf，
	 * 多段只可能来自上层人为拼接，本类的加密/解密两侧都对它保持“要么就地、要么报错”。
	 */
	private int encryptSegment(ByteBuffer part) throws GeneralSecurityException {
		if (part.hasArray()) {
			byte[] array = part.array();
			int base = part.arrayOffset() + part.position();
			int n = part.remaining();
			int written = cipher.update(array, base, n, array, base);
			part.position(part.limit());
			return written;
		}
		ByteBuffer dst = part.duplicate();
		if (dst.capacity() - dst.limit() < TAG_BYTES) {
			throw new GeneralSecurityException(
					"direct payload segment lacks " + TAG_BYTES + " bytes of trailing space, "
							+ "cannot encrypt in place (payload must be contiguous)");
		}
		dst.limit(dst.limit() + TAG_BYTES);
		return cipher.update(part, dst);
	}

	/** 记录头的 4 字节 seq（大端）。同一份字节既上线，也作为 AAD。 */
	private static byte[] seqBytes(long seq) {
		byte[] b = new byte[HEADER_BYTES];
		b[0] = (byte) (seq >>> 24);
		b[1] = (byte) (seq >>> 16);
		b[2] = (byte) (seq >>> 8);
		b[3] = (byte) seq;
		return b;
	}

	/** IV = salt(8B) ‖ seq 低 32 位（大端）。 */
	private byte[] ivFor(long seq) {
		byte[] iv = new byte[IV_BYTES];
		System.arraycopy(salt, 0, iv, 0, SALT_BYTES);
		iv[8] = (byte) (seq >>> 24);
		iv[9] = (byte) (seq >>> 16);
		iv[10] = (byte) (seq >>> 8);
		iv[11] = (byte) seq;
		return iv;
	}
}
