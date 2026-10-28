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

import cn.wowspeeder.sw.SWCommon;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;

/**
 */
public class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {

	private static final String WEBSOCKET_PATH = "/websocket";

	private final SslContext sslCtx;

	/** 是否对隧道载荷加密（配置项 payload_cipher）。开关本身不进握手协商：两端必须一致，不一致时认证失败断连。 */
	private final boolean payloadCipherEnabled;

	public WebSocketServerInitializer(SslContext sslCtx, boolean payloadCipherEnabled) {
		this.sslCtx = sslCtx;
		this.payloadCipherEnabled = payloadCipherEnabled;
	}

	@Override
	public void initChannel(SocketChannel ch) throws Exception {
		ChannelPipeline pipeline = ch.pipeline();
		if (sslCtx != null) {
			pipeline.addLast(sslCtx.newHandler(ch.alloc()));
		}
		pipeline.addLast(new HttpServerCodec());
		pipeline.addLast(new HttpObjectAggregator(65536 * 5));
		pipeline.addLast(new HttpHandShakeRequestHandler());
		// 不再协商 permessage-deflate：隧道载荷多为 TLS/已压缩内容，压缩带来的 CPU 与一次内存拷贝
		// 收益为负；去掉后新旧对端仍可互通（对端 offer 压缩、本端不响应即协商降级）。
		pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, 65536 * 5));
		pipeline.addLast(new WebSocketIndexPageHandler(WEBSOCKET_PATH));
		pipeline.addLast(new WebSocketByteBufCodec());
		if (payloadCipherEnabled) {
			// 必须加在 WebSocketByteBufCodec 之后、ssTcpProxy 之前（SWServer 随后 addLast）：
			// 出站 ssTcpProxy → 这里密封 → WebSocketByteBufCodec 封帧；入站反向。
			// 密钥等服务端握手解析出 salt 后由 HttpHandShakeRequestHandler 注入（fail-closed）。
			pipeline.addLast(PayloadCipherCodec.forServer(ch.attr(SWCommon.PASSWORD).get()));
		}
	}
}
