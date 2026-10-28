package cn.wowspeeder.encryption;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * HKDF-SHA256（RFC 5869）：extract + expand 两步，纯 JDK {@code Mac} 实现。
 *
 * <p>用途：隧道载荷加密（{@code PayloadCipher}）用「主密钥 + 每连接随机 salt + 方向标签」
 * 派生出上行 / 下行两把独立的 AES-256 密钥。展开只需两三次 HMAC，落在每连接一次的路径上，
 * 开销微秒级。
 *
 * <p>正确性由 {@code HkdfTest} 的 RFC 5869 官方测试向量（Test Case 1）钉死。
 */
public final class Hkdf {

	private static final int HASH_LEN = 32;

	private Hkdf() {
	}

	/**
	 * 派生 {@code length} 字节输出密钥。
	 *
	 * @param ikm   输入密钥材料（这里是端口密码的 PBKDF2 主密钥）
	 * @param salt  每连接随机盐；{@code null} 按 RFC 语义视为全 0
	 * @param info  方向/用途标签；允许 {@code null}（视为空）
	 * @param length 输出字节数，≤ 255 * 32
	 */
	public static byte[] deriveKey(byte[] ikm, byte[] salt, byte[] info, int length) {
		if (ikm == null || ikm.length == 0) {
			throw new IllegalArgumentException("ikm must not be empty");
		}
		if (length < 1 || length > 255 * HASH_LEN) {
			throw new IllegalArgumentException("invalid HKDF output length: " + length);
		}
		byte[] actualSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
		byte[] actualInfo = info != null ? info : new byte[0];

		// extract: PRK = HMAC-SHA256(salt, IKM)
		byte[] prk = hmac(actualSalt, ikm);

		// expand: T(i) = HMAC-SHA256(PRK, T(i-1) | info | i)
		byte[] okm = new byte[length];
		byte[] t = new byte[0];
		int offset = 0;
		for (byte counter = 1; offset < length; counter++) {
			Mac mac = newMac(prk);
			mac.update(t);
			mac.update(actualInfo);
			mac.update(counter);
			t = mac.doFinal();
			int n = Math.min(HASH_LEN, length - offset);
			System.arraycopy(t, 0, okm, offset, n);
			offset += n;
		}
		Arrays.fill(t, (byte) 0);
		Arrays.fill(prk, (byte) 0);
		return okm;
	}

	private static byte[] hmac(byte[] key, byte[] data) {
		Mac mac = newMac(key);
		return mac.doFinal(data);
	}

	private static Mac newMac(byte[] key) {
		Mac mac;
		try {
			mac = Mac.getInstance("HmacSHA256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("HmacSHA256 not available", e);
		}
		try {
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
		} catch (InvalidKeyException e) {
			throw new IllegalStateException("HmacSHA256 key rejected", e);
		}
		return mac;
	}
}
