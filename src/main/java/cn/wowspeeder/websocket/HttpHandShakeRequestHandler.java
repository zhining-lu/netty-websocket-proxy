package cn.wowspeeder.websocket;

import cn.wowspeeder.encryption.Base64Encrypt;
import cn.wowspeeder.sw.SWCommon;
import cn.wowspeeder.sw.SWServerTcpProxyHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpHandShakeRequestHandler extends ChannelInboundHandlerAdapter {
    private static InternalLogger logger = InternalLoggerFactory.getInstance(HttpHandShakeRequestHandler.class);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if(msg instanceof HttpRequest){
            String targetAddr = "";
            HttpRequest request = (HttpRequest)msg;
            String uri = request.uri();
            //
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                // No need to parse args
                ctx.fireChannelRead(msg);
                return;
            }
            // parse args to get targetHost and targetPort
            String[] s = uri.split("\\?");
            switch (s.length){
                case 1:
                    //
                    throw new UnsupportedOperationException("url args not right please check ");
                case 2:
                    //remove the part after ? and reset uri
                    // uri = /websocket?target=123.456.44.7:8080
                    request.setUri(s[0]);
                    String token = s[1];

                    Pattern tp = Pattern.compile("^token=(.*)");
                    Matcher tm = tp.matcher(token);
                    if (tm.matches()) {
                        targetAddr = tm.group(1);
                    }else {
                        logger.error("url args not right please check");
                        throw new UnsupportedOperationException("url args not right please check ");
                    }

                    String password = ctx.channel().attr(SWCommon.PASSWORD).get();
                    Base64Encrypt base64 = Base64Encrypt.getInstance(password);

                    String targetHostAndPort = base64.getDesString(targetAddr);
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
                    }else {
                        logger.error("may be password is not right");
                        throw new UnsupportedOperationException("may be password is not right");
                    }

                    break;
                default:
                    logger.warn("More than one ? in url: {}", uri);
                    throw new UnsupportedOperationException("url args not right please check ");
            }
        }
        //remove this pipline, bacause only one HttpReqest in In one websocket request process

        ctx.fireChannelRead(msg);
        ctx.pipeline().remove(this);
    }
}
