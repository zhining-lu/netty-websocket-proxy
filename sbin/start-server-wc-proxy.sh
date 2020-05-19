#!/bin/bash

#启动websocket-proxy server服务
nohup java -Xms512m -Xmx512m -jar ./netty-websocket-proxy-1.3.0.jar -s -conf="conf/config-example-server.json" >/dev/null 2>&1 &
