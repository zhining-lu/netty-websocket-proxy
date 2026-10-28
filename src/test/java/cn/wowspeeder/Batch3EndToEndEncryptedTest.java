package cn.wowspeeder;

import cn.wowspeeder.encryption.PayloadCipher;
import cn.wowspeeder.encryption.PskDeriver;
import cn.wowspeeder.sw.NettyTransport;
import cn.wowspeeder.sw.SWCommon;
import cn.wowspeeder.sw.SWServerTcpProxyHandler;
import cn.wowspeeder.websocket.HttpHandShakeRequestHandler;
import cn.wowspeeder.websocket.PayloadCipherCodec;
import cn.wowspeeder.websocket.WebSocketByteBufCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 端到端验证「开启载荷加密」的完整链路：握手带 salt → 服务端在
 * {@link HttpHandShakeRequestHandler} 里初始化 {@link PayloadCipherCodec} →
 * 双向数据都经过密封/解封后仍逐字节正确。
 *
 * <p>服务端是真实 socket + 真实 pipeline（含掩码解码、帧编解码），客户端用
 * {@link PayloadCipher} 手工密封记录并按 RFC 6455 掩码组帧，模拟
 * {@code SWLocalTcpProxyHandler} 建立的隧道行为。
 */
public class Batch3EndToEndEncryptedTest {

    private static final String PASSWORD = "889900";
    private static final byte[] SALT = "saltsalt".getBytes(StandardCharsets.US_ASCII);
    private static final int TIMEOUT_SECONDS = 10;

