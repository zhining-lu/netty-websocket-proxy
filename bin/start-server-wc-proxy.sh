#!/bin/bash

#start websocket-proxy server
currpath=`pwd`
basename=`basename $currpath`
if [ "$basename" = "bin" ];then
cd ../
fi
homepath=`pwd`
log=$homepath/logs/sw-proxy.log
echo "starting server proxy program, logging to $log"
nohup java -Xms512m -Xmx512m -jar ./lib/netty-websocket-proxy-1.3.5.jar -s -conf="./conf/config-example-server.json" >/dev/null 2>&1 &
