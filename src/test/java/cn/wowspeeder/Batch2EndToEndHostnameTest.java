package cn.wowspeeder;

import cn.wowspeeder.encryption.Base64Encrypt;
import cn.wowspeeder.sw.NettyTransport;
import cn.wowspeeder.sw.SWCommon;
import cn.wowspeeder.sw.SWServerTcpProxyHandler;
import cn.wowspeeder.websocket.HttpHandShakeRequestHandler;
import cn.wowspeeder.websocket.WebSocketByteBufCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.junit.AfterClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 端到端验证「目标地址是域名」这条路径。
 *
 * <p>这是第二批改动里风险最高的一处：服务端不再在 {@code HttpHandShakeRequestHandler} 里
 * 用 {@code new InetSocketAddress(host, port)} 同步解析，而是交给
 * {@code AsyncDnsAddressResolverGroup} 异步解析。如果 resolver 接得不对
 * （比如地址建成了 resolved、或者 Bootstrap 没挂 resolver），
 * {@code Bootstrap.doResolveAndConnect0} 会跳过解析、拿一个没有 IP 的地址去 connect，
 * 表现出来就是「目标永远连不上」。
 *
 * <p>所以这里用真实的 socket 把服务端整条 pipeline 搭起来，token 里塞的是
 * <b>主机名</b>（localhost）而不是 IP，验证握手后目标确实被连上、且双向数据都通。
 */
public class Batch2EndToEndHostnameTest {

    private static final String PASSWORD = "889900";
    private static final int TIMEOUT_SECONDS = 10;

    private static final EventLoopGroup BOSS = new NioEventLoopGroup(1);
    private static final EventLoopGroup WORKER = new NioEventLoopGroup(2);

    @AfterClass
    public static void tearDownClass() {
        BOSS.shutdownGracefully();
        WORKER.shutdownGracefully();
    }

    @Test
    public void hostnameTargetIsResolvedAndProxied() throws Exception {
        // 目标服务器：绑在 localhost 解析出来的地址上，保证服务端的域名解析结果与它一致
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
        }, "batch2-target");
        targetThread.setDaemon(true);
        targetThread.start();

        // 服务端：复刻 SWServer.startSingle 里挂的那条 pipeline
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
                                .addLast(new SWServerTcpProxyHandler());
                    }
                });

        Channel serverChannel = serverBootstrap.bind("127.0.0.1", 0).sync().channel();
        int serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();

        Socket client = null;
        try {
            client = new Socket("127.0.0.1", serverPort);
            client.setSoTimeout(TIMEOUT_SECONDS * 1000);

            // token 里放的是主机名 localhost，不是 127.0.0.1
            String token = Base64Encrypt.getInstance(PASSWORD)
                    .getEncString("localhost:" + targetPort);

            String handshake = "GET /websocket?token=" + token + " HTTP/1.1\r\n"
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
            assertTrue("握手必须成功，实际响应头：\n" + responseHead,
                    responseHead.startsWith("HTTP/1.1 101"));

            // 隧道通了以后发一帧业务数据，服务端应当把它写进「localhost:targetPort」
            out.write(binaryFrame("PING-OVER-WS".getBytes("UTF-8")));
            out.flush();

            assertTrue("目标服务器应当收到业务数据（说明域名被解析并连上了）",
                    targetGotData.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("PING-OVER-WS", receivedByTarget.get());

            // 目标回 WORLD，经隧道回到客户端
            assertEquals("WORLD", new String(readFrame(in), "UTF-8"));
        } finally {
            if (client != null) {
                client.close();
            }
            serverChannel.close();
            target.close();
        }
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

    /** 构造一个客户端发送用的、带掩码的二进制帧（载荷 < 126 字节，够用）。 */
    private static byte[] binaryFrame(byte[] payload) {
        byte[] mask = new byte[]{0x11, 0x22, 0x33, 0x44};
        byte[] frame = new byte[2 + 4 + payload.length];
        frame[0] = (byte) 0x82;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, frame, 2, 4);
        for (int i = 0; i < payload.length; i++) {
            frame[2 + 4 + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return frame;
    }

    /** 读一个服务端发来的、无掩码的二进制帧。 */
    private static byte[] readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            fail("还没收到数据帧连接就断了");
        }
        int opcode = b0 & 0x0F;
        assertEquals("期望二进制帧，实际 opcode=" + opcode, 0x2, opcode);
        int length = b1 & 0x7F;
        assertTrue("本用例只处理短帧", length < 126);
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
