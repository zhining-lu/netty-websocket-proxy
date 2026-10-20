package cn.wowspeeder.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回归测试：{@link WebSocketClientHandler} 不能吞掉 {@code channelActive}。
 *
 * <p>背景：客户端的 pipeline 是
 * {@code ... -> WebSocketClientHandler -> WebSocketByteBufCodec -> relay}。如果
 * {@code WebSocketClientHandler.channelActive} 不调用 {@code super.channelActive(ctx)}，
 * 排在它后面的 relay 就收不到 channelActive（却仍会收到 channelInactive），
 * 导致「活跃隧道」计数只减不增、变成负数 —— 线上日志里出现过
 * {@code active[socks=..., tunnel=-60, ...]} 就是这个原因。
 *
 * <p>顺带也保护了 relay 里「隧道连上时先对齐一次水位」的 {@code BackPressure.link}。
 */
public class WebSocketClientChannelActiveTest {

    @Test
    public void channelActivePropagatesPastClientHandler() throws Exception {
        URI uri = new URI("ws://127.0.0.1:8080/websocket?token=x");
        WebSocketClientHandler wsHandler = new WebSocketClientHandler(
                WebSocketClientHandshakerFactory.newHandshaker(
                        uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), 65536 * 5));

        final AtomicInteger activeCount = new AtomicInteger();
        final AtomicInteger inactiveCount = new AtomicInteger();
        ChannelInboundHandlerAdapter downstreamCounter = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                activeCount.incrementAndGet();
                ctx.fireChannelActive();
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                inactiveCount.incrementAndGet();
                ctx.fireChannelInactive();
            }
        };

        // EmbeddedChannel 构造完成即处于 active 状态，会向下游触发一次 channelActive。
        EmbeddedChannel channel = new EmbeddedChannel(wsHandler, downstreamCounter);

        assertEquals(1, activeCount.get(), "channelActive 必须越过 WebSocketClientHandler 传到底");

        channel.finishAndReleaseAll();
        assertEquals(1, inactiveCount.get(), "channelInactive 同样只应触发一次");
        assertEquals(activeCount.get(), inactiveCount.get(), "一次 active 必须严格对应一次 inactive");
    }
}
