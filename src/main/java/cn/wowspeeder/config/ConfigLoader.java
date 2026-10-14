package cn.wowspeeder.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;

/**
 * 加载Config配置
 */
public class ConfigLoader {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ConfigLoader.class);

    public static Config load(String file) throws Exception {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            JsonReader reader;
            reader = new JsonReader(new InputStreamReader(in, "UTF-8"));
            Config config = new Gson().fromJson(reader, Config.class);
            reader.close();
            return config;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
