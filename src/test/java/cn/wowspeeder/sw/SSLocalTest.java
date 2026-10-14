package cn.wowspeeder.sw;

import cn.wowspeeder.SWLocal;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

public class SSLocalTest {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SSLocalTest.class);

    public static void main(String[] args) {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);
        try {
            SWLocal.getInstance().start("./conf/config-example-client.json");
        } catch (Exception e) {
            logger.error("start SWLocal failed", e);
        }
    }
}
