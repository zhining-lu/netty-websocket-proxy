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
     * @deprecated 已挪到 {@link SocketOptions#WRITE_BUFFER_WATER_MARK}，
     *             和 socket 缓冲区选项放在一起更便于对照。保留此常量仅为兼容旧引用。
     */
    @Deprecated
    public static final WriteBufferWaterMark WRITE_BUFFER_WATER_MARK =
            SocketOptions.WRITE_BUFFER_WATER_MARK;
}
