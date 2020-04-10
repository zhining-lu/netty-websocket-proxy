package cn.wowspeeder.sw;

import cn.wowspeeder.SWServer;
import io.netty.util.ResourceLeakDetector;

public class SSServerTest {

    public static void main(String[] args) {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);
        try {
            SWServer.getInstance().start("./conf/config-example-server.json");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
