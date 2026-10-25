# 离线地图资源

地图和路网作为 GitHub Release 附件发布，避免大文件进入源码历史。下载后核对同一 Release 的 `SHA256SUMS.txt`。

| 附件 | 用途 | 大小（约） | 来源与限制 |
|---|---|---:|---|
| `WatchNav-contour-from-watch.map`（不随 Release 分发） | 等高线矢量地图，项目原有手表地图备份 | 558 MiB | 文件头确认为 OpenAndroMaps；其许可限制随 App 捆绑分发，须自行从官网取得 |
| `E120_N30-20260816.rd5` | 经度120–125、纬度30–35分片的 BRouter 路网，不能推断其完全覆盖任意路线 | 15.6 MiB | BRouter / OpenStreetMap，保留 ODbL 署名；2026-08-16 下载快照 |
| `sentinel2-2025-test.mbtiles` | 小范围卫星测试样本 | 5.2 MiB | EOX Sentinel-2 cloudless 2025，CC BY-NC-SA 4.0；仅非商业用途 |

资源清单反映本地现存文件。558 MiB 文件头含 `www.openandromaps.org` 和 Christian Kernbeis / Michael Bechtold / Tobias Kuehn 署名。[OpenAndroMaps 许可](https://www.openandromaps.org/en/license) 允许私人使用，但将地图随产品或 App 捆绑需要书面许可。因此该文件保留本地，用户可从 [OpenAndroMaps 官网](https://www.openandromaps.org/en/) 自行选择区域下载，解压后把 `.map` 分享/导入手机，再同步手表。此仓库不提供其地图文件直链。

普通/等高线 `.map` 可从应用配置的地图提供者获取；BRouter `.rd5` 从其分片服务下载；卫星图由应用按范围下载为 MBTiles。相关地址和实现可在 `package-manager` 及双端下载代码中查看。图源数据会随时间更新，路网和地图快照日期应分别记录。

地图、路网和影像是三个独立数据集。手机发送手表后，应在手表核对图层及路网覆盖，完成一次离线预览。手表等高线和影像显示范围按定位附近约 15 km 限制，避免一次绘制整个大文件。

署名：© OpenStreetMap contributors，https://www.openstreetmap.org/copyright 。卫星：Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2025)。
