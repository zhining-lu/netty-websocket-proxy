# netty-websocket-proxy
A  implementation of Forward-proxy in Java base on netty4 framework.

# Features

- [x] TCP support
- [x] CDN support
- [x] SOCKS5 support


# Environment
* 源码与字节码目标为 Java 8；已改用 `java.util.Base64`，可在 JDK 8 / 11 / 17 / 21 下编译（JDK 21 下用 `javac --release 8` 验证通过）

# Install
1. download netty-websocket-proxy-x.x.x-bin.tar.gz
2. tar -xzvf netty-websocket-proxy-x.x.x-bin.tar.gz
3. run
#### as swserver
```
java -jar ./bin/netty-websocket-proxy-x.x.x.jar -s -conf="./conf/config-example-server.json"
```
#### as swclient
```
java -jar ./bin/netty-websocket-proxy-x.x.x.jar -c -conf="./conf/config-example-client.json"
```
  Note: You can also use the command under bin to start the service. After the service starts, you can use Google Chrome and install the SwitchyOmega plug-in to surf the Internet

# Config

Server side (`-s`) and client side (`-c`) share the same config format:

| 字段 | 说明 |
|---|---|
| `server` | 服务端(`-s`)是监听地址，客户端(`-c`)是要连接的远端地址 |
| `port_password` | key 是端口，value 是该端口的密码；两端必须一致 |
| `local_address` / `local_port` | 仅客户端使用，本地 SOCKS5 监听地址与起始端口 |
| `boss_threads` | 可选，缺省 `1`。只监听一个端口时多开无意义 |
| `worker_threads` | 可选，缺省 `0` = Netty 默认（`2 × CPU`）。代理场景建议按并发连接数显式指定 |
| `use_epoll` | 可选，缺省 `true`。Linux 上优先用 epoll；native 库不可用时**自动回退 NIO**，不会启动失败 |
| `so_reuseport` | 可选，缺省 `false`。仅在 epoll 生效，多进程绑同一端口做负载均衡时打开 |

> 要用上 epoll，需要把 `netty-transport-native-epoll` 的 `linux-x86_64` classifier 包放进 classpath，
> 否则 `Epoll.isAvailable()` 返回 false，程序照常以 NIO 启动（启动日志里会打印 `transport=nio`）。

### 关于域名解析

`server`（客户端侧）与目标地址（服务端侧）都支持填域名，解析是**异步**的、并且带 TTL 缓存
（成功 60s、失败 5s），不会阻塞 event loop。若遇到解析异常，先确认目标地址没有写错——
密码不对时服务端会在解密阶段就失败，不会走到解析。

**多端口时的本地端口分配规则**（客户端）：`port_password` 里的每个远端端口都需要一个独立的本地
SOCKS5 监听端口，因此按**远端端口升序**依次分配 `local_port`、`local_port + 1`、`local_port + 2` …

例如客户端配置 `local_port = 18080`、`port_password = {"80": "a", "443": "b"}`，启动后会得到：

```
SOCKS5 127.0.0.1:18080  ->  远端 80
SOCKS5 127.0.0.1:18081  ->  远端 443
```

**UDP 未实现**：项目只做 TCP 转发。客户端发起 SOCKS5 `UDP_ASSOCIATE` 时，本地端会返回
`COMMAND_UNSUPPORTED (0x07)` 并关闭连接，请让客户端回退到 TCP（历史上这里错误地返回 `SUCCESS`，
导致 UDP 数据被静默丢弃）。

# Build
1. import as maven project
2. maven package

## TODO
* [ ] performance optimization
* [ ] android client
## Data flow
![image](https://img-blog.csdnimg.cn/2020051017110683.png)
   Note: CDN is optional
