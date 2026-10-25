# WatchNav v0.20.39 — 手机与 Wear OS 离线导航

公开发布整理：2026-10-25。手表 `0.20.39 / code 64`；手机 `0.20.26-companion / code 10049`。

本应用源于中国大陆国行三星 Watch 的独立离线地图需求，需要自行侧载。请把 `phone` APK 安装到手机，把 `watch` APK 安装到 Wear OS 手表。

## 下载与安装

- `WatchNav-watch-v0.20.39.apk`：手表端。
- `WatchNav-phone-v0.20.26-companion.apk`：手机端，Android 11+、arm64。
- `E120_N30-20260816.rd5`：BRouter 路网分片快照。
- `sentinel2-2025-test.mbtiles`：小范围卫星地图样本，EOX / Copernicus，CC BY-NC-SA 4.0。
- `WatchNav-docs-v0.20.39.zip`：离线图文手册、技术架构及重要日志。
- `SHA256SUMS.txt`：下载文件完整性校验。

[图文安装与使用手册](https://github.com/yzgpk2002/watchnav/blob/codex/public-release-0.20.39/docs/USER_MANUAL_ZH.md) · [地图来源与许可](https://github.com/yzgpk2002/watchnav/blob/codex/public-release-0.20.39/docs/MAP_RESOURCES.md) · [完整改进日志](https://github.com/yzgpk2002/watchnav/blob/codex/public-release-0.20.39/CHANGELOG.md)

![手机路线工坊](https://raw.githubusercontent.com/yzgpk2002/watchnav/codex/public-release-0.20.39/docs/images/phone-routes.png)

## 本次累计重要改进

- 到达检测独立于路线匹配；未按规划路径抵达也可完成导航。路线接驳固定到 GPX 声明的第一个点，修复错误接入第 92/100 点及到达仍显示 2.2 km 的问题。
- 原始 GPX 与临时接驳分开保存，重开路线按最新位置重算；拼接使用连续距离轴。
- 导航显示实际偏航距离，超过 60 m 红字、声音和振动告警；船位图标缩小，边缘滑动可缩放。
- GPX 航点、Via/Shaping、转向和多种环境/传感器扩展解析及往返保留。
- 山地定位匹配、高程融合、盲区步数推算及坡度 ETA 改进。
- 手机与手表双向资料管理、Wear/LAN 同步、录音回传与手机离线转录。
- 公开工程内置所需 BRouter 源码与许可证，补齐手机和手表的图文侧载手册。

## 使用边界

当前提供保留原签名的 Debug 侧载包。源码和构建版本以本 Release 标签为准。手表与手机同包名但用于不同设备，勿装反。

558 MiB OpenAndroMaps 等高线备份未捆绑公开附件：其来源许可要求随应用捆绑前取得许可，用户应从官网自行下载并导入。路网分片和卫星样本不是全球地图。

接驳到 GPX 起点后会结束第一阶段；在起点再次启动可继续沿 GPX。第三方路网的门禁、通行和海拔数据可能不完整。海图/潮汐/活性估算不替代现场判断。

详细验证记录见仓库 `docs/RELEASE_VALIDATION.md`。历史真机截图不代表本次新增户外回测。
