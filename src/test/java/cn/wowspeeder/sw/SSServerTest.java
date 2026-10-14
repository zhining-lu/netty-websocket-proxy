package cn.wowspeeder.sw;

import cn.wowspeeder.SWServer;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

public class SSServerTest {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SSServerTest.class);

    public static void main(String[] args) {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);
        try {
            SWServer.getInstance().start("./conf/config-example-server.json");
        } catch (Exception e) {
            logger.error("start SWServer failed", e);
        }
    }
}
