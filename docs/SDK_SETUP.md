# SDK 与第三方依赖

SDK Java 绑定和原生库需要使用同一版本。SDK 源码、文档、JAR、SO、下载压缩包均不随此仓库发布。

## 海康

当前开发使用 HCNetSDK V6.1.9.5 Android。取得授权包后执行：

```sh
python3 tools/import_hikvision.py /path/to/sdk/libs
```

目标目录：`vendor/hikvision/sdk/libs/*.jar` 和 `vendor/hikvision/sdk/jniLibs/{arm64-v8a,armeabi-v7a}/*.so`。
详见 [来源说明](../vendor/hikvision/README.md)。

## 宇视

当前使用 NetDEVSDK Android V2.7.0.1。
目标根目录：`vendor/uniview/sdk/NETDEVSDK_Android_V2.7.0.1/`。
保留 `lib/{arm64-v8a,armeabi-v7a}/*.so` 和 `demo/app/src/main/java/com/sdk/*.java`。
不要将示例 Activity 放入 Java 编译目录。两个告警回调文件中未使用的 `com.example.netsdk_demo.CAlarmReport` 导入需要删除。
详见 [来源说明](../vendor/uniview/README.md)。

## 大华

内部测试依赖来源为 https://github.com/faradzhalelov/dahua_net_sdk ，参考树 SHA 为 `fd3006c6bbeb7f783579d25a51ff0433c90b67f8`。
目标根目录：`vendor/dahua/dahua_net_sdk/android/`，保留 `libs/*.jar` 和 `src/main/jniLibs/{arm64-v8a,armeabi-v7a}/*.so`。
应用直接调用 JAR，不编译 Flutter 包装层。
当前打包排除了大华 arm64 的 `libcrypto.so`、`libssl.so` 和 armv7 的 `libgnustl_shared.so`，详见 [依赖说明](../vendor/dahua/README.md)。
该处理只解决打包重名，仍需验证运行期依赖兼容性。

## 许可

大华参考仓库的 MIT 许可仅覆盖其包装层，不覆盖厂商 SDK 二进制。
三家厂商 SDK 均遵循各自许可，内部测试取得的包不在本仓库再分发。
LibVLC 通过 Maven 使用 `org.videolan.android:libvlc-all:3.7.0`；发布含库的安装包时应遵守上游许可。
本仓库未额外声明应用代码的开源许可证。
