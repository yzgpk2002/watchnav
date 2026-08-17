# WatchNav

WatchNav 是一套面向 Wear OS 的离线户外导航与垂钓协作应用，由相同业务内核驱动的手表端和 Android 手机伴侣端组成。当前手表版本为 **0.20.35**，手机伴侣版本为 **0.20.26-companion**。

产品原则是“手表独立、手机增强”：离开手机和网络后，手表仍可完成定位、地图显示、步行离线路由、导航、偏航重算、自由探索、TracBack、轨迹保存与垂钓记录；手机负责大屏地图、路线与标点管理、较大数据包下载、长期归档、语音转录和双端同步。

## 当前能力

### Wear OS 手表端

- Mapsforge 普通/等高线矢量地图与 MBTiles 卫星图，地图跟随、平移、缩放和覆盖边界约束。
- BRouter 离线步行规划，自动连接当前位置与路线起点，偏航重规划和终点到达保护。
- 自适应定位与航向融合：位置滤波、低速电子罗盘、步数/位移方向判定、航向滞回、真北方位刻度。
- 轻量导航画面：当前船位、局部前后轨迹、少量分叉路、左/直/右提示、转向距离和剩余距离。
- GPX/KML 路线库、WGS-84/GCJ-02/BD-09 导入纠偏、路线去重/删除、自由探索与历史活动。
- 垂钓指标界面：气压、溶氧估算、潮汐、Solunar 活性、日出日落、抛投统计及语音钉。
- 中文/English 切换、圆屏安全区、禁用系统右滑退出导航、导航结束后自动释放 GNSS/传感器。

### Android 手机伴侣端

- 路线工坊、路线搜索与预览、GPX/KML/KMZ 导入、坐标系转换、重命名/删除和系统分享。
- 普通地图、等高线、BRouter 路网、当前卫星 MBTiles、历史影像和水域资料的下载与管理。
- EOX Sentinel-2 Cloudless 2025 当前卫星底图，1–15 km 选区、z10–z16、断点续传和来源署名。
- 手表资料库：路线、地图、活动、标点、垂钓录音和同步状态长期保存。
- 标点独立资料页：名称、坐标、时间、文字备注、图片和关联录音。
- SenseVoiceSmall int8 手机端离线转录；保留原始 WAV，并把转录结果关联到对应标点备注。
- Google Wear Data Layer 与同一 Wi-Fi 局域网两条同步通道，兼容安全文件夹场景。

### 双端同步

- 版本化实体、revision、tombstone、SHA-256、冲突副本和能力协商。
- 路线、设置、标点、活动摘要、潮汐缓存和垂钓数据双向同步。
- 地图、等高线、路网、卫星图、活动文件和录音等大文件校验传输。
- 大文件临时落盘、空间预留、断点状态、安装回执和旧数据保护。
- 手表仅在用户开启接收窗口时提供局域网发现；会话使用随机令牌，不开放永久上传入口。

## 工程模块

| 模块 | 职责 |
|---|---|
| `nav-core` | 坐标、路线、GPX/KML、空间索引、匹配、进度、转向和海拔分析 |
| `routing-api` | 离线路由接口与请求/结果模型 |
| `brouter-engine` | BRouter 步行路由与 `.rd5` 路网管理 |
| `map-mapsforge` | Mapsforge 图层仓库、渲染、边界/缩放约束和导航叠加 |
| `package-manager` | 地图/路网下载、Range 续传、哈希、签名、安装与回滚 |
| `session` | GNSS/传感器融合、导航服务、自由探索、活动归档和终点状态机 |
| `sync-protocol` | 纯 Kotlin 同步模型、二进制编解码、哈希和冲突策略 |
| `sync-transport` | Wear Data Layer、局域网发现/传输、接收暂存和回执 |
| `watch-app` | Wear OS 圆屏 UI、导航、垂钓、地图下载及手表同步协调器 |
| `mobile-app` | Android 手机 UI、地图/路线/资料库、下载、同步和语音转录 |

完整设计见 [技术架构](docs/TECHNICAL_ARCHITECTURE.md)，版本演进见 [改进日志](CHANGELOG.md)，手机端产品方案见 [手机伴侣设计](docs/MOBILE_COMPANION_APP_DESIGN.md)。

## 构建

要求：JDK 17、Android SDK（compileSdk 36）、可访问 Google/Maven Central。手机端的 SenseVoice 运行库位于 `mobile-app/libs/sherpa-onnx.aar`，识别模型不进入仓库，由手机首次使用时下载并校验 SHA-256。

```powershell
.\gradlew.bat test assembleDebug
```

单独构建：

```powershell
.\gradlew.bat :watch-app:assembleDebug
.\gradlew.bat :mobile-app:assembleDebug
```

输出位置：

- `watch-app/build/outputs/apk/debug/watch-app-debug.apk`
- `mobile-app/build/outputs/apk/debug/mobile-app-debug.apk`

## 真机调试

日常调试默认使用 VMware 桥接网络，在虚拟机内通过 ADB mDNS 发现并直连手表；宿主机 TCP 转发只作为桥接暂时不可用时的兜底。Windows 启动器不依赖 PATH，统一使用系统工具绝对路径并保留窗口输出。

详见 [Windows Wear OS 调试](docs/WINDOWS_WEAR_DIRECT_DEBUG.md)。

## 数据与许可边界

- 用户路线、轨迹、精确钓点、录音和照片默认只保存在本机与配对设备。
- 第三方地图必须保留来源、许可与署名；不读取加密缓存，不绕过商业图源限制。
- EOX 当前卫星层用于非商业场景，遵循 CC BY-NC-SA 4.0 和要求的 Copernicus 署名。
- 海图、水深和公共下钓点仅用于垂钓辅助，不能替代最新官方海图、航行通告或现场安全判断。

## 当前验证基线

- Gradle JVM/Android 单元测试与双端 Debug APK 构建通过。
- Samsung Galaxy Watch7 真机安装、离线地图、离线路由、低速航向、终点识别和服务退出回测通过。
- Android 手机与手表的 Wear/LAN 同步、路线发送、地图/资料接收和录音转录完成联合测试。
- 大区域地图、长时间续航与复杂水域路网仍应在每次正式发布前继续做户外回归。
