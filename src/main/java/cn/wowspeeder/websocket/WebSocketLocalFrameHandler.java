/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package cn.wowspeeder.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.List;

/**
 * Echoes uppercase content of text frames.
 */
public class WebSocketLocalFrameHandler extends MessageToMessageCodec<WebSocketFrame, ByteBuf> {

    private static InternalLogger logger = InternalLoggerFactory.getInstance(WebSocketLocalFrameHandler.class);

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        //transfer to WebSocketFrame

        out.add(new BinaryWebSocketFrame(msg).retain());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame msg, List<Object> out) throws Exception {
        //transfer to ByteBuf
        //MessageToMessageDecoder 会在 decode 返回后 release 一次入站 frame（DefaultWebSocketFrame 没有
        //独立引用计数，frame.release() 就是 content.release()），所以这里只 retain 一次，这一个引用
        //交给下游 relay 消费。上游 WebSocketClientHandler 转发帧时已经自行 retain，这里再多 retain
        //一次会让引用计数多 1，最终在下游释放后于 WebSocketClientHandler 的自动 release 处炸掉。
        out.add(msg.content().retain());

    }
}
