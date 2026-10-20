package cn.wowspeeder.sw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProxyStats} 的计数与格式回归测试。
 *
 * <p>所有断言都用「增量」而不是绝对值 —— 整个测试 JVM 共享同一份静态计数，
 * 别的用例可能已经改过它。
 */
public class ProxyStatsTest {

    @Test
    public void activeConnectionsArePairedByOpenClose() {
        long baseTunnel = ProxyStats.getActiveTunnels();
        ProxyStats.tunnelOpened();
        ProxyStats.tunnelOpened();
        assertEquals(baseTunnel + 2, ProxyStats.getActiveTunnels());
        ProxyStats.tunnelClosed();
        assertEquals(baseTunnel + 1, ProxyStats.getActiveTunnels());
        ProxyStats.tunnelClosed();
        assertEquals(baseTunnel, ProxyStats.getActiveTunnels());

        long baseSocks = ProxyStats.getActiveSocksConnections();
        ProxyStats.socksOpened();
        ProxyStats.socksClosed();
        assertEquals(baseSocks, ProxyStats.getActiveSocksConnections());

        long baseTarget = ProxyStats.getActiveTargetConnections();
        ProxyStats.targetOpened();
        ProxyStats.targetClosed();
        assertEquals(baseTarget, ProxyStats.getActiveTargetConnections());
    }

    @Test
    public void tunnelAttemptsResolveToSuccessOrFailure() {
        long success0 = ProxyStats.getTunnelSuccesses();
        long fail0 = ProxyStats.getTunnelFailures();

        ProxyStats.tunnelAttempt();
        ProxyStats.tunnelSucceeded();
        ProxyStats.tunnelAttempt();
        ProxyStats.tunnelFailed();

        assertEquals(success0 + 1, ProxyStats.getTunnelSuccesses());
        assertEquals(fail0 + 1, ProxyStats.getTunnelFailures());
    }

    @Test
    public void dnsCountersAreTracked() {
        long hit0 = ProxyStats.getDnsHits();
        long miss0 = ProxyStats.getDnsMisses();
        long literal0 = ProxyStats.getDnsIpLiterals();
        long reject0 = ProxyStats.getDnsRejects();

        ProxyStats.dnsHit();
        ProxyStats.dnsMiss();
        ProxyStats.dnsIpLiteral();
        ProxyStats.dnsReject();

        assertEquals(hit0 + 1, ProxyStats.getDnsHits());
        assertEquals(miss0 + 1, ProxyStats.getDnsMisses());
        assertEquals(literal0 + 1, ProxyStats.getDnsIpLiterals());
        assertEquals(reject0 + 1, ProxyStats.getDnsRejects());
    }

    @Test
    public void summaryHasAllSectionsAndNoNaN() {
        String s = ProxyStats.summary();
        assertTrue(s.contains("active["), s);
        assertTrue(s.contains("tunnelConnect["), s);
        assertTrue(s.contains("targetConnect["), s);
        assertTrue(s.contains("dns["), s);
        assertTrue(s.contains("hitRate="), s);
        // 分母为 0 时必须是 n/a，不能出现 NaN / Infinity
        assertFalse(s.contains("NaN"), s);
        assertFalse(s.contains("Infinity"), s);
    }
}
