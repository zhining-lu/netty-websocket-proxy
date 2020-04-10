package cn.wowspeeder.sw;

import cn.wowspeeder.SWLocal;
import io.netty.util.ResourceLeakDetector;

public class SSLocalTest {
    public static void main(String[] args) {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED);
        try {
            SWLocal.getInstance().start("./conf/config-example-client.json");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
