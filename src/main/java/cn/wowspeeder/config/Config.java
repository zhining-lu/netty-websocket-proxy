package cn.wowspeeder.config;


import cn.wowspeeder.encryption.Base64Encrypt;
import com.google.gson.annotations.SerializedName;

import java.security.GeneralSecurityException;
import java.util.Map;

public class Config {

	@SerializedName("server")
	private String server;

	/**
	 * 隧道载荷加密，缺省 {@code aes-256-gcm}（每帧 AES-256-GCM 就地加解密，密钥由端口密码 + 每连接
	 * 随机 salt 派生）；{@code none} 关闭，用于灰度/回滚。
	 * <p>
	 * <b>两端必须一致</b>：协议里没有协商过程（握手 RTT 不能多），不一致时隧道建立后的第一帧
	 * 就会认证失败断连，日志表现为 payload cipher failed。
	 */
	@SerializedName("payload_cipher")
	private String payloadCipher;

	@SerializedName("port_password")
	private Map<Integer,String> portPassword;

	@SerializedName("local_address")
	private String localAddress;

	@SerializedName("local_port")
	private Integer localPort;

	/** boss 线程数，缺省 1（只有一个监听端口，多开无意义）。 */
	@SerializedName("boss_threads")
	private Integer bossThreads;

	/** worker 线程数，缺省 0 = 交给 Netty（2 * CPU）。代理场景建议按并发连接数显式指定。 */
	@SerializedName("worker_threads")
	private Integer workerThreads;

	/** 是否优先使用 epoll（Linux），缺省 true；native 库不可用时自动回退 NIO。 */
	@SerializedName("use_epoll")
	private Boolean useEpoll;

	/** 是否开启 SO_REUSEPORT（仅 epoll 生效），缺省 false。多进程绑同一端口做负载均衡时用。 */
	@SerializedName("so_reuseport")
	private Boolean soReuseport;

	/**
	 * 显式指定 SO_RCVBUF（字节），缺省 null = 不设置，交给内核 BDP 自动调优。
	 * <p>
	 * <b>非必要不要设</b>：一旦设置，内核会先把它截断到 {@code net.core.rmem_max}
	 * （默认 208KB）再翻倍，并且会关闭接收方向的自动调优。只有当你确实需要
	 * 限制内存占用（例如成千上万条并发连接的接入机）时才显式指定。
	 */
	@SerializedName("receive_buffer")
	private Integer receiveBuffer;

	/**
	 * 显式指定 SO_SNDBUF（字节），缺省 null = 不设置，交给内核 BDP 自动调优。
	 * <p>
	 * 语义与限制同 {@link #receiveBuffer}，对应 {@code net.core.wmem_max}。
	 */
	@SerializedName("send_buffer")
	private Integer sendBuffer;

	/**
	 * 周期性统计日志的间隔（秒），缺省 60；&le;0 表示关闭周期性统计。
	 * <p>
	 * 统计内容见 {@link cn.wowspeeder.sw.ProxyStats}：活跃连接数、隧道/目标建连成功率、DNS 缓存命中率。
	 */
	@SerializedName("stats_interval_seconds")
	private Integer statsIntervalSeconds;

	public String getServer() {
		return server;
	}

	public void setServer(String server) {
		this.server = server;
	}

	public Map<Integer,String> getPortPassword() {
		return portPassword;
	}

	public void setPortPassword(Map<Integer,String> portPassword) {
		this.portPassword = portPassword;
	}

	public String getPayloadCipher() {
		return payloadCipher == null ? "aes-256-gcm" : payloadCipher;
	}

	public void setPayloadCipher(String payloadCipher) {
		this.payloadCipher = payloadCipher;
	}

	/** 隧道载荷加密是否开启；仅识别 {@code none} 为关闭，其余值（含缺省）都按 aes-256-gcm 处理。 */
	public boolean isPayloadCipherEnabled() {
		return !"none".equalsIgnoreCase(getPayloadCipher());
	}

	public String getLocalAddress() {
		return localAddress;
	}

	public void setLocalAddress(String localAddress) {
		this.localAddress = localAddress;
	}

	public Integer getLocalPort() {
		return localPort;
	}

	public void setLocalPort(Integer localPort) {
		this.localPort = localPort;
	}

	public int getBossThreads() {
		return bossThreads == null ? 1 : bossThreads;
	}

	public void setBossThreads(Integer bossThreads) {
		this.bossThreads = bossThreads;
	}

	public int getWorkerThreads() {
		return workerThreads == null ? 0 : workerThreads;
	}

	public void setWorkerThreads(Integer workerThreads) {
		this.workerThreads = workerThreads;
	}

	public boolean isUseEpoll() {
		return useEpoll == null || useEpoll;
	}

	public void setUseEpoll(Boolean useEpoll) {
		this.useEpoll = useEpoll;
	}

	public boolean isSoReuseport() {
		return soReuseport != null && soReuseport;
	}

	public void setSoReuseport(Boolean soReuseport) {
		this.soReuseport = soReuseport;
	}

	/**
	 * @return 显式指定的接收缓冲区大小；null 或非正数表示不设置（走内核自动调优）。
	 */
	public Integer getReceiveBuffer() {
		return receiveBuffer == null || receiveBuffer <= 0 ? null : receiveBuffer;
	}

	public void setReceiveBuffer(Integer receiveBuffer) {
		this.receiveBuffer = receiveBuffer;
	}

	/**
	 * @return 显式指定的发送缓冲区大小；null 或非正数表示不设置（走内核自动调优）。
	 */
	public Integer getSendBuffer() {
		return sendBuffer == null || sendBuffer <= 0 ? null : sendBuffer;
	}

	public void setSendBuffer(Integer sendBuffer) {
		this.sendBuffer = sendBuffer;
	}

	/**
	 * 统计日志间隔秒数；缺省 60，&le;0 表示不打印周期性统计。
	 */
	public int getStatsIntervalSeconds() {
		return statsIntervalSeconds == null ? 60 : statsIntervalSeconds;
	}

	public void setStatsIntervalSeconds(Integer statsIntervalSeconds) {
		this.statsIntervalSeconds = statsIntervalSeconds;
	}

	/**
	 * 预派生 token 加密密钥。
	 * <p>
	 * token 用 AES-256-GCM 保护，密钥由端口密码经 PBKDF2 派生（12 万次迭代）。派生只需做一次
	 * （结果按密码缓存），但首次派生约 150ms；如果等到第一条连接才做，就会额外压在那条连接的
	 * 握手路径上。所以在启动阶段、线程池起来之前先热身一遍，把这份开销挪到启动耗时里。
	 * <p>
	 * 派生失败（JDK 缺少 AES-GCM）时抛异常直接启动失败，好过让每条连接在握手时才发现。
	 */
	public void warmUpTokenKeys() throws GeneralSecurityException {
		if (portPassword == null) {
			return;
		}
		for (String password : portPassword.values()) {
			Base64Encrypt.getInstance(password);
		}
	}

}
