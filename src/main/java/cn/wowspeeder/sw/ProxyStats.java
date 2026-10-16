package cn.wowspeeder.sw;

import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行期统计指标，以及把这些指标周期性打进日志的辅助方法。
 *
 * <p>集中记录三类指标，方便线上排障（不必再靠翻大量 debug 日志推断）：
 * <ul>
 *   <li><b>活跃连接数</b>：SOCKS5 接入连接、WebSocket 隧道、服务端到目标的连接；</li>
 *   <li><b>建连成功率</b>：隧道建连（客户端{"->"}服务端）与目标建连（服务端{"->"}目标）；</li>
 *   <li><b>DNS 缓存命中率</b>：命中、未命中、IP 字面量直返、解析线程池拒绝次数。</li>
 * </ul>
 *
 * <p><b>计数约定：</b>
 * <ul>
 *   <li>活跃连接数是<b>当前值</b>，通过 channel 的 {@code channelActive}/{@code channelInactive}
 *       增减 —— Netty 保证这两个事件成对且各触发一次，所以不会漂移；</li>
 *   <li>其余是<b>进程级累计值</b>。客户端与服务端跑在同一个进程时（例如测试）会共用这份计数，
 *       属于预期行为，各端只会有自己那一侧的非零值。</li>
 * </ul>
 *
 * <p>成功率按「成功 /（成功 + 失败）」计算，而不是「成功 / 发起」——后者会把仍在建连中的
 * 请求算进分母，导致短时间窗口内成功率被低估。
 */
