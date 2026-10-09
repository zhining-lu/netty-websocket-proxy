package cn.wowspeeder.sw;

import io.netty.channel.Channel;

/**
 * 反向背压联动。
 *
 * <p><b>没有它会怎样：</b>代理的每一条数据通路都是「读 A → 写 B」。如果 B 写得比 A 读得慢
 * （对端拥塞、客户端在下大文件却只读不写……），数据就会一路堆在 B 的写缓冲里，
 * 直到 OOM —— Netty 的写缓冲默认没有上限。TCP 自己的滑动窗口只在「往 socket 写」这一层
 * 生效，挡不住 Netty 在用户态攒起来的队列。
 *
 * <p><b>做法：</b>把「下游写缓冲是否越过水位」映射成「上游要不要继续 read」。
 * 下游不可写（超过 {@code WRITE_BUFFER_WATER_MARK} 高水位）就 {@code setAutoRead(false)}
 * 掐断上游读取，让背压一路传回真正的数据源（浏览器 / 目标服务器），
 * 等下游重新可写（降到低水位以下）再打开。注意是<b>高低双水位</b>，
 * 不是为了单点抖动来回开关 —— 这个滞回由 {@code WriteBufferWaterMark} 提供。
 *
 * @see SWCommon#WRITE_BUFFER_WATER_MARK
 */
public final class BackPressure {

    private BackPressure() {
    }

    /**
     * 按下游的可写状态联动上游的 autoRead。
     *
     * <p>项目里两端 channel 都由同一个 event loop 驱动
     * （{@code proxyClient.group(clientChannel.eventLoop())}），所以正常路径就是当前线程；
     * 但仍然做了线程判断，避免以后改成多 event loop 时出现「非 event loop 线程改 ChannelConfig」
     * 的隐患。
     *
     * @param upstream   需要暂停/恢复读取的 channel（数据来源）
     * @param downstream 数据写入的目标 channel（判断它的写缓冲水位）
     */
    public static void link(final Channel upstream, final Channel downstream) {
        if (upstream == null || downstream == null) {
            return;
        }
        final boolean autoRead = downstream.isWritable();
        if (upstream.eventLoop().inEventLoop()) {
            upstream.config().setAutoRead(autoRead);
        } else {
            upstream.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    upstream.config().setAutoRead(autoRead);
                }
            });
        }
    }
}
