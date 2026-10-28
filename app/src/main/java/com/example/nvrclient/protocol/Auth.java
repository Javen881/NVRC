package com.example.nvrclient.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP/RTSP authentication; credentials never appear in diagnostic messages. */
public final class Auth {
    private Auth() {}
    public static String header(String challenge, String user, String password,
                                String method, String uri, int count) throws Exception {
        if (challenge == null) throw new IllegalArgumentException("设备未提供认证方式");
        if (challenge.regionMatches(true, 0, "Basic", 0, 5)) {
            return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
        if (!challenge.regionMatches(true, 0, "Digest", 0, 6))
            throw new IllegalArgumentException("不支持的认证方式");
        Map<String, String> p = new HashMap<>();
        Matcher m = Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").matcher(challenge.substring(6));
        while (m.find()) p.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2) != null ? m.group(2) : m.group(3));
        String realm = p.get("realm"), nonce = p.get("nonce");
        if (realm == null || nonce == null) throw new IllegalArgumentException("认证参数缺失");
        String algorithm = p.getOrDefault("algorithm", "MD5");
        boolean session = algorithm.toLowerCase(Locale.ROOT).endsWith("-sess");
        String hash = session ? algorithm.substring(0, algorithm.length() - 5) : algorithm;
        if (!hash.equalsIgnoreCase("MD5") && !hash.equalsIgnoreCase("SHA-256"))
            throw new IllegalArgumentException("不支持的摘要算法");
        String qop = p.get("qop");
        boolean auth = qop != null && java.util.Arrays.stream(qop.split(",")).anyMatch(v -> v.trim().equals("auth"));
        if (qop != null && !auth) throw new IllegalArgumentException("设备认证不支持 auth 模式");
        String cnonce = UUID.randomUUID().toString().replace("-", ""), nc = String.format(Locale.ROOT, "%08x", count);
        String ha1 = digest(hash, user + ":" + realm + ":" + password);
        if (session) ha1 = digest(hash, ha1 + ":" + nonce + ":" + cnonce);
        String ha2 = digest(hash, method + ":" + uri);
        String response = digest(hash, ha1 + ":" + nonce + ":" + (auth ? nc + ":" + cnonce + ":auth:" : "") + ha2);
        return "Digest username=" + quote(user) + ", realm=" + quote(realm) + ", nonce=" + quote(nonce)
            + ", uri=" + quote(uri) + ", response=" + quote(response) + ", algorithm=" + algorithm
            + (auth ? ", qop=auth, nc=" + nc + ", cnonce=" + quote(cnonce) : session ? ", cnonce=" + quote(cnonce) : "")
            + (p.containsKey("opaque") ? ", opaque=" + quote(p.get("opaque")) : "");
    }
    static String digest(String algorithm, String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static String quote(String s) {
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) throw new IllegalArgumentException("认证字段无效");
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
