# websocket-proxy-netty
A  implementation of Websocket-proxy in Java base on netty4 framework.

# Features

- [x] TCP support
- [x] CDN support


# Environment
* JRE8

# Install
1. download netty-websocket-proxy-x.x.x-bin.zip
2. unzip netty-websocket-proxy-x.x.x-bin.zip
3. run
#### as ssserver
```
java -jar netty-websocket-proxy-x.x.x.jar -s -conf="conf/config-example-server.json"
```
#### as ssclient
```
java -jar netty-websocket-proxy-x.x.x.jar -c --conf="conf/config-example-client.json"
```

## Config file as python port
[Create configuration file and run](https://github.com/shadowsocks/shadowsocks/wiki/Configuration-via-Config-File)

# Build
1. import as maven project
2. maven package

## TODO
* [ ] performance optimization
* [ ] rate limit