public final class ProxyStats {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ProxyStats.class);

    private ProxyStats() {
    }

    // ==================== 活跃连接数（当前值） ====================

    /** 当前活跃的 SOCKS5 接入连接（客户端浏览器侧）。 */
    private static final AtomicLong activeSocksConnections = new AtomicLong();
    /** 当前活跃的 WebSocket 隧道连接（客户端出站 / 服务端入站）。 */
    private static final AtomicLong activeTunnels = new AtomicLong();
    /** 当前活跃的「服务端 -> 目标」连接。 */
    private static final AtomicLong activeTargetConnections = new AtomicLong();

    // ==================== 建连次数（累计值） ====================

    /** WebSocket 隧道建连：发起 / 成功 / 失败。 */
    private static final AtomicLong tunnelAttempts = new AtomicLong();
    private static final AtomicLong tunnelSuccesses = new AtomicLong();
    private static final AtomicLong tunnelFailures = new AtomicLong();

    /** 「服务端 -> 目标」建连：发起 / 成功 / 失败。 */
    private static final AtomicLong targetAttempts = new AtomicLong();
    private static final AtomicLong targetSuccesses = new AtomicLong();
    private static final AtomicLong targetFailures = new AtomicLong();

    // ==================== DNS（累计值） ====================

    private static final AtomicLong dnsHits = new AtomicLong();
    private static final AtomicLong dnsMisses = new AtomicLong();
    private static final AtomicLong dnsIpLiterals = new AtomicLong();
    private static final AtomicLong dnsRejects = new AtomicLong();

    // ------------------------------------------------------------------
    // 活跃连接数
    // ------------------------------------------------------------------

    public static void socksOpened() {
        activeSocksConnections.incrementAndGet();
    }

    public static void socksClosed() {
        activeSocksConnections.decrementAndGet();
    }

    public static void tunnelOpened() {
        activeTunnels.incrementAndGet();
    }

    public static void tunnelClosed() {
        activeTunnels.decrementAndGet();
    }

    public static void targetOpened() {
        activeTargetConnections.incrementAndGet();
    }

    public static void targetClosed() {
        activeTargetConnections.decrementAndGet();
    }

    // ------------------------------------------------------------------
    // 建连次数
    // ------------------------------------------------------------------

    public static void tunnelAttempt() {
        tunnelAttempts.incrementAndGet();
    }

    public static void tunnelSucceeded() {
        tunnelSuccesses.incrementAndGet();
    }

    public static void tunnelFailed() {
        tunnelFailures.incrementAndGet();
    }

    public static void targetAttempt() {
        targetAttempts.incrementAndGet();
    }

    public static void targetSucceeded() {
        targetSuccesses.incrementAndGet();
    }

    public static void targetFailed() {
        targetFailures.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // DNS
    // ------------------------------------------------------------------

    public static void dnsHit() {
        dnsHits.incrementAndGet();
    }

    public static void dnsMiss() {
        dnsMisses.incrementAndGet();
    }

    public static void dnsIpLiteral() {
        dnsIpLiterals.incrementAndGet();
    }

    public static void dnsReject() {
        dnsRejects.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // 读取（供测试与排障）
    // ------------------------------------------------------------------

    public static long getActiveSocksConnections() {
        return activeSocksConnections.get();
    }

    public static long getActiveTunnels() {
        return activeTunnels.get();
    }

    public static long getActiveTargetConnections() {
        return activeTargetConnections.get();
    }

    public static long getTunnelAttempts() {
        return tunnelAttempts.get();
    }

    public static long getTunnelSuccesses() {
        return tunnelSuccesses.get();
    }

    public static long getTunnelFailures() {
        return tunnelFailures.get();
    }

    public static long getTargetAttempts() {
        return targetAttempts.get();
    }

    public static long getTargetSuccesses() {
        return targetSuccesses.get();
    }

    public static long getTargetFailures() {
        return targetFailures.get();
    }

    public static long getDnsHits() {
        return dnsHits.get();
    }

    public static long getDnsMisses() {
        return dnsMisses.get();
    }

    public static long getDnsIpLiterals() {
        return dnsIpLiterals.get();
    }

    public static long getDnsRejects() {
        return dnsRejects.get();
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    /**
     * 在当前线程打一条统计日志。只在 INFO 级别开启时才拼装字符串，避免无谓开销。
     */
    public static void logSummary() {
        if (logger.isInfoEnabled()) {
            logger.info("stats: {}", summary());
        }
    }

    /**
     * 把当前所有指标拼成一行，便于肉眼查看与日志检索。
     * <p>包级可见，供测试断言格式。
     */
    static String summary() {
        long tSuccess = tunnelSuccesses.get();
        long tFail = tunnelFailures.get();
        long gSuccess = targetSuccesses.get();
        long gFail = targetFailures.get();
        long hit = dnsHits.get();
        long miss = dnsMisses.get();

        return String.format(Locale.ROOT,
                "active[socks=%d, tunnel=%d, target=%d] "
                        + "tunnelConnect[attempt=%d, success=%d, fail=%d, rate=%s] "
                        + "targetConnect[attempt=%d, success=%d, fail=%d, rate=%s] "
                        + "dns[hit=%d, miss=%d, ipLiteral=%d, rejected=%d, hitRate=%s]",
                activeSocksConnections.get(), activeTunnels.get(), activeTargetConnections.get(),
                tunnelAttempts.get(), tSuccess, tFail, percent(tSuccess, tSuccess + tFail),
                targetAttempts.get(), gSuccess, gFail, percent(gSuccess, gSuccess + gFail),
                hit, miss, dnsIpLiterals.get(), dnsRejects.get(), percent(hit, hit + miss));
    }

    /**
     * @return 百分比字符串；分母为 0（样本不足）时返回 {@code n/a}，避免出现 NaN / Infinity
     */
    private static String percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f%%", numerator * 100.0 / denominator);
    }

    /**
     * 在给定的事件循环组上启动周期性统计日志。
     *
     * <p>刻意复用现有的 worker {@link EventExecutorGroup}，不额外起线程；日志本身是
     * Log4j2 异步写（见 log4j2.xml），所以即使跑在 event loop 上也不会阻塞网络。
     *
     * @param group           通常是 worker group；任务会随该组的 shutdown 一起取消
     * @param intervalSeconds 间隔秒数；&le;0 表示关闭周期性统计
     */
    public static void startReporter(final EventExecutorGroup group, long intervalSeconds) {
        if (intervalSeconds <= 0) {
            logger.info("periodic stats logging disabled (stats_interval_seconds={})", intervalSeconds);
            return;
        }
        group.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                logSummary();
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        logger.info("periodic stats logging enabled, every {}s", intervalSeconds);
    }
}
