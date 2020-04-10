package cn.wowspeeder.sw;

import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.util.AttributeKey;
import cn.wowspeeder.encryption.ICrypt;

import java.net.InetSocketAddress;

public class SWCommon {

    public static final AttributeKey<InetSocketAddress> RemoteAddr = AttributeKey.valueOf("ssclient");
    public static final AttributeKey<InetSocketAddress> REMOTE_DES = AttributeKey.valueOf("ssremotedes");
    public static final AttributeKey<Socks5CommandRequest> REMOTE_DES_SOCKS5 = AttributeKey.valueOf("socks5remotedes");
    public static final AttributeKey<String>  PASSWORD = AttributeKey.valueOf("password");

    public static final int TCP_PROXY_IDEL_TIME = 120;
}
