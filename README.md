# NVRC / NvrClient

Android 多品牌 NVR 客户端，当前版本 **0.7.2**，最低 Android 8.0（API 26），支持 ARM64 / ARMv7。

## 功能与状态

- 实时预览，1 / 4 / 9 / 16 分屏，选中窗口横屏全屏。
- 预览画面滑动控制八方向云台，松手停止；画面不显示方向箭头。
- 预览页选择主 / 子码流；ONVIF 读取设备 Profile，纯 RTSP 可输入替代地址。
- 截图、系统无声录屏、本地录像导出，设备连接信息由 Android Keystore 加密保存。
- 海康 HCNetSDK：预览、云台、G.711 / G.722 / AAC 对讲、按时间回放、暂停及倍速、PS 原始录像、远程参数配置。
- 大华 NetSDK：已编写预览、云台、G.711A 音频发送和 DAV 原始录像接入代码，尚需实机验证。当前对讲接收回调未播放设备声音，不应视为完整双向对讲。
- 宇视 NetDEVSDK：已编写预览、云台、对讲及原始录像接口，尚需实机验证。
- ONVIF / RTSP：通道读取、预览、PTZ、满足设备能力要求的 G.711 backchannel。

## 已知问题

- 用户反馈海康主码流黑屏，尚未定位或修复；子码流与主码流解码兼容性需实机联调。
- 大华、宇视的远程回放和远程参数配置尚未接入。
- GB28181 SIP 注册、目录查询和媒体接入尚未实现；播放国标平台 RTSP 输出不等于原生国标支持。
- 厂商 SDK 存在同名原生库，当前打包处理不代表所有设备上的运行兼容性已验证。
- 0.6.0 / 0.7.0 曾因错误的 Java 排除规则缺失应用类；0.7.1 已移除该规则。

## 构建

使用 JDK 21、Android SDK（编译 SDK 36.1），在 Android Studio 打开本目录。
设置 `ANDROID_HOME` 或本机 `local.properties` 的 `sdk.dir`，后者不要提交。

**本仓库不包含厂商 SDK、安装包、设备凭据及签名密钥。克隆后须先补齐授权 SDK，才能编译。**
具体目录与依赖见 [SDK 配置](docs/SDK_SETUP.md)。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain
bash tests/run.sh
```

协议测试需要设置 `JAVA_HOME`。Gradle Wrapper 已包含，不需要单独安装 Gradle。
APK 输出为 `app/build/outputs/apk/debug/NvrClient-v0.7.2-debug.apk`。
默认是开发签名；新机器开发签名可能不同，覆盖安装前应保留原有签名并自行安全管理。

## 使用

添加设备时选择海康 SDK（默认 8000）、大华 NetSDK（默认 37777）、宇视 SDK 或 ONVIF / RTSP。
海康通道 0 表示自动首通道，大华通常从 0 开始，宇视通常从 1 开始；以设备配置为准。
预览上方码流菜单可切换当前窗口视频流，录像过程中需先停止设备录像。

## 验证范围

0.7.2 已通过 15 项 Robolectric 测试、Lint 和 APK 构建。模拟测试不能证明厂商原生库的设备兼容性，仍需实际手机和摄像机验证。

历史开发记录见 [开发记录](docs/DEVELOPMENT_HISTORY.md)，以本页列出的当前状态为准。
第三方依赖和厂商许可边界见 [SDK 配置](docs/SDK_SETUP.md)。
