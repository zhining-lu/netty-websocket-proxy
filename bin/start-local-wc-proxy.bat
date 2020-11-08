@echo off
taskkill -f -t -im javaw.exe >nul 2>nul
cd ../
echo Client service is starting ...
echo.
start javaw -Xms512m -Xmx512m -jar ./lib/netty-websocket-proxy-1.3.4.jar -c -conf="./conf/config-example-client.json"
echo Client service is started.
echo.&echo The proxy program starts successfully, press any key to exit & pause >nul 2>nul
exit