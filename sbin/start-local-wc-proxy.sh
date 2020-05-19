#!/bin/bash

#启动websocket-proxy client服务
nohup java -Xms512m -Xmx512m -jar ./netty-websocket-proxy-1.3.0.jar -s -conf="conf/config-example-client.json" >/dev/null 2>&1 &
