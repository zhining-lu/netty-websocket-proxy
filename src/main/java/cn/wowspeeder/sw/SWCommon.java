package cn.wowspeeder.sw;

import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;

public class SWCommon {

    public static final AttributeKey<InetSocketAddress> REMOTE_DES = AttributeKey.valueOf("ssremotedes");
    public static final AttributeKey<Socks5CommandRequest> REMOTE_DES_SOCKS5 = AttributeKey.valueOf("socks5remotedes");
    public static final AttributeKey<String>  PASSWORD = AttributeKey.valueOf("password");

    public static final int TCP_PROXY_IDEL_TIME = 120;

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
