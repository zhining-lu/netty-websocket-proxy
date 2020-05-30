package cn.wowspeeder.websocket;

import cn.wowspeeder.encryption.Base64Encrypt;
import cn.wowspeeder.sw.SWCommon;
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
                        ctx.channel().attr(SWCommon.REMOTE_DES).set(new InetSocketAddress(host, port));
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
