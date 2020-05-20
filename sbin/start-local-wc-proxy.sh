#!/bin/bash

#start websocket-proxy client
nohup java -Xms512m -Xmx512m -jar ./netty-websocket-proxy-1.3.1.jar -c -conf="conf/config-example-client.json" >/dev/null 2>&1 &
