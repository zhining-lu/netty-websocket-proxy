package cn.wowspeeder.config;


import com.google.gson.annotations.SerializedName;

import java.util.Map;

public class Config {

	@SerializedName("server")
	private String server;

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

}
