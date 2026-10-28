package cn.wowspeeder.websocket;

import cn.wowspeeder.encryption.Base64Encrypt;
import cn.wowspeeder.encryption.PayloadCipher;
import cn.wowspeeder.sw.SWCommon;
import cn.wowspeeder.sw.SWServerTcpProxyHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpHandShakeRequestHandler extends ChannelInboundHandlerAdapter {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(HttpHandShakeRequestHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // handedOff 标记 msg 是否已经交给下游。
        // 本类继承的是 ChannelInboundHandlerAdapter，不像 SimpleChannelInboundHandler 那样在返回后
        // 自动 release；而经 HttpObjectAggregator 聚合出来的 FullHttpRequest 是带引用计数的，
        // 一旦解析过程中抛异常，msg 既不会被转发给下游、也没有人替我们释放 —— 这块 ByteBuf 就再也
        // 回不到池里（Netty 的泄漏检测会在 GC 时报
        // "LEAK: ByteBuf.release() was not called before it's garbage-collected"）。
        // 所以只要 msg 没交出去，就必须在 finally 里释放；异常本身照常向上抛出，行为与修改前一致。
        boolean handedOff = false;
        try {
            if (msg instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) msg;
                String uri = request.uri();
                //
                if ("/".equals(uri) || "/index.html".equals(uri)) {
                    // No need to parse args
                    handedOff = true;
                    ctx.fireChannelRead(msg);
                    return;
                }
                // parse args to get targetHost and targetPort
                resolveTarget(ctx, request, uri);
            }
            //remove this pipline, bacause only one HttpReqest in In one websocket request process
            handedOff = true;
            ctx.fireChannelRead(msg);
            ctx.pipeline().remove(this);
        } finally {
            if (!handedOff) {
                // 解析失败（token 不合法 / 密码不对 / 端口不是数字 ...）：msg 没交给下游，必须自己释放
                ReferenceCountUtil.release(msg);
            }
        }
    }

    /**
     * 解析 uri 上的 token 参数，还原出真正的目标地址并写入 channel 属性，同时提前向目标发起 TCP 连接。
     * <p>
     * 解析失败时直接抛异常，由调用方（{@link #channelRead}）负责释放 msg。
     */
    private void resolveTarget(ChannelHandlerContext ctx, HttpRequest request, String uri) throws Exception {
        String[] s = uri.split("\\?");
        switch (s.length) {
            case 1:
                //
                throw new UnsupportedOperationException("url args not right please check ");
            case 2:
                //remove the part after ? and reset uri
                // uri = /websocket?token=<base64url>&salt=<base64url(8B)>
                request.setUri(s[0]);

                // query 可能带多个参数（token 必有；开启载荷加密时还有 salt），逐个解析
                String token = null;
                String saltB64 = null;
                for (String kv : s[1].split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String name = kv.substring(0, eq);
                    String value = kv.substring(eq + 1);
                    if ("token".equals(name)) {
                        token = value;
                    } else if ("salt".equals(name)) {
                        saltB64 = value;
                    }
                }
                if (token == null) {
                    logger.error("url args not right please check");
                    throw new UnsupportedOperationException("url args not right please check ");
                }

                String password = ctx.channel().attr(SWCommon.PASSWORD).get();
                Base64Encrypt base64 = Base64Encrypt.getInstance(password);

                String targetHostAndPort;
                try {
                    targetHostAndPort = base64.getDesString(token);
                } catch (GeneralSecurityException e) {
                    // AES-GCM 认证失败：密码不对，或者 token 被截断/篡改。
                    // 故意不把具体原因回给客户端，避免成为密码猜测的提示。
                    logger.warn("token decrypt failed from {}: {}", ctx.channel().remoteAddress(), e.getMessage());
                    throw new UnsupportedOperationException("may be password is not right");
                }
                //eg: targetHostAndPort = www.baidu.com:443
                Pattern p = Pattern.compile("^\\s*(.*?):(\\d+)\\s*$");
                Matcher m = p.matcher(targetHostAndPort);
                if (m.matches()) {
                    String host = m.group(1);
                    int port = Integer.parseInt(m.group(2));
                    // 刻意用 createUnresolved：new InetSocketAddress(host, port) 会在构造时就
                    // 同步查 DNS，而这里跑在服务端 accept 后的 event loop 上 —— 一次慢解析
                    // 会把该 event loop 上的所有连接一起卡住。交给 AsyncDnsAddressResolverGroup
                    // 在独立线程池里异步解析（见 SWServerTcpProxyHandler 里 Bootstrap.resolver）。
                    ctx.channel().attr(SWCommon.REMOTE_DES).set(InetSocketAddress.createUnresolved(host, port));
                    logger.info("client {} target url: {}", ctx.channel().remoteAddress(), host + ":" + port);

                    // 目标地址一确定就立刻向目标发起 TCP 连接，与随后的 WebSocket 握手并行，
                    // 省掉原先必须等首份业务数据才建连的约 1 个 RTT。
                    // 连上之前到达的数据仍由 SWServerTcpProxyHandler 的 clientBuffs 缓存，不影响正确性。
                    SWServerTcpProxyHandler tcpProxyHandler = ctx.pipeline().get(SWServerTcpProxyHandler.class);
                    if (tcpProxyHandler != null) {
                        tcpProxyHandler.connectTargetOnHandshake(ctx.channel());
                    }

                    // 载荷加密：管线里挂了 PayloadCipherCodec 就说明本端口开了加密，
                    // 必须在握手阶段拿到 salt 并派生密钥（此后任何一帧都验签，fail-closed）。
                    // 不在管线里（payload_cipher=none）则忽略 salt，保持旧行为。
                    PayloadCipherCodec cipherCodec = ctx.pipeline().get(PayloadCipherCodec.class);
                    if (cipherCodec != null) {
                        byte[] salt = decodeSalt(saltB64);
                        cipherCodec.initServerKeys(salt);
                    }
                } else {
                    logger.error("may be password is not right");
                    throw new UnsupportedOperationException("may be password is not right");
                }

                break;
            default:
                logger.warn("More than one ? in url: {}", uri);
                throw new UnsupportedOperationException("url args not right please check ");
        }
    }

    /**
     * 解码 salt 参数：URL 安全 Base64、无填充、固定 8 字节（见 {@link PayloadCipher#SALT_BYTES}）。
     * 不合法一律按「url args not right」断连，不区分具体原因（不给探测者任何额外信息）。
     */
    private static byte[] decodeSalt(String saltB64) throws UnsupportedOperationException {
        if (saltB64 == null || saltB64.isEmpty()) {
            logger.error("url args not right please check: salt is required when payload cipher is on");
            throw new UnsupportedOperationException("url args not right please check ");
        }
        byte[] salt;
        try {
            salt = Base64.getUrlDecoder().decode(saltB64);
        } catch (IllegalArgumentException e) {
            logger.error("url args not right please check: salt is not base64url");
            throw new UnsupportedOperationException("url args not right please check ");
        }
        if (salt.length != PayloadCipher.SALT_BYTES) {
            logger.error("url args not right please check: bad salt length {}", salt.length);
            throw new UnsupportedOperationException("url args not right please check ");
        }
        return salt;
    }
}
