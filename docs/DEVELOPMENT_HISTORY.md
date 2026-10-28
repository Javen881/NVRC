# 历史开发记录（历史描述不代表当前状态）

# NvrClient

Android NVR 客户端，最低 Android 8.0（API 26）。实时预览页集成对讲和云台操作。

## 当前实现

- 底部固定“预览 / 回放 / 摄像机” Tab；摄像机页区分“远程参数配置”与“连接设置 / 选择通道”；前者实际读取和下发海康设备参数，后者保存本机连接。
- 画面右上角直接全屏 / 退出全屏；全屏只展开选中窗口，返回可还原分屏。预览画面不显示任何方向箭头，直接滑动控制八方向；抬手、取消、多指触摸或失焦停止。点开“云台”后也可使用八方向按钮。
- 参考界面的红色标题栏、浅色背景、选中窗口边框；实时预览与远程回放独立页面，支持 1/4/9/16 分屏。
- 海康 8000 SDK 登录、实时预览、云台、G.711/G.722 对讲、按时间回放、录像索引、暂停/倍速及本地录像剪辑。
- 回放日期选择、拖动时间轴定位；录像索引用绿色显示。时间轴当前显示所选定位时间，尚未跟随设备播放时钟滚动。
- 工具栏“截屏”保存当前视频窗口的 PNG；“录屏”可启动系统授权的屏幕录制，保存无声 H.264 MP4，支持海康及 ONVIF/RTSP 的显示画面。录制中按钮显示“停止录屏”，也可从常驻通知停止。Android 10+ 保存到相册 Movies/NvrClient，Android 8/9 保存到应用 Movies 目录；单次最多 60 分钟或约 2GB。
- “录屏”菜单同时保留海康设备原始录像保存（PS 格式，应用 Movies 目录），录制中点击“停止录像”结束。“本地录像 / 导出”可用安卓文件选择器导出到用户目录，PS 不伪装为 MP4。开始前检查存储、停止后校验非空，失败显示具体错误。回放页“快进”支持恢复 1× 和选择 2×/4×/8×，成功收到 SDK 返回后显示速度。
- 系统录屏遵循授权时选择的应用窗口或整屏范围，当前不录制声音；系统停止共享或从最近任务移除应用时会结束保存。
- 设备添加、修改、删除；Android Keystore AES-GCM 加密保存配置与凭据。
- RTSP/TCP 实时视频播放（LibVLC）、声音开关、重试和全屏。
- ONVIF Media 通道读取与视频地址获取，WS-Security PasswordDigest、HTTP Basic/Digest 认证。
- 预览页方向、变焦长按控制；松手、触摸取消、窗口失焦、切后台时发出 Stop；ONVIF 移动附带 1 秒设备端超时。
- 预览页点击对讲、再次点击结束：申请麦克风权限，8kHz PCM 采集，G.711 A-law/μ-law 编码，通过 ONVIF RTSP/TCP backchannel 发送。对讲时静音预览避免回声；单次限时 60 秒，点击结束、失焦或切后台停止采集与连接；成功发送首帧后才显示“正在对讲”。
- Android 13+ 返回手势处理、屏幕常亮生命周期、无障碍点击短时云台动作。

## 兼容范围与边界

海康、大华、宇视是兼容目标。目前标准接入路径要求设备已启用 ONVIF/RTSP，并具有相应账号权限。品牌标记不代表所有型号已通过测试。标准对讲要求设备提供 ONVIF 音频回传及 G.711/8000 单声道，缺少该能力时会明确报错。HTTP 为兼容局域网设备开放；跨网访问应使用受控网络。

海康 8000 为厂商 SDK 设备服务端口；已集成官方 HCNetSDK V6.1.9.5 Android 二进制，支持 ARM64 / ARMv7。选择“海康 SDK”，填写 IP、端口和账号；通道 0 自动选首通道，也可指定设备 SDK 通道号。SDK 对讲会读取设备当前格式，支持 G.711 A-law/μ-law 8kHz 和 G.722 16kHz。部分固定 G.722 的设备不提供编码读取接口，客户端会按海康约定自动使用 G.722。SDK 来源见 `vendor/hikvision/README.md`。

