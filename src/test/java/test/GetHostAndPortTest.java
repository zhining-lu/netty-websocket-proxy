package test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GetHostAndPortTest {
    public static void main(String[] args) {

        Pattern p = Pattern.compile("^\\s*(.*?):(\\d+)\\s*$");
        Matcher m = p.matcher("wwww.baidu.com:8080");
        if (m.matches()) {
            String host = m.group(1);
            int port = Integer.parseInt(m.group(2));
            System.out.println(host);
            System.out.println(port);
        }
    }
}
