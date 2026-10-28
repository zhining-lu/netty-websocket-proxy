package cn.wowspeeder.encryption;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 把「目标地址:端口」加密成一个可以放进 URL query 的 token，服务端用同一个端口密码还原。
 *
 * <h2>已废弃的实现（1.3.5 及以前）</h2>
 * <pre>
 *   SecureRandom.getInstance("SHA1PRNG") + setSeed(password) → KeyGenerator("DES") → Cipher("DES")
 * </pre>
 * 这里有四处硬伤，任意一处都足以让 token 形同明文：
 * <ol>
 *   <li><b>DES 本身已不可用</b>：56 位有效密钥，1999 年就被暴力破解（EFF Deep Crack，22 小时）；
 *       现在单机 GPU 是小时级。<b>DES-ECB 还属于 ECB 模式</b>，相同明文块产生相同密文块。</li>
 *   <li><b>用密码直接喂 SHA1PRNG 当密钥</b>：SHA1PRNG 的 {@code setSeed} 是「追加熵」语义而非
 *       「设定种子」，而 JDK 在无系统熵情况下会用低熵种子；不同 JDK 实现的派生结果还不一致，
 *       本身不是 KDF，更没有随机盐和工作因子。</li>
 *   <li><b>无 IV、无完整性校验</b>：同一密码在任何时间、任何连接上加密同一地址都得到同一密文，
 *       攻击者录制一个 token 就能反复重放；密文可被篡改而无法察觉。</li>
 *   <li>DES 在 JDK 21 仍可用，但从 JDK 19 起属于 {@code jdk.crypto.cryptoki} 之外默认禁用的
 *       弱算法集合，政策收紧后随时可能直接抛 {@code NoSuchAlgorithmException}。</li>
 * </ol>
 *
 * <h2>现在的实现</h2>
 * <b>AES-256-GCM</b>（JDK 自带 SunJCE，无额外依赖），密钥由 <b>PBKDF2-HMAC-SHA256</b> 派生
 * （派生逻辑在 {@link PskDeriver}，与隧道载荷加密共用同一把主密钥）：
 * <ul>
 *   <li>密钥：{@code PBKDF2(password, SALT, 120000 次, 32 字节)}；不加随机盐是为了让同一个端口密码
 *       在客户端与服务端派生出同一把密钥（这是单向约定，不是存储密码）；高迭代次数用来抬高
 *       离线爆破成本。</li>
 *   <li>每次加密随机生成 <b>12 字节 IV</b>，与密文、16 字节 GCM tag 一起 Base64 编码；
 *       因此同一明文每次的 token 都不同，重放和密文比对都失效。</li>
 *   <li>GCM 自带认证：token 被篡改、被截断或用错误密码解密，一律抛异常（不会返回半截明文）。</li>
 * </ul>
 *
 * <h2>兼容性</h2>
 * token 格式与算法都变了：<b>新旧版本不能混用</b>，客户端与服务端必须一起升级。
 */
public class Base64Encrypt {

	/** GCM 推荐 12 字节 IV；JDK 的 SunJCE 不接受其他长度。 */
	private static final int IV_BYTES = 12;

	/** GCM 认证标签长度（位）。 */
	private static final int TAG_BITS = 128;

	private static final String CIPHER = "AES/GCM/NoPadding";

	/** PBKDF2 只在实例构造时跑一次（约几十毫秒），之后每条连接只做 AES-GCM。 */
	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * 按密码缓存实例。实例一旦构造完成，key 就是不可变的，可安全并发复用。
	 * 做缓存而不是每次 new，是因为 PBKDF2 的 12 万次迭代落在每条代理连接的握手热路径上，
	 * 不缓存会把握手拖慢几十毫秒。
	 *
	 * 原实现是「一个静态实例 + setKey 改写内部 key」，多端口并发时会出现 A 端口用 B 端口
	 * 密钥解密的情况（偶发 “may be password is not right”），这里保持按密码隔离。
	 */
	private static final ConcurrentMap<String, Base64Encrypt> INSTANCES = new ConcurrentHashMap<>();

