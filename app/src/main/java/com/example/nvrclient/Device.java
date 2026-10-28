package com.example.nvrclient;

import org.json.JSONObject;
import java.net.URI;

final class Device {
    String id = java.util.UUID.randomUUID().toString();
    String name = "我的摄像头", brand = "通用 ONVIF", endpoint = "", stream = "", user = "", password = "";
    String profile = "", ptz = "", talkStream = "";
    String connection = "onvif", host = "";
    int sdkPort = 8000, sdkChannel = 0, sdkStream = 1, voiceChannel = 1;
    int voiceCodec = 0;
    boolean voiceToCamera;
    boolean hikvision() { return "hikvision-sdk".equals(connection); }
    boolean uniview() { return "uniview-sdk".equals(connection); }
    boolean dahua() { return "dahua-sdk".equals(connection); }
    boolean nativeSdk() { return hikvision() || uniview() || dahua(); }
    JSONObject json() throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("brand", brand).put("endpoint", endpoint)
            .put("stream", stream).put("user", user).put("password", password).put("profile", profile)
            .put("ptz", ptz).put("talkStream", talkStream).put("connection", connection).put("host", host)
            .put("sdkPort", sdkPort).put("sdkChannel", sdkChannel).put("sdkStream", sdkStream).put("voiceChannel", voiceChannel).put("voiceCodec",voiceCodec).put("voiceToCamera",voiceToCamera);
    }
    static Device from(JSONObject o) {
        Device d = new Device();
        d.name = o.optString("name", d.name); d.brand = o.optString("brand", d.brand);
        d.endpoint = o.optString("endpoint"); d.stream = o.optString("stream");
        d.user = o.optString("user"); d.password = o.optString("password");
        d.profile = o.optString("profile"); d.ptz = o.optString("ptz"); d.talkStream = o.optString("talkStream");
        d.connection = o.optString("connection", "onvif"); d.host = o.optString("host");
        d.sdkPort = o.optInt("sdkPort", 8000); d.sdkChannel = o.optInt("sdkChannel", 0);
        d.sdkStream = o.optInt("sdkStream", 1); d.voiceChannel = o.optInt("voiceChannel", 1);d.voiceCodec=o.optInt("voiceCodec",0);d.voiceToCamera=o.optBoolean("voiceToCamera",false);
        d.id = o.optString("id", java.util.UUID.nameUUIDFromBytes((d.name + "|" + d.host + "|" + d.stream).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        return d;
    }
    static void validateUrl(String value, boolean rtsp) throws Exception {
        URI uri = new URI(value);
        String scheme = uri.getScheme();
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null ||
            !(rtsp ? "rtsp".equalsIgnoreCase(scheme) : "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException(rtsp ? "请输入有效 RTSP 地址，账号密码请填在独立输入框" : "请输入有效 HTTP/HTTPS 设备服务地址");
        if (uri.getPort() > 65535 || uri.getPort() == 0) throw new IllegalArgumentException("端口无效");
    }
}
