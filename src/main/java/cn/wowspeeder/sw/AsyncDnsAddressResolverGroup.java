package cn.wowspeeder.sw;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.InetNameResolver;
import io.netty.resolver.InetSocketAddressResolver;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步 + 带 TTL 缓存的域名解析器。
 *
 * <p><b>为什么需要它：</b>Netty 在不显式指定 resolver 时用的是
 * {@link io.netty.resolver.DefaultAddressResolverGroup}，其内部是
 * {@code DefaultNameResolver} —— 直接调 {@code InetAddress.getByName()}，也就是
 * <b>在调用线程上同步阻塞</b>。而 {@code Bootstrap.connect()} 是在 channel 的 event loop
 * 上跑的，换句话说：一次慢 DNS（几秒）会把挂在这个 event loop 上的<b>所有</b>连接一起卡住。
 * 本项目里这发生在两个地方：
 * <ul>
 *   <li>服务端 {@code SWServerTcpProxyHandler} 连目标地址（每条隧道一次，且目标多为域名）</li>
 *   <li>本地 {@code SWLocalTcpProxyHandler} 连代理服务器（每来一条浏览器连接一次）</li>
 * </ul>
 *
 * <p><b>做法：</b>把阻塞解析丢到一个独立的小线程池里执行，event loop 只负责等
 * promise 完成；同时对解析结果做 TTL 缓存，避免同一域名在短时间内反复查。
 * 与之配套的是调用方必须用 {@link InetSocketAddress#createUnresolved(String, int)}
 * —— 只有「未解析」的地址才会走 resolver，{@code new InetSocketAddress(host, port)}
 * 会在构造时就把 DNS 查掉，绕过这里。
 *
 * <p><b>取舍：</b>没有用 netty-resolver-dns 的 {@code DnsAddressResolverGroup}，
 * 因为那条路依赖读取系统 DNS 配置（Windows 下 4.1.42 的实现可能取不到 name server），
 * 且会把 DNS 语义（EDNS、TCP 回退等）一并引进来。这里只用 JDK 自带的解析，
 * 行为与改动前完全一致，只是不阻塞 event loop、并且有缓存。
 */
public final class AsyncDnsAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AsyncDnsAddressResolverGroup.class);

    public static final AsyncDnsAddressResolverGroup INSTANCE = new AsyncDnsAddressResolverGroup();

    /** 解析成功后的缓存有效期（秒）。 */
    static final int POSITIVE_TTL_SECONDS = 60;

    /** 解析失败后的缓存有效期（秒），避免目标长时间不可达时反复打 DNS。 */
    static final int NEGATIVE_TTL_SECONDS = 5;

    /**
     * 执行阻塞解析的线程池。
     * <p>core=2 常驻，队列满到 16 之后才开始扩容到 16 个线程；再满就直接拒掉
     * （宁可让这条连接立刻失败，也不能回退到 event loop 上同步解析）。
     * 线程是 daemon，不会拖住 JVM 退出。
     */
    private static final ThreadPoolExecutor RESOLVER_POOL;

    static {
        final AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "sw-dns-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        RESOLVER_POOL = new ThreadPoolExecutor(
                2, 16, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(16), factory);
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<String, Entry>();

    private AsyncDnsAddressResolverGroup() {
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) throws Exception {
        // InetSocketAddressResolver 负责把「主机名 -> InetAddress」包装成「InetSocketAddress -> InetSocketAddress」，
        // 并在结果回来时用已解析的 InetAddress + 原端口拼出目标地址（这一步不查 DNS，不会阻塞）。
        return new InetSocketAddressResolver(executor, new CachingNameResolver(executor, cache));
    }

    /** 清空缓存。仅供测试与排障使用。 */
    public void clearCache() {
        cache.clear();
    }

    /** 当前缓存条目数。仅供测试与排障使用。 */
    public int cacheSize() {
        return cache.size();
    }

    /** 一条缓存记录：{@code addresses} 与 {@code failure} 恰有一个非 null。 */
    static final class Entry {
        final InetAddress[] addresses;
        final UnknownHostException failure;
        final long expireAtNanos;

        Entry(InetAddress[] addresses, UnknownHostException failure, long expireAtNanos) {
            this.addresses = addresses;
            this.failure = failure;
            this.expireAtNanos = expireAtNanos;
        }
    }

    private static final class CachingNameResolver extends InetNameResolver {

        private final Map<String, Entry> cache;

        CachingNameResolver(EventExecutor executor, Map<String, Entry> cache) {
            super(executor);
            this.cache = cache;
        }

        @Override
        protected void doResolve(String inetHost, Promise<InetAddress> promise) throws Exception {
            // 已经是 IP 字面量就不用查了（目标填 IP 是常见配置，省一次缓存查找）
            byte[] literal = NetUtil.createByteArrayFromIpAddressString(inetHost);
            if (literal != null) {
                promise.trySuccess(InetAddress.getByAddress(inetHost, literal));
                return;
            }

            Entry cached = get(inetHost);
            if (cached != null) {
                if (cached.failure != null) {
                    promise.tryFailure(cached.failure);
                } else {
                    promise.trySuccess(cached.addresses[0]);
                }
                return;
            }

            submitResolve(inetHost, promise);
        }

        @Override
        protected void doResolveAll(String inetHost, Promise<List<InetAddress>> promise) throws Exception {
            byte[] literal = NetUtil.createByteArrayFromIpAddressString(inetHost);
            if (literal != null) {
                promise.trySuccess(Collections.singletonList(InetAddress.getByAddress(inetHost, literal)));
                return;
            }

            Entry cached = get(inetHost);
            if (cached != null) {
                if (cached.failure != null) {
                    promise.tryFailure(cached.failure);
                } else {
                    promise.trySuccess(Arrays.asList(cached.addresses));
                }
                return;
            }

            submitResolveAll(inetHost, promise);
        }

        private void submitResolve(final String host, final Promise<InetAddress> promise) {
            try {
                RESOLVER_POOL.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            InetAddress address = InetAddress.getByName(host);
                            put(host, new InetAddress[]{address}, null, POSITIVE_TTL_SECONDS);
                            promise.trySuccess(address);
                        } catch (UnknownHostException e) {
                            put(host, null, e, NEGATIVE_TTL_SECONDS);
                            logger.debug("dns resolve failed: {}", host, e);
                            promise.tryFailure(e);
                        } catch (Throwable t) {
                            // 兜底：绝不让异常逃出线程池任务，否则这个工作线程会直接死掉
                            promise.tryFailure(t);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                logger.warn("dns resolver pool is saturated, reject resolve of {}", host);
                promise.tryFailure(e);
            }
        }

        private void submitResolveAll(final String host, final Promise<List<InetAddress>> promise) {
            try {
                RESOLVER_POOL.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            InetAddress[] addresses = InetAddress.getAllByName(host);
                            put(host, addresses, null, POSITIVE_TTL_SECONDS);
                            promise.trySuccess(Arrays.asList(addresses));
                        } catch (UnknownHostException e) {
                            put(host, null, e, NEGATIVE_TTL_SECONDS);
                            logger.debug("dns resolveAll failed: {}", host, e);
                            promise.tryFailure(e);
                        } catch (Throwable t) {
                            promise.tryFailure(t);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                logger.warn("dns resolver pool is saturated, reject resolveAll of {}", host);
                promise.tryFailure(e);
            }
        }

        private Entry get(String host) {
            Entry entry = cache.get(host);
            if (entry == null) {
                return null;
            }
            if (entry.expireAtNanos - System.nanoTime() <= 0) {
                cache.remove(host, entry);
                return null;
            }
            return entry;
        }

        private void put(String host, InetAddress[] addresses, UnknownHostException failure, int ttlSeconds) {
            cache.put(host, new Entry(addresses, failure,
                    System.nanoTime() + ttlSeconds * 1000L * 1000L * 1000L));
        }
    }
}
