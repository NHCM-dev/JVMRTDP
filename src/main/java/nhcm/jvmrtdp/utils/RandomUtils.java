package nhcm.jvmrtdp.utils;

import java.util.concurrent.ThreadLocalRandom;

public class RandomUtils
{

    private static final String CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }

        return sb.toString();
    }
}