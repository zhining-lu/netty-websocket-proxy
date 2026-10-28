package cn.wowspeeder.websocket;

import cn.wowspeeder.encryption.PayloadCipher;
import cn.wowspeeder.encryption.PskDeriver;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * 隧道载荷加密编解码器：插在 {@link WebSocketByteBufCodec} 与转发 handler（客户端 relay /
 * 服务端 {@code SWServerTcpProxyHandler}）之间，正好卡在「ByteBuf 载荷 ↔ WebSocket 帧」的
 * 转换点内侧 —— 出站先密封再封帧，入站先拆帧再解封。
 *
 * <p>管线位置（必须保持，动了要么加密不到载荷、要么把握手报文也当成记录）：
 * <pre>
 * 客户端: HttpClientCodec → … → WebSocketClientHandler → WebSocketByteBufCodec → [这里] → relay
 * 服务端: HttpServerCodec → … → WebSocketByteBufCodec → [这里] → ssTcpProxy
 * </pre>
 *
 * <h2>引用计数（与 WebSocketByteBufCodec 同一套约定，改前先读 PayloadCipher 的注释）</h2>
 * <ul>
 *   <li>encode：{@code seal} 对明文 retain 一次交给 composite；本框架在 encode 返回后
 *       release 一次原始 msg，净效果是 composite 持有这一份引用。</li>
 *   <li>decode：{@code open} 返回的切片已 retain 一次；本框架在 decode 返回后 release
 *       输入的整条记录（切片与记录共享底层内存），净效果是下游拿到一份独立引用。</li>
 * </ul>
 *
 * <p>失败即断：seq 不对、认证失败、未初始化就收到数据帧，一律抛异常 →
 * {@link #exceptionCaught} 关连接（debug 级日志，per-connection 路径不刷 error）。
 * 绝不静默降级成明文，也绝不下发未认证的数据。
 *
 * <p>服务端实例在握手解析出 salt 前处于未初始化状态；握手处理器会调用 {@link #initServerKeys}。
 */
public class PayloadCipherCodec extends MessageToMessageCodec<ByteBuf, ByteBuf> {
	private static InternalLogger logger = InternalLoggerFactory.getInstance(PayloadCipherCodec.class);

	private final String password;
	private final boolean serverSide;
	private byte[] salt;
	private volatile PayloadCipher tx;
	private volatile PayloadCipher rx;

	private PayloadCipherCodec(String password, byte[] salt, boolean serverSide) {
		this.password = password;
		this.salt = salt;
		this.serverSide = serverSide;
	}

	/**
	 * 客户端：建隧道时随机生成 salt 并随握手 URL 送达服务端，两端密钥即刻就绪。
	 */
	public static PayloadCipherCodec forClient(String password, byte[] salt) throws GeneralSecurityException {
		PayloadCipherCodec codec = new PayloadCipherCodec(password, salt, false);
		codec.initKeys();
		return codec;
	}

	/**
	 * 服务端：握手还没来，salt 未知；等 {@link cn.wowspeeder.websocket.HttpHandShakeRequestHandler}
	 * 解析出 salt 后调 {@link #initServerKeys(byte[])}。在那之前收到/发送任何载荷都会失败断连。
	 */
	public static PayloadCipherCodec forServer(String password) {
		if (password == null) {
			throw new IllegalStateException("payload cipher enabled but PASSWORD attr is not set");
		}
		return new PayloadCipherCodec(password, null, true);
	}

	/** 服务端握手路径专用：salt 到位后派生双向密钥，每条连接只允许调用一次。 */
	public void initServerKeys(byte[] connSalt) throws GeneralSecurityException {
		if (!serverSide) {
			throw new IllegalStateException("client-side codec must not be initialized via initServerKeys");
		}
		if (tx != null) {
			throw new IllegalStateException("payload cipher already initialized");
		}
		this.salt = connSalt.clone();
		initKeys();
	}

	private void initKeys() throws GeneralSecurityException {
		SecretKeySpec master = PskDeriver.getInstance(password).masterKey();
		if (serverSide) {
			// 服务端：出站（→客户端）用 s2c，入站（客户端→）用 c2s
			this.tx = new PayloadCipher(newCipher(master, "s2c"), salt);
			this.rx = new PayloadCipher(newCipher(master, "c2s"), salt);
		} else {
			// 客户端：出站（→服务端）用 c2s，入站（服务端→）用 s2c
			this.tx = new PayloadCipher(newCipher(master, "c2s"), salt);
			this.rx = new PayloadCipher(newCipher(master, "s2c"), salt);
		}
	}

	private SecretKeySpec newCipher(SecretKeySpec master, String direction) {
		return PayloadCipher.newCipher(master, salt, direction);
	}

	@Override
	protected void encode(ChannelHandlerContext ctx, ByteBuf msg, java.util.List<Object> out)
			throws Exception {
		if (tx == null) {
			throw new GeneralSecurityException("payload cipher not initialized (handshake not completed?)");
		}
		out.add(tx.seal(ctx.alloc(), msg));
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf msg, java.util.List<Object> out)
			throws Exception {
		if (rx == null) {
			throw new GeneralSecurityException("payload cipher not initialized (no handshake?)");
		}
		out.add(rx.open(msg));
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		// per-connection 路径打 debug（与项目日志约定一致）：RST、篡改、密码不对都会走到这里
		logger.debug("payload cipher failed, closing tunnel: {}", cause.toString());
		ctx.close();
	}
}
