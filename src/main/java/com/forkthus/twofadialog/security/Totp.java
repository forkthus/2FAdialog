package com.forkthus.twofadialog.security;

import org.apache.commons.codec.binary.Base32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

public final class Totp {
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base32 B32 = new Base32(false); // no padding

    public static String newBase32Secret() {
        byte[] buf = new byte[20]; // 160-bit
        RNG.nextBytes(buf);
        return B32.encodeAsString(buf).replace("=", "");
    }

    public static String provisioningUri(String issuer, String account, String base32Secret) {
        // otpauth://totp/Issuer:account?secret=...&issuer=Issuer&digits=6&period=30
        String label = url(issuer + ":" + account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret +
                "&issuer=" + url(issuer) + "&digits=6&period=30";
    }

    public static boolean verify(String base32Secret, String code, int skewSteps) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long step = Instant.now().getEpochSecond() / 30;
        for (int i = -skewSteps; i <= skewSteps; i++) {
            if (code.equals(gen(base32Secret, step + i))) return true;
        }
        return false;
    }

    private static String gen(String base32Secret, long step) {
        byte[] key = B32.decode(base32Secret);
        byte[] msg = ByteBuffer.allocate(8).putLong(step).array();
        byte[] hmac = hmacSha1(key, msg);
        int off = hmac[hmac.length - 1] & 0x0F;
        int bin = ((hmac[off] & 0x7F) << 24) |
                ((hmac[off+1] & 0xFF) << 16) |
                ((hmac[off+2] & 0xFF) << 8) |
                (hmac[off+3] & 0xFF);
        int otp = bin % 1_000_000;
        return String.format("%06d", otp);
    }

    private static byte[] hmacSha1(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(msg);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
