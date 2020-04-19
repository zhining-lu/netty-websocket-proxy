#!/bin/bash

#启动websocket-proxy服务
nohup java -jar ./netty-websocket-proxy-1.3.0.jar -s -conf="conf/config-example-server.json" 2>&1 &
