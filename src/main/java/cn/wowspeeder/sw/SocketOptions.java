package cn.wowspeeder.sw;

import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;

/**
 * 所有 channel 共用的 socket / 缓冲区选项。
 *
 * <p><b>关于 SO_RCVBUF / SO_SNDBUF：默认不设置。</b>
 *
 * <p>这不是遗漏，是刻意的。设这两个选项有两重副作用，对代理来说弊大于利：
 *
 * <ol>
 *   <li><b>写入的值会被内核先截断再翻倍。</b>Linux 的 {@code setsockopt} 先做
 *       {@code if (val > sysctl_wmem_max) val = sysctl_wmem_max;} 再
 *       {@code sk_sndbuf = val * 2;}。默认 {@code net.core.wmem_max} 只有 208KB，
 *       所以传入 2MB 会被砍到 208KB 后才翻倍 —— 实际生效值由系统参数决定，
 *       调用方根本控制不住（这也是原来的 {@code 2 * 1024 * 1024} 写了个寂寞的原因）。</li>
 *   <li><b>一旦设置就关闭内核自动调优。</b>该调用会给 socket 打上
 *       {@code SOCK_RCVBUF_LOCK} / {@code SOCK_SNDBUF_LOCK} 标记，此后内核不再按
 *       实测 BDP 自动增长缓冲区。链路的 BDP（带宽 × RTT）是未知的：百兆 80ms 只需约
 *       0.95MB，而千兆 150ms 需要约 36MB。写死一个值必然在高速链路上成为瓶颈，
 *       比不设置还慢。</li>
 * </ol>
 *
 * <p>因此默认交给内核自适应。确有需要（例如要限制单连接内存占用，或已知链路特征）
 * 时，用配置项显式打开，并注意：<b>至少要 256KB</b>（低于默认 rmem_max 会被截断），
 * 且要相应调大 {@code net.core.rmem_max} / {@code net.core.wmem_max}。
 *
 * @see SWCommon#WRITE_BUFFER_WATER_MARK
 */
public final class SocketOptions {

    private SocketOptions() {
    }

    /**
     * 内核在 setsockopt 时会把值翻倍（一半留给结构体的簿记开销），
     * 所以「想要 N 字节可用缓冲」需要填 {@code N / 2}。
     */
    public static final int KERNEL_BUFFER_MULTIPLIER = 2;

    /**
     * Linux 默认的 {@code net.core.rmem_max} / {@code wmem_max} 是 208KB。
     * 低于该值会被静默截断，填了也不生效，所以低于它就没有设置的意义。
     */
    public static final int KERNEL_DEFAULT_MAX = 208 * 1024;

    /** 推荐的最小显式值：至少要和内核默认上限同量级，否则不如不设。 */
    public static final int RECOMMENDED_MIN = 256 * 1024;

    /**
     * 读写缓冲是否已显式配置。
     *
     * <p>为 {@code false}（默认）时下面两个 getter 返回 0，调用方应<b>跳过</b>设置，
     * 让内核自动调优。这个「返回 0 表示不设」的约定集中在这里，
     * 避免四五个调用点各写一遍 if 判断。
     */
    private static volatile boolean explicit;

    private static volatile int receiveBuffer;
    private static volatile int sendBuffer;

    /**
     * 由启动流程调用一次。任一值为 null / &le;0 表示该项不设置。
     *
     * @param receive SO_RCVBUF 的值（字节），null 或非正数表示交给内核
     * @param send    SO_SNDBUF 的值（字节），null 或非正数表示交给内核
     */
    public static void configure(Integer receive, Integer send) {
        int r = (receive == null || receive <= 0) ? 0 : receive;
        int s = (send == null || send <= 0) ? 0 : send;
        receiveBuffer = r;
        sendBuffer = s;
        explicit = (r > 0 || s > 0);
    }

    /** 是否显式设置了至少一个 socket 缓冲区。 */
    public static boolean isExplicit() {
        return explicit;
    }

    /** SO_RCVBUF 的值；0 表示不设置（交给内核自动调优）。 */
    public static int receiveBuffer() {
        return receiveBuffer;
    }

    /** SO_SNDBUF 的值；0 表示不设置（交给内核自动调优）。 */
    public static int sendBuffer() {
        return sendBuffer;
    }

    /**
     * 给 {@code Bootstrap} 用：需要设置的选项就在下面一行，不需要就是 {@code null}，
     * 由调用点判断后跳过。
     */
    public static void applySend(io.netty.bootstrap.AbstractBootstrap<?, ?> bootstrap) {
        if (sendBuffer > 0) {
            bootstrap.option(ChannelOption.SO_SNDBUF, sendBuffer);
        }
        if (receiveBuffer > 0) {
            bootstrap.option(ChannelOption.SO_RCVBUF, receiveBuffer);
        }
    }

    /** 给 {@code ServerBootstrap} 的 childOption 用（作用于已接受的连接）。 */
    public static void applyChild(io.netty.bootstrap.ServerBootstrap bootstrap) {
        if (sendBuffer > 0) {
            bootstrap.childOption(ChannelOption.SO_SNDBUF, sendBuffer);
        }
        if (receiveBuffer > 0) {
            bootstrap.childOption(ChannelOption.SO_RCVBUF, receiveBuffer);
        }
    }

    /**
     * 写缓冲高低水位线，配合 {@link BackPressure} 做端到端背压。
     *
     * <p>Netty 默认是 32KB / 64KB，对代理来说太小：一次稍大的下载就会撞上高水位，
     * 触发「暂停读上游 → 排空 → 恢复」的来回抖动，反而伤吞吐。
     * 这里抬到 256KB / 1MB —— 高水位 1MB 意味着单条连接最多在用户态攒 1MB，
     * 1000 条并发连接的最坏内存占用约 1GB，是个可按机器内存调整的折中值。
     */
    public static final WriteBufferWaterMark WRITE_BUFFER_WATER_MARK =
            new WriteBufferWaterMark(256 * 1024, 1024 * 1024);
}
