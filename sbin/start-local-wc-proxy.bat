@echo off
start javaw -Xms512m -Xmx512m -jar netty-websocket-proxy-1.3.1.jar -c -conf="conf/config-example-client.json"
exit