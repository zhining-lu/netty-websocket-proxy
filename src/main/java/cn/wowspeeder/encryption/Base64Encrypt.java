package cn.wowspeeder.encryption;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Base64Encrypt {

	/**
	 * 按密码缓存实例。实例一旦构造完成，key 就不再变化，因此可以安全地并发复用。
	 * 这里做缓存而不是每次 new，是因为 KeyGenerator/SecureRandom 的初始化位于每条代理连接的
	 * 热路径上，重复初始化的开销不可忽略。
	 *
	 * 原实现是「一个静态实例 + setKey 改写内部 key」，多端口并发时会出现 A 端口用 B 端口
	 * 密钥解密的情况（偶发 “may be password is not right”），这里一并修掉。
	 */
	private static final ConcurrentMap<String, Base64Encrypt> INSTANCES = new ConcurrentHashMap<>();

	private final Key key;

	public static Base64Encrypt getInstance(String key) throws Exception {
		Base64Encrypt cached = INSTANCES.get(key);
		if (cached != null) {
			return cached;
		}
		Base64Encrypt created = new Base64Encrypt(key);
		Base64Encrypt existing = INSTANCES.putIfAbsent(key, created);
		return existing != null ? existing : created;
	}

	/**
	 * 根据参数生成KEY
	 *
	 * @param strKey
	 */
	private Base64Encrypt(String strKey) throws Exception {
		SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
		secureRandom.setSeed(strKey.getBytes(StandardCharsets.UTF_8));
		KeyGenerator generator = KeyGenerator.getInstance("DES");
		generator.init(secureRandom);
		this.key = generator.generateKey();
	}

	/**
	 * 加密String明文输入,String密文输出
	 *
	 * @param strMing
	 * @return
	 */
	public String getEncString(String strMing) throws Exception {
		byte[] byteMi = this.getEncCode(strMing.getBytes(StandardCharsets.UTF_8));
		return Base64.getEncoder().encodeToString(byteMi);
	}

	/**
	 * 解密 以String密文输入,String明文输出
	 *
	 * @param strMi
	 * @return
	 */
	public String getDesString(String strMi) throws Exception {
		byte[] byteMing = this.getDesCode(Base64.getDecoder().decode(strMi));
		return new String(byteMing, StandardCharsets.UTF_8);
	}

	/**
	 * 加密以byte[]明文输入,byte[]密文输出
	 *
	 * @param byteS
	 * @return
	 * @throws Exception
	 */
	private byte[] getEncCode(byte[] byteS) throws Exception {
		Cipher cipher = Cipher.getInstance("DES");
		cipher.init(Cipher.ENCRYPT_MODE, key);
		return cipher.doFinal(byteS);
	}

	/**
	 * 解密以byte[]密文输入,以byte[]明文输出
	 *
	 * @param byteD
	 * @return
	 * @throws Exception
	 */
	private byte[] getDesCode(byte[] byteD) throws Exception {
		Cipher cipher = Cipher.getInstance("DES");
		cipher.init(Cipher.DECRYPT_MODE, key);
		return cipher.doFinal(byteD);
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
