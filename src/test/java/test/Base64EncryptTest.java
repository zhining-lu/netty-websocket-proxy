package test;

import cn.wowspeeder.encryption.Base64Encrypt;

public class Base64EncryptTest {
    public static void main(String[] args) throws Exception {

        Base64Encrypt base64 = Base64Encrypt.getInstance("gghggh");

        String encString = base64.getEncString("133.333.334.456:80");

        System.out.println(encString);

        System.out.println(base64.getDesString(encString));

        Base64Encrypt base642 = Base64Encrypt.getInstance("55555");

        String encString2 = base64.getEncString("13.333.334.666:443");

        System.out.println(encString2);

        System.out.println(base642.getDesString(encString2));

    }
}