	private final SecretKeySpec key;

	public static Base64Encrypt getInstance(String password) throws GeneralSecurityException {
		Base64Encrypt cached = INSTANCES.get(password);
		if (cached != null) {
			return cached;
		}
		Base64Encrypt created = new Base64Encrypt(password);
		Base64Encrypt existing = INSTANCES.putIfAbsent(password, created);
		return existing != null ? existing : created;
	}

	private Base64Encrypt(String password) throws GeneralSecurityException {
		if (password == null) {
			throw new IllegalArgumentException("password must not be null");
		}
		this.key = PskDeriver.getInstance(password).masterKey();
	}

	/**
	 * 加密明文，返回 URL 安全的 Base64 token。
	 * <p>
	 * 输出布局：{@code Base64( IV(12) || ciphertext || tag(16) )}。
	 * 用 URL 安全的编码（{@code -} {@code _} 代替 {@code +} {@code /}，无填充）是因为
	 * token 要直接拼进 {@code ws://host:port/websocket?token=...}，普通的 Base64 里的
	 * {@code +} 在 query 中会被解码成空格。
	 */
	public String getEncString(String plain) throws GeneralSecurityException {
		byte[] iv = new byte[IV_BYTES];
		RANDOM.nextBytes(iv);

		Cipher cipher = newGcmCipher(Cipher.ENCRYPT_MODE, iv);
		byte[] cipherText;
		try {
			cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
		} catch (IllegalBlockSizeException | BadPaddingException e) {
			throw new GeneralSecurityException("token encrypt failed", e);
		}

		byte[] out = new byte[IV_BYTES + cipherText.length];
		System.arraycopy(iv, 0, out, 0, IV_BYTES);
		System.arraycopy(cipherText, 0, out, IV_BYTES, cipherText.length);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
	}

	/**
	 * 解密 token。密文被篡改、长度不对或密码不匹配时抛 {@link GeneralSecurityException}
	 * （GCM 认证失败），调用方据此判定「密码不对」。
	 */
	public String getDesString(String token) throws GeneralSecurityException {
		byte[] raw;
		try {
			raw = Base64.getUrlDecoder().decode(token);
		} catch (IllegalArgumentException e) {
			throw new GeneralSecurityException("token is not valid base64url", e);
		}
		// IV + 至少 1 字节明文 + 16 字节 tag
		if (raw.length <= IV_BYTES + TAG_BITS / 8) {
			throw new GeneralSecurityException("token too short: " + raw.length);
		}

		byte[] iv = Arrays.copyOfRange(raw, 0, IV_BYTES);
		byte[] cipherText = Arrays.copyOfRange(raw, IV_BYTES, raw.length);

		Cipher cipher = newGcmCipher(Cipher.DECRYPT_MODE, iv);
		byte[] plain;
		try {
			plain = cipher.doFinal(cipherText);
		} catch (IllegalBlockSizeException | BadPaddingException e) {
			// AEADBadTagException 是 BadPaddingException 的子类：认证标签校验失败
			throw new GeneralSecurityException("token decrypt failed (wrong password or tampered)", e);
		}
		return new String(plain, StandardCharsets.UTF_8);
	}

	private Cipher newGcmCipher(int mode, byte[] iv) throws GeneralSecurityException {
		Cipher cipher;
		try {
			cipher = Cipher.getInstance(CIPHER);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
			throw new IllegalStateException(CIPHER + " not available", e);
		}
		try {
			cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
		} catch (InvalidAlgorithmParameterException | InvalidKeyException e) {
			throw new GeneralSecurityException("AES-GCM init failed", e);
		}
		return cipher;
	}

	public static byte[] hex2byte(String strhex) {
		if (strhex == null) {
			return null;
		}
		int l = strhex.length();
		byte[] b = new byte[l / 2];
		for (int i = 0; i != l / 2; i++) {
			b[i] = (byte) Integer.parseInt(strhex.substring(i * 2, i * 2 + 2), 16);
		}

		return b;
	}
}
