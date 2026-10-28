package com.example.nvrclient.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ONVIF RTSP/TCP audio backchannel. One instance owns one session. */
public final class Backchannel implements AudioChannel {
    private final String url, user, password;
    private volatile Socket socket;
    private InputStream input;
    private OutputStream output;
    private String challenge, session;
    private volatile boolean closed;
    private int cseq, nonceCount, sequence, timestamp, ssrc, channel;
    private Track track;
    private long lastKeepAlive;
    public Backchannel(String url, String user, String password) { this.url = url; this.user = user; this.password = password; }
    public static final class Track {
        public final String control;
        public final int payload;
        public final boolean alaw;
        Track(String control, int payload, boolean alaw) { this.control = control; this.payload = payload; this.alaw = alaw; }
    }
    public static Track parseSdp(String sdp) {
        for (String block : sdp.split("(?m)(?=^m=)")) {
            if (!block.startsWith("m=audio ") || !Pattern.compile("(?m)^a=sendonly\\s*$").matcher(block).find()) continue;
            Matcher control = Pattern.compile("(?m)^a=control:([^\\r\\n]+)").matcher(block);
            if (!control.find()) continue;
            String[] first = block.split("\\r?\\n", 2)[0].trim().split("\\s+");
            for (int i = 3; i < first.length; i++) {
                int pt;
                try { pt = Integer.parseInt(first[i]); } catch (NumberFormatException e) { continue; }
                if (pt < 0 || pt > 127) continue;
                Matcher codec = Pattern.compile("(?im)^a=rtpmap:" + pt + "\\s+(PCMA|PCMU)/8000(?:/1)?\\s*$").matcher(block);
                if (codec.find()) return new Track(control.group(1).trim(), pt, codec.group(1).equalsIgnoreCase("PCMA"));
                boolean mapped = Pattern.compile("(?m)^a=rtpmap:" + pt + "\\s").matcher(block).find();
                if (!mapped && (pt == 0 || pt == 8)) return new Track(control.group(1).trim(), pt, pt == 8);
            }
        }
        throw new IllegalArgumentException("设备未提供 G.711 ONVIF 对讲通道，请检查设备能力或使用厂商 SDK");
    }
    public void open() throws Exception {
        URI uri = new URI(url);
        if (!"rtsp".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null)
            throw new IllegalArgumentException("对讲需要不含账号的 RTSP 地址");
        if (closed) throw new EOFException();
        Socket created = new Socket(); socket = created;
        if (closed) { created.close(); throw new EOFException(); }
        created.connect(new InetSocketAddress(uri.getHost(), uri.getPort() < 0 ? 554 : uri.getPort()), 4000);
        created.setSoTimeout(4000); created.setTcpNoDelay(true);
        input = created.getInputStream(); output = created.getOutputStream();
        Response description = request("DESCRIBE", url, "Accept: application/sdp\r\n");
        track = parseSdp(description.body);
        String base = description.headers.getOrDefault("content-base", url);
        String target = resolve(base, track.control);
        URI trackUri = new URI(target);
        if (!uri.getHost().equalsIgnoreCase(trackUri.getHost())) throw new IllegalArgumentException("对讲地址与设备不一致");
        Response setup = request("SETUP", target, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        String sessionValue = setup.headers.get("session");
        if (sessionValue == null) throw new IllegalArgumentException("设备未返回对讲会话");
        session = sessionValue.split(";", 2)[0].trim();
        Matcher channels = Pattern.compile("interleaved=(\\d+)-(\\d+)", Pattern.CASE_INSENSITIVE)
            .matcher(setup.headers.getOrDefault("transport", ""));
        if (!channels.find()) throw new IllegalArgumentException("设备不支持 TCP 音频回传");
        channel = Integer.parseInt(channels.group(1));
        if (channel < 0 || channel > 254) throw new IllegalArgumentException("对讲通道无效");
        request("PLAY", url, "");
        SecureRandom random = new SecureRandom(); sequence = random.nextInt(65536); timestamp = random.nextInt(); ssrc = random.nextInt();
        lastKeepAlive = System.nanoTime();
    }
    static String resolve(String base, String control) throws Exception {
        if (control.startsWith("rtsp://")) return control;
        if (control.equals("*")) return base;
        URI uri = new URI(base);
        if (control.startsWith("/")) return uri.resolve(control).toString();
        return (base.endsWith("/") ? base : base + "/") + control;
    }
    public void send(short[] samples, int count) throws Exception {
        if (closed || track == null || count <= 0 || count > 160) throw new IllegalStateException("对讲会话已停止");
        byte[] packet = new byte[16 + count];
        ByteBuffer b = ByteBuffer.wrap(packet);
        b.put((byte) '$').put((byte) channel).putShort((short) (12 + count));
        b.put((byte) 0x80).put((byte) track.payload).putShort((short) sequence++).putInt(timestamp).putInt(ssrc);
        for (int i = 0; i < count; i++) b.put(G711.encode(samples[i], track.alaw));
        output.write(packet); output.flush(); timestamp += count;
        if (System.nanoTime() - lastKeepAlive > 10_000_000_000L) {
            request("OPTIONS", url, ""); lastKeepAlive = System.nanoTime();
        }
    }
    private Response request(String method, String target, String extra) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (closed) throw new EOFException();
            StringBuilder request = new StringBuilder(method).append(' ').append(target).append(" RTSP/1.0\r\nCSeq: ").append(++cseq)
                .append("\r\nUser-Agent: NvrClient/0.2\r\nRequire: www.onvif.org/ver20/backchannel\r\n");
            if (session != null) request.append("Session: ").append(session).append("\r\n");
            if (challenge != null) request.append("Authorization: ").append(Auth.header(challenge, user, password, method, target, ++nonceCount)).append("\r\n");
            request.append(extra).append("\r\n");
            output.write(request.toString().getBytes(StandardCharsets.UTF_8)); output.flush();
            Response response = read();
            if (response.code == 401 && attempt == 0) { challenge = response.headers.get("www-authenticate"); nonceCount = 0; continue; }
            if (response.code == 401 || response.code == 403) throw new IllegalStateException("对讲认证失败");
            if (response.code == 551) throw new IllegalStateException("设备不支持 ONVIF 对讲，请使用厂商 SDK 接入");
            if (response.code < 200 || response.code >= 300) throw new IllegalStateException("对讲请求失败（RTSP " + response.code + "）");
            return response;
        }
        throw new IllegalStateException("对讲认证失败");
    }
    private Response read() throws Exception {
        int first = input.read();
        while (first == '$') {
            readByte(); int size = (readByte() << 8) | readByte();
            for (int i = 0; i < size; i++) readByte();
            first = input.read();
        }
        if (first < 0) throw new EOFException();
        String status = (char) first + line();
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("RTSP/")) throw new IllegalArgumentException("无效的 RTSP 响应");
        Response response = new Response(); response.code = Integer.parseInt(parts[1]);
        for (int i = 0; ; i++) {
            if (i > 100) throw new IllegalArgumentException("RTSP 响应头过大");
            String line = line(); if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) response.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        int length = Integer.parseInt(response.headers.getOrDefault("content-length", "0"));
        if (length < 0 || length > 1024 * 1024) throw new IllegalArgumentException("RTSP 响应过大");
        byte[] data = new byte[length];
        for (int offset = 0; offset < length;) { int n = input.read(data, offset, length - offset); if (n < 0) throw new EOFException(); offset += n; }
        response.body = new String(data, StandardCharsets.UTF_8);
        return response;
    }
    private int readByte() throws Exception { int value = input.read(); if (value < 0) throw new EOFException(); return value; }
    private String line() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 8192) { int c = readByte(); if (c == '\n') return bytes.toString("UTF-8").replace("\r", ""); bytes.write(c); }
        throw new IllegalArgumentException("RTSP 响应行过大");
    }
    /** Called by the audio worker after recording stops. */
    public void teardown() {
        try { if (!closed && session != null) request("TEARDOWN", url, ""); } catch (Exception ignored) { }
        close();
    }
    /** Interrupt connection setup, reads or writes from the lifecycle thread. */
    @Override public void close() {
        closed = true; Socket current = socket;
        if (current != null) try { current.close(); } catch (Exception ignored) { }
    }
    private static final class Response {
        int code; String body; final Map<String, String> headers = new HashMap<>();
    }
}
