package test;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.socksx.v5.*;

public class Socks5CommandRequestTest {
    public static void main(String[] args) {

        DefaultSocks5CommandRequest req = new DefaultSocks5CommandRequest(Socks5CommandType.CONNECT, Socks5AddressType.IPv4,"dd222", 8080);


    }
}
