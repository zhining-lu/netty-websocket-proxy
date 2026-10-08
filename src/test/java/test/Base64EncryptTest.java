package test;

import cn.wowspeeder.encryption.Base64Encrypt;

public class Base64EncryptTest {
    public static void main(String[] args) throws Exception {

        // 每个密码对应一个实例，必须用同一个实例完成加解密往返
        Base64Encrypt base64 = Base64Encrypt.getInstance("gghggh");

        String encString = base64.getEncString("133.333.334.456:80");

        System.out.println(encString);

        System.out.println(base64.getDesString(encString));

        Base64Encrypt base642 = Base64Encrypt.getInstance("55555");

        String encString2 = base642.getEncString("13.333.334.666:443");

        System.out.println(encString2);

        System.out.println(base642.getDesString(encString2));

        // 不同密码的实例必须互相隔离：用  的密钥无法解开 base642 的密文base64
        System.out.println("instance isolated: " + (base64 != base642));
        // 同一密码必须复用同一实例
        System.out.println("instance cached: " + (base64 == Base64Encrypt.getInstance("gghggh")));

    }
}
