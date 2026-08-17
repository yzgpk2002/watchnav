# Windows / VMware 直连 Wear OS 调试

## 1. 默认拓扑

日常调试采用：

```text
手表 Wi-Fi ←→ 局域网 ←→ VMware 桥接网卡 ←→ 虚拟机内 ADB
```

虚拟机直接执行构建、`adb connect`、安装、截图、logcat 和 scrcpy。手表 IP 或 TLS 连接端口变化时，优先由虚拟机中的 ADB mDNS 重新发现，不要求用户反复在宿主机配置转发。Tailscale 出口节点可以继续使用，但必须允许局域网访问。

宿主机 TCP 转发只用于桥接网络暂时不可达时：

```text
手表 ←→ 宿主机监听端口 ←→ VMnet8 NAT 地址 ←→ 虚拟机 ADB
```

## 2. 脚本原则

- `.cmd` 不依赖 PATH，调用 PowerShell 时使用：
  `%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe`
- Windows 系统工具同样优先使用 `%SystemRoot%\System32\...` 绝对路径。
- 需要管理员权限的宿主机启动器主动申请 UAC，不在脚本执行中途才抛出“请以管理员身份运行”。
- 启动器保持窗口打开，显示每一步和日志位置，失败不能一闪而过。
- 路径必须兼容空格、UNC 共享目录、中文 Windows PowerShell 5.1 和 CRLF。
- 调试地址保存在本机用户配置，不硬编码进 Git 仓库。

## 3. 桥接模式：推荐流程

确认虚拟机网卡已桥接到当前 Wi-Fi/以太网网卡，并且虚拟机可以主动访问手表网段。

首次配对：

```powershell
.\tools\WatchNav-WearDebug.ps1 Pair -PairEndpoint IP:配对端口 -PairCode 六位配对码
```

之后优先自动发现：

```powershell
.\tools\WatchNav-WearDebug.ps1 Discover
.\tools\WatchNav-WearDebug.ps1 Status
```

若 mDNS 尚未出现，可临时指定手表“无线调试”页面显示的连接端口：

```powershell
.\tools\WatchNav-WearDebug.ps1 Connect -Endpoint IP:连接端口
```

配对端口与连接端口不同；Wi-Fi 重连后连接端口可能改变，但配对历史通常仍有效。

## 4. 构建、安装与回测

```powershell
.\gradlew.bat :watch-app:assembleDebug
.\tools\WatchNav-WearDebug.ps1 Install -Apk .\watch-app\build\outputs\apk\debug\watch-app-debug.apk
.\tools\WatchNav-WearDebug.ps1 Launch
.\tools\WatchNav-WearDebug.ps1 Screenshot
.\tools\WatchNav-WearDebug.ps1 Logcat
```

也可以直接使用 ADB：

```powershell
adb devices -l
adb -s SERIAL install -r .\watch-app\build\outputs\apk\debug\watch-app-debug.apk
adb -s SERIAL shell am start -n com.watchnav.app/.MainActivity
```

覆盖安装保留应用数据。涉及数据库/文件迁移时，安装前应先拉取必要备份，不能用卸载重装掩盖迁移问题。

## 5. NAT 转发兜底

仅当桥接路由无法恢复时使用：

1. 在宿主机以管理员权限运行 `scripts/windows-adb/Run-Host-Watch-Adb-Forward.cmd`。
2. 宿主机脚本只负责把 VMnet8 上的一个 TCP 监听地址转发到当次手表 `IP:连接端口`，并把防火墙范围限制在 VMware NAT 子网。
3. 虚拟机运行 `scripts/windows-adb/Run-VM-Watch-Adb.cmd`，ADB 连接宿主机 VMnet8 地址和监听端口。
4. 手表连接端口变化时只更新兜底转发；桥接恢复后立即返回虚拟机直连。

转发脚本不能解决手表无线调试未开启、配对失效、路由器客户端隔离或宿主机本身无法访问手表的问题。

## 6. 连接诊断顺序

```powershell
ipconfig
route print
Test-NetConnection WATCH_IP -Port WATCH_PORT
adb mdns services
adb devices -l
```

按顺序判断：

1. 虚拟机是否获得与当前桥接网络匹配的地址和网关。
2. Tailscale 是否允许局域网访问，是否错误接管本地网段。
3. 路由器是否启用 AP/客户端隔离。
4. 手表与电脑是否连接同一可互通 Wi-Fi，而不只是 SSID 名称相似。
5. 无线调试页面中的当前端口是否与尝试连接的端口一致。
6. `adb mdns services` 是否发现 `_adb-tls-connect._tcp`。

## 7. 真机日志与数据取证

导航问题应在修改算法前保留：

- 当前 App 版本与 versionCode。
- `navigation_session.xml` 的进度/剩余状态。
- 最后定位、accuracy、speed、bearing 和时间。
- 当前活动 GPX、原始路线 GPX 与近期面包屑 CSV。
- 崩溃 logcat、ANR、内存、温度和 batterystats。

这些文件只保存到本机 `artifacts/`，该目录已加入 `.gitignore`。不得把用户真实轨迹、设备地址或完整日志推送到源码仓库。

## 8. 结束调试

- 停止仍在运行的测试导航，确认 `dumpsys activity services com.watchnav.app` 不再有导航服务。
- 关闭不需要的 scrcpy/logcat 会话。
- 户外测试结束后按需关闭手表无线调试，降低耗电和局域网暴露。
