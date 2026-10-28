package com.example.nvrclient.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class OnvifClient {
    private static final String DEVICE = "http://www.onvif.org/ver10/device/wsdl";
    private static final String MEDIA = "http://www.onvif.org/ver10/media/wsdl";
    private static final String PTZ = "http://www.onvif.org/ver20/ptz/wsdl";
    private final String user, password;
    public OnvifClient(String user, String password) { this.user = user; this.password = password; }
    public static final class Profile {
        public final String name, token, stream, ptz;
        Profile(String name, String token, String stream, String ptz) {
            this.name = name; this.token = token; this.stream = stream; this.ptz = ptz;
        }
    }
    public List<Profile> discover(String endpoint) throws Exception {
        Document caps = call(endpoint, DEVICE, "GetCapabilities", "<d:Category>All</d:Category>");
        String media = childText(caps, "Media", "XAddr"), ptz = childText(caps, "PTZ", "XAddr");
        if (media.isEmpty()) throw new IllegalStateException("设备未提供 ONVIF Media 服务");
        Document profiles = call(media, MEDIA, "GetProfiles", "");
        List<Profile> result = new ArrayList<>();
        NodeList nodes = profiles.getElementsByTagNameNS("*", "Profiles");
        for (int i = 0; i < nodes.getLength() && i < 128; i++) {
            Element p = (Element) nodes.item(i);
            String token = p.getAttribute("token");
            if (token.isEmpty()) continue;
            // Resolve the URI only after selection, avoiding requests for every NVR channel.
            result.add(new Profile(text(p, "Name"), token, media,
                p.getElementsByTagNameNS("*", "PTZConfiguration").getLength() > 0 ? ptz : ""));
        }
        if (result.isEmpty()) throw new IllegalStateException("未找到视频通道，请检查 ONVIF 权限");
        return result;
    }
    public String stream(Profile profile) throws Exception {
        Document response = call(profile.stream, MEDIA, "GetStreamUri",
            "<d:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol>"
            + "</tt:Transport></d:StreamSetup><d:ProfileToken>" + xml(profile.token) + "</d:ProfileToken>");
        String uri = text(response.getDocumentElement(), "Uri");
        if (uri.isEmpty()) throw new IllegalStateException("设备未返回视频流地址");
        return uri;
    }
    public void move(String endpoint, String token, float pan, float tilt, float zoom) throws Exception {
        String velocity = zoom == 0 ? "<tt:PanTilt x=\"" + pan + "\" y=\"" + tilt + "\"/>"
            : "<tt:Zoom x=\"" + zoom + "\"/>";
        call(endpoint, PTZ, "ContinuousMove", "<d:ProfileToken>" + xml(token)
            + "</d:ProfileToken><d:Velocity>" + velocity + "</d:Velocity><d:Timeout>PT1S</d:Timeout>");
    }
    public void stop(String endpoint, String token) throws Exception {
        call(endpoint, PTZ, "Stop", "<d:ProfileToken>" + xml(token)
            + "</d:ProfileToken><d:PanTilt>true</d:PanTilt><d:Zoom>true</d:Zoom>");
    }
    private Document call(String endpoint, String namespace, String operation, String body) throws Exception {
        URI uri = new URI(endpoint);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null) throw new IllegalArgumentException("设备服务地址无效");
        String authorization = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(4000); connection.setReadTimeout(4000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + namespace + "/" + operation + "\"");
            if (authorization != null) connection.setRequestProperty("Authorization", authorization);
            String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:d=\"" + namespace + "\" xmlns:tt=\"http://www.onvif.org/ver10/schema\"><s:Header>" + security()
                + "</s:Header><s:Body><d:" + operation + ">" + body + "</d:" + operation + "></s:Body></s:Envelope>";
            try {
                byte[] data = envelope.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(data); }
                int code = connection.getResponseCode();
                if (code == 401 && attempt == 0) {
                    String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                    authorization = Auth.header(connection.getHeaderField("WWW-Authenticate"), user, password, "POST", target, 1);
                    continue;
                }
                if (code == 401 || code == 403) throw new IllegalStateException("设备认证失败，请检查 ONVIF 账号及权限");
                if (code < 200 || code >= 300) throw new IllegalStateException("ONVIF 请求失败（HTTP " + code + "），请检查设备能力和配置");
                byte[] bytes;
                try (InputStream input = connection.getInputStream()) { bytes = limitedRead(input, 2 * 1024 * 1024); }
                Document doc = parse(bytes);
                if (doc.getElementsByTagNameNS("*", "Fault").getLength() > 0)
                    throw new IllegalStateException("设备返回 ONVIF Fault，请检查权限、设备时间和控制能力");
                return doc;
            } finally { connection.disconnect(); }
        }
        throw new IllegalStateException("设备认证失败");
    }
    private String security() throws Exception {
        if (user.isEmpty()) return "";
        byte[] nonce = new byte[16]; new SecureRandom().nextBytes(nonce);
        String created = Instant.now().toString();
        ByteArrayOutputStream raw = new ByteArrayOutputStream(); raw.write(nonce);
        raw.write(created.getBytes(StandardCharsets.UTF_8)); raw.write(password.getBytes(StandardCharsets.UTF_8));
        String digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()));
        String se = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
        String base = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-";
        return "<wsse:Security xmlns:wsse=\"" + se + "\" xmlns:wsu=\"" + base + "wssecurity-utility-1.0.xsd\">"
            + "<wsse:UsernameToken><wsse:Username>" + xml(user) + "</wsse:Username><wsse:Password Type=\"" + base
            + "username-token-profile-1.0#PasswordDigest\">" + digest + "</wsse:Password><wsse:Nonce EncodingType=\"" + base
            + "soap-message-security-1.0#Base64Binary\">" + Base64.getEncoder().encodeToString(nonce)
            + "</wsse:Nonce><wsu:Created>" + created + "</wsu:Created></wsse:UsernameToken></wsse:Security>";
    }
    public static Document parse(byte[] data) throws Exception {
        String source = new String(data, StandardCharsets.UTF_8);
        // Parse character input so alternate XML encodings cannot bypass the DTD check.
        if (source.indexOf('\0') >= 0 || java.util.regex.Pattern.compile("<!\\s*(DOCTYPE|ENTITY)",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(source).find())
            throw new IllegalArgumentException("设备 XML 包含禁止的声明");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(source)));
    }
    private static String childText(Document doc, String parent, String child) {
        NodeList nodes = doc.getElementsByTagNameNS("*", parent);
        return nodes.getLength() == 0 ? "" : text((Element) nodes.item(0), child);
    }
    private static String text(Element element, String name) {
        NodeList nodes = element.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
    static byte[] limitedRead(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
        while ((n = in.read(buffer)) != -1) { if (out.size() + n > limit) throw new IllegalStateException("设备响应过大"); out.write(buffer, 0, n); }
        return out.toByteArray();
    }
    public static String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
}
