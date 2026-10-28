package cn.wowspeeder.encryption;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 端口密码 → 32 字节主密钥（{@code PBKDF2-HMAC-SHA256}），按密码缓存。
 *
 * <p>从 {@link Base64Encrypt} 里抽出来的：token 加密（握手地址）与隧道载荷加密
 * （{@code PayloadCipher}）共用同一把主密钥做各自方向的派生，派生逻辑必须只有一份，
 * 否则将来改参数（迭代次数 / 盐）只会改到一处，两端派生出不同的 key 还很难查。
 *
 * <p>派生参数<b>绝对不能动</b>：盐与迭代次数变了，两端派生出的密钥就变了，
 * 旧 token / 旧客户端全部失效。这个算法被 {@code PskDeriverTest} 的金标值（KAT）钉死。
 */
public final class PskDeriver {

	/** AES-GCM 标准密钥长度 32 字节。 */
	private static final int KEY_BITS = 256;

	/**
	 * PBKDF2 迭代次数。端口密码通常很短，按 OWASP 2023 对 PBKDF2-HMAC-SHA256 的建议取 120000；
	 * 派生结果被缓存，每条连接只是查表。
	 */
	private static final int PBKDF2_ITERATIONS = 120000;

	/** 固定盐：同一密码在两端派生出同一把密钥。它公开可见，安全性由密码与迭代次数提供。 */
	private static final byte[] KDF_SALT =
			"netty-websocket-proxy/token-encryption/v2".getBytes(StandardCharsets.UTF_8);

	/**
	 * 按密码缓存派生结果。PBKDF2 一次约 290ms，绝不能落在连接热路径上；
	 * 启动预热见 {@code Config.warmUpTokenKeys()}。
	 */
	private static final ConcurrentMap<String, PskDeriver> INSTANCES = new ConcurrentHashMap<>();

	private final SecretKeySpec masterKey;

	public static PskDeriver getInstance(String password) throws GeneralSecurityException {
		// 先判空再查表：ConcurrentHashMap 不允许 null key，null 会先抛 NPE 而不是这里语义化的 IAE
		if (password == null) {
			throw new IllegalArgumentException("password must not be null");
		}
		PskDeriver cached = INSTANCES.get(password);
		if (cached != null) {
			return cached;
		}
		PskDeriver created = new PskDeriver(password);
		PskDeriver existing = INSTANCES.putIfAbsent(password, created);
		return existing != null ? existing : created;
	}

	private PskDeriver(String password) throws GeneralSecurityException {
		if (password == null) {
			throw new IllegalArgumentException("password must not be null");
		}
		this.masterKey = deriveKey(password);
	}

	/**
	 * 主密钥（{@code PBKDF2(password, KDF_SALT, 120000, 32B)}）。
	 * <p>
	 * 返回的是共享实例，调用方<b>只读、不得改写内容</b>；需要副本时自己 clone。
	 */
	public SecretKeySpec masterKey() {
		return masterKey;
	}

	/**
	 * 手写 PBKDF2-HMAC-SHA256，而不是 {@code SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")}：
	 * 后者在较老的 JDK 上并非所有厂商都提供；用 Mac 循环实现的成本很低，
	 * 且迭代次数与盐都是显式写死的，不依赖 Provider 的默认行为。
	 * <p>
	 * dkLen 正好等于 HMAC-SHA256 输出长度（32 字节），只需要一个 block（i = 1）。
	 */
	private static SecretKeySpec deriveKey(String password) throws GeneralSecurityException {
		Mac mac;
		try {
			mac = Mac.getInstance("HmacSHA256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("HmacSHA256 not available", e);
		}
		mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

		byte[] block = new byte[KDF_SALT.length + 4];
		System.arraycopy(KDF_SALT, 0, block, 0, KDF_SALT.length);
		block[KDF_SALT.length] = 0;
		block[KDF_SALT.length + 1] = 0;
		block[KDF_SALT.length + 2] = 0;
		block[KDF_SALT.length + 3] = 1;

		byte[] u = mac.doFinal(block);
		byte[] derived = u.clone();
		for (int i = 1; i < PBKDF2_ITERATIONS; i++) {
			u = mac.doFinal(u);
			for (int j = 0; j < derived.length; j++) {
				derived[j] ^= u[j];
			}
		}
		return new SecretKeySpec(derived, "AES");
	}
}
