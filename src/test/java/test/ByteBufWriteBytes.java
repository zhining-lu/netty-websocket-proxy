package test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ByteBufWriteBytes {
    public static void main(String[] args) {

        ByteBuf buf = Unpooled.wrappedBuffer("jjgjggg".getBytes(),"s".getBytes());
        byte[] bytes = "sdfsdfsjjjj9999999999999999j".getBytes();

        System.out.println("11111111");
        buf.retain().clear().writeBytes(bytes);
        System.out.println("222"+buf.capacity());

    }
}