    // Netty 4.2 起 NioEventLoopGroup 已 deprecated，改用 MultiThreadIoEventLoopGroup + NioIoHandler
    private static final EventLoopGroup BOSS = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private static final EventLoopGroup WORKER = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());

    @AfterAll
    public static void tearDownClass() {
        BOSS.shutdownGracefully();
        WORKER.shutdownGracefully();
    }

    @Test
    public void encryptedTunnelEndToEnd() throws Exception {
        SecretKeySpec master = PskDeriver.getInstance(PASSWORD).masterKey();
        // 客户端出站用 c2s 方向密钥，服务端入站也是 c2s —— 方向标签由 PayloadCipherCodec 统一管理
        PayloadCipher clientTx = new PayloadCipher(
                PayloadCipher.newCipher(master, SALT, "c2s"), SALT);
        PayloadCipher clientRx = new PayloadCipher(
                PayloadCipher.newCipher(master, SALT, "s2c"), SALT);

        // 目标服务器：读一条数据、回一条数据
        InetAddress loopback = InetAddress.getByName("localhost");
        final ServerSocket target = new ServerSocket(0, 50, loopback);
        target.setSoTimeout(TIMEOUT_SECONDS * 1000);
        final int targetPort = target.getLocalPort();

        final AtomicReference<String> receivedByTarget = new AtomicReference<String>();
        final CountDownLatch targetGotData = new CountDownLatch(1);

        Thread targetThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try (Socket socket = target.accept()) {
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[128];
                    int n = in.read(buffer);
                    if (n > 0) {
                        receivedByTarget.set(new String(buffer, 0, n, "UTF-8"));
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write("WORLD".getBytes("UTF-8"));
                    out.flush();
                    targetGotData.countDown();
                    Thread.sleep(500);
                } catch (Exception ignored) {
                    // 用例失败时由断言报出，这里不再重复抛
                }
            }
        }, "batch3-target");
        targetThread.setDaemon(true);
        targetThread.start();

        // 服务端：复刻 SWServer.startSingle 开启载荷加密后挂的那条 pipeline
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(BOSS, WORKER)
                .channel(NettyTransport.serverChannelClass())
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, SWCommon.WRITE_BUFFER_WATER_MARK)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.attr(SWCommon.PASSWORD).set(PASSWORD);
                        ch.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(65536 * 5))
                                .addLast(new HttpHandShakeRequestHandler())
                                .addLast(new WebSocketServerProtocolHandler("/websocket", null, true, 65536 * 5))
                                .addLast(new WebSocketByteBufCodec())
                                .addLast(PayloadCipherCodec.forServer(PASSWORD))
                                .addLast(new SWServerTcpProxyHandler());
                    }
                });

        Channel serverChannel = serverBootstrap.bind("127.0.0.1", 0).sync().channel();
        int serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();

        Socket client = null;
        try {
            client = new Socket("127.0.0.1", serverPort);
            client.setSoTimeout(TIMEOUT_SECONDS * 1000);

            String token = cn.wowspeeder.encryption.Base64Encrypt.getInstance(PASSWORD)
                    .getEncString("localhost:" + targetPort);
            String saltB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(SALT);

            String handshake = "GET /websocket?token=" + token + "&salt=" + saltB64 + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + serverPort + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            OutputStream out = client.getOutputStream();
            out.write(handshake.getBytes("ISO-8859-1"));
            out.flush();

            InputStream in = client.getInputStream();
            String responseHead = readHttpHead(in);
            assertTrue(responseHead.startsWith("HTTP/1.1 101"),
                    "握手必须成功，实际响应头：\n" + responseHead);

            // 隧道通了以后发一帧「密封过」的业务数据，服务端解开并写进 localhost:targetPort
            out.write(maskedFrame(seal(clientTx, "PING-OVER-WS".getBytes("UTF-8"))));
            out.flush();

            assertTrue(targetGotData.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "目标服务器应当收到解密后的业务数据");
            assertEquals("PING-OVER-WS", receivedByTarget.get(),
                    "目标收到的必须是解密后的明文");

            // 目标回 WORLD，服务端密封后经隧道回到客户端，客户端解开
            byte[] record = readFrame(in);
            assertEquals("WORLD", new String(open(clientRx, record), "UTF-8"));
        } finally {
            if (client != null) {
                client.close();
            }
            serverChannel.close();
            target.close();
        }
    }

    /** 用 {@link PayloadCipher} 密封一段明文，返回线上的记录字节（seq‖ct‖tag）。 */
    private static byte[] seal(PayloadCipher cipher, byte[] plain) throws Exception {
        ByteBuf payload = io.netty.buffer.Unpooled.wrappedBuffer(plain.clone());
        ByteBuf record = cipher.seal(UnpooledByteBufAllocator.DEFAULT, payload);
        byte[] bytes = ByteBufUtil.getBytes(record);
        record.release();
        payload.release();
        return bytes;
    }

    /** 用 {@link PayloadCipher} 解开一段线上记录字节，返回明文。 */
    private static byte[] open(PayloadCipher cipher, byte[] recordBytes) throws Exception {
        ByteBuf record = io.netty.buffer.Unpooled.wrappedBuffer(recordBytes);
        ByteBuf plain = cipher.open(record);
        byte[] bytes = ByteBufUtil.getBytes(plain);
        plain.release();
        record.release();
        return bytes;
    }

    /** 读到第一个空行为止，返回响应头文本。 */
    private static String readHttpHead(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                fail("握手响应还没读完连接就断了：\n" + head.toString("ISO-8859-1"));
            }
            head.write(b);
            switch (state) {
                case 0:
                    state = b == '\r' ? 1 : 0;
                    break;
                case 1:
                    state = b == '\n' ? 2 : 0;
                    break;
                case 2:
                    state = b == '\r' ? 3 : 0;
                    break;
                default:
                    state = b == '\n' ? 4 : 0;
                    break;
            }
        }
        return head.toString("ISO-8859-1");
    }

    /** 构造客户端发送用的、带掩码的二进制帧（支持 126~65535 的中等长度）。 */
    private static byte[] maskedFrame(byte[] payload) {
        byte[] mask = new byte[]{0x11, 0x22, 0x33, 0x44};
        int headerLen;
        byte[] header;
        if (payload.length <= 125) {
            headerLen = 2;
            header = new byte[]{(byte) 0x82, (byte) (0x80 | payload.length)};
        } else {
            headerLen = 4;
            header = new byte[]{(byte) 0x82, (byte) 0xFE,
                    (byte) (payload.length >>> 8), (byte) payload.length};
        }
        byte[] frame = new byte[headerLen + 4 + payload.length];
        System.arraycopy(header, 0, frame, 0, headerLen);
        System.arraycopy(mask, 0, frame, headerLen, 4);
        for (int i = 0; i < payload.length; i++) {
            frame[headerLen + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return frame;
    }

    /** 读一个服务端发来的、无掩码的二进制帧，返回载荷（= 一条加密记录）。 */
    private static byte[] readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            fail("还没收到数据帧连接就断了");
        }
        assertEquals(0x2, b0 & 0x0F, "期望二进制帧，实际 opcode=" + (b0 & 0x0F));
        int length = b1 & 0x7F;
        if ((b1 & 0x80) != 0) {
            fail("服务端不应发送掩码帧");
        }
        if (length == 126) {
            int hi = in.read();
            int lo = in.read();
            if (hi < 0 || lo < 0) {
                fail("长度还没读完连接就断了");
            }
            length = (hi << 8) | lo;
        }
        assertTrue(length >= PayloadCipher.HEADER_BYTES + PayloadCipher.TAG_BYTES,
                "载荷必须至少装得下一条加密记录，实际 " + length);
        byte[] payload = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(payload, read, length - read);
            if (n < 0) {
                fail("数据帧读到一半连接断了");
            }
            read += n;
        }
        return payload;
    }
}
