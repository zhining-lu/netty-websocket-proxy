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
| `receive_buffer` / `send_buffer` | 可选，缺省**不设置**。即 `SO_RCVBUF` / `SO_SNDBUF`，单位字节。**非必要不要动**，理由见下一节 |

> 要用上 epoll，需要把 `netty-transport-native-epoll` 的 `linux-x86_64` classifier 包放进 classpath，
> 否则 `Epoll.isAvailable()` 返回 false，程序照常以 NIO 启动（启动日志里会打印 `transport=nio`）。

### 关于 `SO_RCVBUF` / `SO_SNDBUF`

**默认不设置，交给内核按实测 BDP 自动调优。** 这不是偷懒，而是刻意的：把这两个选项写死会带来两重副作用。

**1. 写入的值会被内核先截断、再翻倍，调用方根本控制不住。**
Linux 的 `setsockopt` 大致是这样做的：

```c
if (val > sysctl_wmem_max) val = sysctl_wmem_max;   /* 先截断 */
sk_sndbuf = val * 2;                                /* 再翻倍，一半留给簿记开销 */
```

`net.core.rmem_max` / `wmem_max` 的**默认值只有 208KB**，所以传 `2 * 1024 * 1024` 会先被砍到 208KB、
再翻成 416KB —— 你写的 2MB 和最终生效值毫无关系。也就是说，原来代码里那几行 `2MB` 是**写了个寂寞**。

**2. 一旦设置就关闭内核自动调优。**
该调用会给 socket 打上 `SOCK_RCVBUF_LOCK` / `SOCK_SNDBUF_LOCK` 标记，此后内核不再按实测 BDP 自动增长缓冲区。
而链路的 BDP（带宽 × RTT）是未知的：

| 带宽 | RTT | 所需缓冲(BDP) |
|---|---|---|
| 100 Mbps | 20 ms | ~0.24 MB |
| 100 Mbps | 80 ms | ~0.95 MB |
| 500 Mbps | 80 ms | ~4.8 MB |
| 1 Gbps | 150 ms | ~18 MB |
| 10 Gbps | 150 ms | ~180 MB |

写死 2MB 只够百兆级 + 中等 RTT，在千兆/万兆链路上必然成为吞吐瓶颈 —— **比不设置还慢**。

**只有以下情况才值得显式指定**，并且要注意两条约束：

* 需要限制单连接内存占用（例如几万条并发连接的接入机）；
* 已经清楚链路特征，要固定窗口做压测对比。

约束一：**至少要 256KB**，低于内核默认上限（208KB）的值会被静默截断。
约束二：改之前先调大 sysctl，否则填大值也不生效：

```bash
sysctl -w net.core.rmem_max=16777216
sysctl -w net.core.wmem_max=16777216
# 持久化写进 /etc/sysctl.conf
```

配置示例（不想要就写 `0` 或直接删掉这两个 key，效果一样）：

```json
{
  "receive_buffer": 524288,
  "send_buffer": 524288
}
```

> 补充：这两个值只会被设在**已接受的连接**（`childOption`）和**出站连接**上。
> 父 channel（监听 socket）上的同名字段对连接毫无作用 —— 历史上确实有一处
> `.option(ChannelOption.SO_RCVBUF, ...)` 写在了父 channel 上，属于无效代码，已删除。

### 关于域名解析

`server`（客户端侧）与目标地址（服务端侧）都支持填域名，解析是**异步**的、并且带 TTL 缓存
（成功 60s、失败 5s），不会阻塞 event loop。若遇到解析异常，先确认目标地址没有写错——
密码不对时服务端会在解密阶段就失败，不会走到解析。

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
