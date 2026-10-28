# 海康 Android 设备网络 SDK

接入目标：8000（可修改）端口的 HCNetSDK 私有设备协议，直接连接 IP/域名。

官方新版下载入口：
https://open.hikvision.com/download/5cda567cf47ae80dd41a54b3?type=10

2026-10-26 页面显示 Android `V6.1.11.5_build20251204`，下载要求海康通行证登录。

官方公开旧版入口：
https://www.hikmicrotech.com/en_us/support/download-center/tool/

Android `HCNetSDKV6.1.9.5_build20220614_android_EN.zip`，下载前要求用户接受材料许可协议。

SDK 是厂商提供的独立二进制依赖。需使用同一版本的 Java JAR 与对应 ABI 原生库，不能混用 Windows/Linux SDK 或不同版本的播放库。已完成接入与打包，仍需真机测试设备登录、预览、音频回传、云台停止与生命周期资源释放。

## 本次集成

用户已授权接受 Materials License Agreement 并下载公开版。已采用上述 HIKMICRO 官方公开版本；原始 ZIP 的 MD5 为 `8dde1095295c8d64fad2d968e8f989e5`，与下载页面公布值一致。

SDK 位于 `sdk/`（不提交版本库），包含同版本 HCNetSDK.jar、PlayerSDK_hcnetsdk.jar、jna.jar 和 arm64-v8a / armeabi-v7a 原生库。文件 SHA-256 清单为 `sdk/sha256.json`。构建配置会自动打包这些依赖。迁移机器时需取得授权 SDK，用 `tools/import_hikvision.py` 导入其 libs 目录，避免误选 pdb 调试库。

该旧版本仍需验证目标 Android 系统与具体设备兼容性。Robolectric 界面测试不执行厂商 ARM 原生库。
