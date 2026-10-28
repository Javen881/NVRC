package com.example.nvrclient.protocol;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real socket tests against deterministic mock devices, no camera credentials required. */
public final class ProtocolTest {
    private static int assertions;
    private static void check(boolean valid, String label) { assertions++; if (!valid) throw new AssertionError(label); }
    private static void rejects(Throwing action, String label) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError(label);
    }
    interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        auth(); codecs(); sdp(); onvif(); backchannel(); cancellation();
        System.out.println("PASS: " + assertions + " assertions; authentication, codecs, SDP, ONVIF HTTP, RTSP/RTP and cancellation");
    }
    private static void auth() throws Exception {
        check(Auth.header("Basic realm=\"camera\"", "admin", "pass", "GET", "/", 1).equals("Basic YWRtaW46cGFzcw=="), "basic credentials");
        String challenge = "Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", qop=\"auth,auth-int\", opaque=\"x\"";
        String result = Auth.header(challenge, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1);
        java.util.regex.Matcher c = java.util.regex.Pattern.compile("cnonce=\"([^\"]+)\"").matcher(result); check(c.find(), "client nonce");
        String expected = Auth.digest("MD5", "939e7578ed9e3c518a452acee763bce9:dcd98b7102dd2f0e8b11d0f600bfb0c093:00000001:" + c.group(1) + ":auth:39aff3a2bab6126f332b942af96d3366");
        check(result.contains("response=\"" + expected + "\""), "RFC digest response");
        rejects(() -> Auth.header("Digest realm=\"x\",nonce=\"y\",qop=\"auth-int\"", "a", "b", "GET", "/", 1), "unsupported qop");
    }
    private static void codecs() {
        check((G711.encode((short)0, true) & 255) == 0xd5, "A-law zero");
        check((G711.encode((short)-1, true) & 255) == 0x55, "A-law negative near zero");
        check((G711.encode((short)0, false) & 255) == 0xff, "mu-law zero");
        check((G711.encode(Short.MAX_VALUE, true) & 255) == 0xaa, "A-law positive clipping");
        check((G711.encode(Short.MIN_VALUE, true) & 255) == 0x2a, "A-law negative clipping");
        for (int i = -32768; i <= 32767; i++) {
            int a = (G711.encode((short)i, true) & 255) ^ 0x55;
            int exponent = (a & 0x70) >> 4, decoded = (a & 15) << 4;
            decoded = exponent == 0 ? decoded + 8 : (decoded + 264) << (exponent - 1);
            if ((a & 0x80) == 0) decoded = -decoded;
            check(Math.abs(decoded - i) <= 512, "A-law quantization bound");
            int u = (~G711.encode((short)i, false)) & 255;
            int magnitude = (((u & 15) << 3) + 132) << ((u & 0x70) >> 4);
            decoded = (u & 0x80) != 0 ? 132 - magnitude : magnitude - 132;
            check(Math.abs(decoded - i) <= 644, "mu-law quantization bound");
        }
    }
    private static void sdp() throws Exception {
        String sdp = "v=0\r\nm=audio 0 RTP/AVP 0\r\na=control:listen\r\na=recvonly\r\nm=audio 0 RTP/AVP 97 8\r\na=control:talk\r\na=sendonly\r\na=rtpmap:97 G726-16/8000\r\n";
        Backchannel.Track t = Backchannel.parseSdp(sdp);
        check(t.alaw && t.payload == 8 && t.control.equals("talk"), "select backchannel not downlink");
        rejects(() -> Backchannel.parseSdp("m=audio 0 RTP/AVP 0\r\na=recvonly\r\na=control:listen\r\n"), "reject no backchannel");
        rejects(() -> Backchannel.parseSdp("m=audio 0 RTP/AVP 96\r\na=sendonly\r\na=control:talk\r\na=rtpmap:96 AAC/48000\r\n"), "reject unsupported codec");
        rejects(() -> OnvifClient.parse("<!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><r>&x;</r>".getBytes(StandardCharsets.UTF_8)), "reject external entity");
    }
    private static void onvif() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/onvif";
        List<String> actions = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> problem = new AtomicReference<>();
        server.createContext("/onvif", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (exchange.getRequestHeaders().getFirst("Authorization") == null) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Digest realm=\"test\",nonce=\"nonce\",qop=\"auth\"");
                    exchange.sendResponseHeaders(401, -1); return;
                }
                check(body.contains("PasswordDigest"), "WSSE digest present");
                String action = exchange.getRequestHeaders().getFirst("Content-Type");
                String payload;
                if (action.contains("GetCapabilities")) payload = "<Capabilities><Media><XAddr>" + endpoint + "</XAddr></Media><PTZ><XAddr>" + endpoint + "</XAddr></PTZ></Capabilities>";
                else if (action.contains("GetProfiles")) payload = "<Profiles token=\"p&amp;1\"><Name>Front</Name><PTZConfiguration/></Profiles>";
                else if (action.contains("GetStreamUri")) { check(body.contains("p&amp;1"), "escape profile token"); payload = "<MediaUri><Uri>rtsp://127.0.0.1/live</Uri></MediaUri>"; }
                else if (action.contains("ContinuousMove")) { check(body.contains("PT1S"), "device-side movement timeout"); check(body.contains("x=\"0.5\"") && body.contains("y=\"0.5\""), "diagonal pan and tilt sent together"); actions.add("move"); payload = "<ContinuousMoveResponse/>"; }
                else { check(action.contains("/Stop"), "explicit stop"); check(body.contains("<d:PanTilt>true"), "pan tilt stop flag"); actions.add("stop"); payload = "<StopResponse/>"; }
                byte[] response = ("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body>" + payload + "</s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Throwable e) { problem.set(e); } finally { exchange.close(); }
        });
        server.start();
        try {
            OnvifClient client = new OnvifClient("admin", "pass");
            List<OnvifClient.Profile> profiles = client.discover(endpoint);
            check(profiles.size() == 1 && profiles.get(0).token.equals("p&1"), "channel enumeration");
            check(client.stream(profiles.get(0)).equals("rtsp://127.0.0.1/live"), "get stream URI");
            client.move(endpoint, "p&1", .5f, .5f, 0); client.stop(endpoint, "p&1");
            check(actions.equals(Arrays.asList("move", "stop")), "move-stop ordering");
            if (problem.get() != null) throw new AssertionError(problem.get());
        } finally { server.stop(0); }
    }
    private static String readRequest(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int last = 0;
        while (out.size() < 8192) {
            int c = input.read(); if (c < 0) throw new EOFException(); out.write(c);
            if (last == '\n' && c == '\r') { if (input.read() != '\n') throw new IOException(); out.write('\n'); return out.toString("UTF-8"); }
            last = c;
        }
        throw new IOException("headers too large");
    }
    private static void response(OutputStream out, int code, String headers, String body) throws Exception {
        out.write(("RTSP/1.0 " + code + " Test\r\n" + headers + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8)); out.flush();
    }
    private static void backchannel() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?> device = worker.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000); InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
                    check(readRequest(in).startsWith("DESCRIBE"), "RTSP describe");
                    response(out, 401, "WWW-Authenticate: Digest realm=\"camera\",nonce=\"123\"\r\n", "");
                    String authenticated = readRequest(in);
                    check(authenticated.contains("Authorization: Digest") && authenticated.contains("Require: www.onvif.org/ver20/backchannel"), "RTSP auth and backchannel negotiation");
                    response(out, 200, "", "v=0\r\nm=audio 0 RTP/AVP 8\r\na=sendonly\r\na=control:talk\r\n");
                    check(readRequest(in).contains("/live/talk RTSP/1.0"), "relative track URI");
                    response(out, 200, "Session: 123;timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=4-5\r\n", "");
                    check(readRequest(in).startsWith("PLAY"), "start audio with PLAY"); response(out, 200, "", "");
                    byte[] packet = in.readNBytes(176); check(packet.length == 176, "20ms RTP packet size");
                    ByteBuffer b = ByteBuffer.wrap(packet);
                    check(b.get() == '$' && b.get() == 4 && b.getShort() == 172, "negotiated TCP channel");
                    check((b.get() & 255) == 128 && b.get() == 8, "RTP header and codec");
                    for (int i = 16; i < packet.length; i++) check((packet[i] & 255) == 0xd5, "encoded audio payload");
                    check(readRequest(in).startsWith("TEARDOWN"), "explicit teardown"); response(out, 200, "", "");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            try (Backchannel channel = new Backchannel("rtsp://127.0.0.1:" + server.getLocalPort() + "/live", "admin", "pass")) {
                channel.open(); channel.send(new short[160], 160); channel.teardown(); device.get(8, TimeUnit.SECONDS);
            } finally { worker.shutdownNow(); }
        }
    }
    private static void cancellation() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Backchannel channel = new Backchannel("rtsp://127.0.0.1:" + server.getLocalPort() + "/live", "", "");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?> result = worker.submit(() -> { try { channel.open(); throw new AssertionError("cancel must interrupt"); } catch (Exception expected) { } });
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(2000); readRequest(peer.getInputStream()); channel.close(); result.get(2, TimeUnit.SECONDS);
                check(true, "cancel interrupts pending RTSP response");
            } finally { channel.close(); worker.shutdownNow(); }
        }
    }
}