GB/T 28181 的 SIP 注册、目录查询及媒体接入尚未实现。可以播放已有国标平台输出的 RTSP 地址，但这不等于完成国标接入。大华/宇视私有 SDK、标准 ONVIF 录像回放、自动局域网发现仍未实现。当前远程回放仅对海康 SDK 设备开放。

## 使用标准接入

1. 添加设备，选择 ONVIF / RTSP。
2. 填写设备的 ONVIF 服务地址（如 `http://<camera-host>/onvif/device_service`）及账号密码。
3. 点击“连接设备并选择通道”，选择需要的通道/码流，然后保存；也可直接填写不带账号密码的 RTSP 地址。
4. 打开“实时预览”。云台需要读取到 PTZ 地址和 ProfileToken；对讲默认使用同一 RTSP 地址，也可在设备配置中指定单独地址。

## 构建和验证

```bash
# 先设置 JAVA_HOME 和 Android SDK 路径，详见 README
cd NvrClient
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
./tests/run.sh
```

APK 按 `NvrClient-v<版本号>-<构建变体>.apk` 命名，位于 `app/build/outputs/apk/debug/`。本机 Google Maven 曾发生 TLS 下载失败，当次使用临时 Google 仓库镜像完成依赖下载；正式仓库配置仍保留官方地址。

界面测试用 Robolectric 验证页面切换、分屏、全屏退出还原、摄像机配置、对讲配置持久化、未连接及会话关闭后的录像失败反馈和后台恢复，并生成 `app/build/ui-previews/` 页面截图。

协议测试使用本机 HTTP/RTSP 模拟设备，验证认证、通道与流地址读取、云台移动/停止、SDP 能力选择、G.711 全 PCM16 输入范围的量化误差、RTP 数据包与取消连接。模拟测试不能代替真机画面、声音、云台物理动作验证。当前环境没有连接 Android 真机或摄像头。

Android Studio 可打开本目录；其他机器需设置 SDK 路径及 JDK。当前包名 `com.example.nvrclient`，仅开发签名。

## 实现参考

- ONVIF Streaming Specification：https://www.onvif.org/specs/stream/ONVIF-Streaming-Spec.pdf
- 海康端口说明：https://enpinfo.hikvision.com/hkwsen/unzip/20230410194813_20373_doc/GUID-E852C255-D9BD-4288-959E-584EC975B407.html
- LibVLC：https://code.videolan.org/videolan/vlc-android/

录屏前台服务依据 Android 官方 MediaProjection 文档实现：https://developer.android.com/media/grow/media-projection 。自动测试覆盖录屏入口、取消授权、快进菜单及未连接时的提示；屏幕录制编码和设备倍速仍需真机验证。

## v0.4.0 设备联调说明

- 海康原始录像改用官方 Android Demo 的 `NET_DVR_SaveRealData_V30(handle, 1, path)` 保存 PS，旧入口只在不支持时回退；开始后请求关键帧，实际失败保留错误码。录像停止、切后台或关闭会话时结束保存，空文件不会报告保存成功。
- 对讲由按住改为点击开关，修复短点按钮立即取消连接的问题。仍使用 G.711 8kHz；G.722 / AAC 等编码尚未实现，会显示设备返回的编码及通道信息，不能仅改客户端选项来转换设备编码。
- 直连 IPC 默认对讲通道 1。经 NVR 对当前数字摄像机讲话，可启用配置项，按登录返回的数字对讲起始通道加视频通道偏移计算；不支持时明确报错，不静默改向 NVR 本体扬声器。
- 自动读取编码失败且已确认设备使用 G.711 时，可手动选择匹配的 A-law/μ-law。设备端编码的修改仍需在设备自身管理界面完成。
- 本次修正与自动测试不能证明特定摄像机已可录像/发声；需要在目标设备上复测，失败提示可用于后续定位。

