package test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PatternTest {
    public static void main(String[] args) {

        Pattern p = Pattern.compile("^token=(.*)");
        Matcher m = p.matcher("token=nsw3L0+xrEssTSHMKZD97A==");
        if (m.matches()) {
            System.out.println(m.group(1));
        }else {
            System.out.println("not mm");
        }
    }
}
