# v0.20.39 公开发布验证

日期：2026-10-25。代码发布分支：`codex/public-release-0.20.39`。

## 构建与测试

使用 JDK 17、Android SDK 36、AGP 8.9.1、Kotlin 2.1.20。本机 Gradle wrapper 分发下载的重定向域名 DNS 失败，因此本次调用本机已缓存的 Gradle 8.14.3，未修改项目 wrapper 8.11.1。

执行：`gradle test :watch-app:assembleDebug :mobile-app:assembleDebug --console=plain --no-daemon`。

结果：`BUILD SUCCESSFUL in 11m 7s`，350 个任务（257 执行、81 来自缓存、12 已是最新）。

| 模块 | 配置累计测试次数 | 失败 | 错误 |
|---|---:|---:|---:|
| nav-core | 36 | 0 | 0 |
| sync-protocol | 4 | 0 | 0 |
| brouter-engine | 4 | 0 | 0 |
| map-mapsforge | 4 | 0 | 0 |
| package-manager | 28 | 0 | 0 |
| session | 60 | 0 | 0 |
| watch-app | 36 | 0 | 0 |
| mobile-app | 12 | 0 | 0 |
| 合计 | 184 | 0 | 0 |

Android 模块合计包含 Debug / Release 配置对同一用例的执行，不能解释为 184 个不同用例。测试覆盖 GPX 扩展、导航进度、到达围栏、山地高程、盲区推算、连续路线拼接、起点选择与同步/下载等逻辑。

构建存在 Android API 弃用提示、SDK XML schema 警告及原生库符号无法剥离提示，未导致测试或打包失败。

## APK 与签名

| 文件 | 包名 | versionName | versionCode |
|---|---|---|---:|
| 手表 APK | com.watchnav.app | 0.20.39 | 64 |
| 手机 APK | com.watchnav.app | 0.20.26-companion | 10049 |

发布脚本使用 `aapt dump badging` 和 `apksigner verify --print-certs` 检查包名、版本与签名。原有侧载签名证书 SHA-256：`b95f9ee91974647b0401d04fbd0d10c68818d3f751fc1b44a0c3db070acb005d`，与既有 v0.20.29 手表包一致；沿用已有本地证书，不将私钥加入仓库。

每个上传附件的实际字节哈希见 Release 的 `SHA256SUMS.txt`；APK 元数据见 `APK_MANIFEST.json`。

## 公开内容与资料

- 已检查所有原有 Git 提交及本次文本文件的常见私钥、GitHub token、Google API key 特征，未发现命中；此扫描不是对未知格式秘密的保证。
- 个人活动 GPX、标记数据库、录音、照片、设备诊断日志和本地 SDK 配置均未纳入发布提交。
- BRouter 必需源码及 profiles 从原构建依赖复制到仓库内，记录上游提交与 MIT 许可证，完整构建已验证新路径。
- 公开地图附件包含可分发路网和卫星样本；OpenAndroMaps 原始地图因捆绑许可限制保留本地，文档引导用户从官网自行取得。
- 后续文档图片更新保留环形路线，将非环形路径、位置圆点和具体起点名称替换为随机演示数据；图像编辑后的公开图片不再作为原始真机证据使用。APK 不变，离线文档压缩包及哈希随图片更新重新生成。
- 两个发布 PowerShell 脚本语法解析通过，并在实际准备/发布流程中使用。

## 真机验证范围

本次是公开发布整理、源码构建和自动测试，未连接手机/手表进行 2026-10-25 新的安装或户外测试。手册图片来自此前真机回测，并明确标注版本差异。2026-08-17 的 v0.20.39 记录包含覆盖安装（code 64）、起点目标文件与后台目标日志核验，以及无崩溃/ANR 的短时回测；不能据此推断所有设备及长时户外续航均通过。