SDK API 依据：官方 Android SDK 随包 Developer Guide 第 3.5.16、3.11 节；[SaveRealData_V30](https://open.hikvision.com/hardware/definitions/NET_DVR_SaveRealData_V30.html)；[StartVoiceCom_MR_V30](https://open.hikvision.com/hardware/v2/接口定义/NET_DVR_StartVoiceCom_MR_V30.html)。

## v0.5.0 远程配置与滑动云台

预览保持无方向箭头，滑动超出 18dp 死区开始转动，随拖动方向切换水平、垂直及四个斜向。海康使用 SDK 的 UP_LEFT / UP_RIGHT / DOWN_LEFT / DOWN_RIGHT 指令；ONVIF 同时发送 PanTilt 两个分量。抬手、取消、多指、切后台及失焦均发停止，原有海康本地看门狗和 ONVIF 设备超时继续生效。

“摄像机 → 设备 → 远程参数配置”，或当前预览上方的“远程配置”可进入真实 SDK 配置页：

- 设备信息：型号、序列号、版本、通道、硬盘和报警数量（设备支持 V40 时）。
- 图像：亮度、对比度、饱和度、锐度、日夜模式、电源频率、镜像 / 翻转。
- 画面文字：通道名称、名称显示、时间显示、星期显示。
- 主 / 子码流：视频编码、码流音频编码、分辨率、帧率、码率模式与上限、I 帧间隔。
- 设备时间：读取和设置设备本地时间；网络信息目前只读。

图像、文字、码流配置针对当前 SDK 通道；设备信息、时间和网络针对登录设备本身。通过 NVR 登录时，系统时间不是前端 IPC 的系统时间。码流音频编码也不等于独立对讲编码。

保存前重新读取完整配置，只修改用户改动的字段，保留其它参数；写入后回读，设备调整过的值显示实际结果。读取失败时没有可保存的默认值。未知现有枚举保留，不自动替换为第一个选项。用户需要点击“保存到摄像机”才会下发；单个型号不支持某项时显示 SDK 错误码，不假装配置成功。

目前未实现截图软件中全部远程菜单：用户管理、事件规则、录像计划、磁盘维护和网络写入尚未开放。真实相机兼容性仍需目标设备验证。官方接口依据：随 SDK Android Developer Guide 的 GetDVRConfig / SetDVRConfig 及官方 Demo 的 Test_CAMERAPARAMCFG_EX；[图像参数](https://open.hikvision.com/hardware/structures/NET_DVR_VIDEOEFFECT.html)、[日夜模式](https://open.hikvision.com/hardware/structures/NET_DVR_DAYNIGHT.html)。

## v0.5.1 横屏全屏与独立对讲配置

全屏会切换为传感器横屏并铺满选中预览，退出全屏恢复传感器竖屏；方向变化由 Activity 自身处理，避免为旋转而重建视频会话。

远程配置新增“语音对讲”页，使用海康 `NET_DVR_GET_COMPRESSCFG_AUD`（1058）和 `NET_DVR_SET_COMPRESSCFG_AUD`（1059），与主/子码流中的音频字段分开。G.711 只允许 8kHz或设备默认，G.722 只允许 16kHz或设备默认；保存成功并回读一致后，会把 G.711 类型同步到本机对讲编码。部分设备需要重启摄像机后才应用对讲编码，界面会逐项显示设定值与设备实际回读值。

## v0.5.2 固定 G.722 对讲

海康 SDK 对讲新增原生 G.722 编码：以 16kHz 单声道采集，每 640 个 PCM16 采样调用 SDK 编码为 80 字节后发送。设备不支持读取对讲编码且返回错误 23 时，按海康设备约定自动走 G.722。远程配置返回参数错误 17 时，界面会说明设备使用固定编码，无需继续强制写入。

## v0.5.3 海康默认音频标记兼容

自动读取对讲格式时，兼容部分固定 G.722 摄像机返回编码 `0xFE/0xFF`、采样率 `0` 的占位组合。采样率 `0` 按海康定义表示设备默认值，不再被当作不支持。

## v0.5.4 AAC 对讲

海康当前对讲参数返回 AAC（编码 7/12）时，使用 Android AAC-LC 编码器处理麦克风 PCM，并把编码后的裸 AAC 帧交给 SDK 语音转发接口。采样率标记 0 使用 16kHz 默认值，其余标记按设备回读值采集。

所有远程参数写入后先回读；若固件延迟提交，会在后台短暂重试两次。仍不一致时展示每项“设定值 / 实际值”，不再只显示笼统警告。
