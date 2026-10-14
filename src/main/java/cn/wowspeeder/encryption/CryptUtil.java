package cn.wowspeeder.encryption;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import io.netty.buffer.ByteBuf;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

public class CryptUtil {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(CryptUtil.class);

    public static byte[] encrypt(ICrypt crypt, Object msg) throws Exception {
        byte[] data = null;
        ByteArrayOutputStream _remoteOutStream = null;
        try {
            _remoteOutStream = new ByteArrayOutputStream(64 * 1024);
            ByteBuf bytebuff = (ByteBuf) msg;
            int len = bytebuff.readableBytes();
            byte[] arr = new byte[len];
            bytebuff.getBytes(0, arr);
            crypt.encrypt(arr, _remoteOutStream);
            data = _remoteOutStream.toByteArray();
        } finally {
            if (_remoteOutStream != null) {
                try {
                    _remoteOutStream.close();
                } catch (IOException e) {
                }
            }
        }
        return data;
    }

    public static byte[] decrypt(ICrypt crypt, Object msg) throws Exception {
        byte[] data = null;
        ByteArrayOutputStream _localOutStream = null;
        try {
            _localOutStream = new ByteArrayOutputStream(64 * 1024);
            ByteBuf bytebuff = (ByteBuf) msg;
            int len = bytebuff.readableBytes();
            byte[] arr = new byte[len];
            bytebuff.getBytes(0, arr);
            crypt.decrypt(arr, _localOutStream);
            data = _localOutStream.toByteArray();
        } finally {
            if (_localOutStream != null) {
                try {
                    _localOutStream.close();
                } catch (IOException e) {
                }
            }
        }
        return data;
    }

}
