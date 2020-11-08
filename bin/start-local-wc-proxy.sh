#!/bin/bash

#start websocket-proxy client
currpath=`pwd`
basename=`basename $currpath`
if [ "$basename" = "bin" ];then
cd ../
fi
nohup java -Xms512m -Xmx512m -jar ./lib/netty-websocket-proxy-1.3.3.jar -c -conf="./conf/config-example-client.json" >/dev/null 2>&1 &
