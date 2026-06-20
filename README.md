# 电池统计

电池统计是一款面向 Root 用户的 Android 功耗观察工具。它把后台采样放在 Root 模块里运行，前台应用只负责读取和展示数据，尽量减少应用本身常驻带来的额外耗电。

## 主要功能

- 充电统计：记录电量、功率、电流、电压、温度变化。
- 耗电统计：统计放电过程、平均功耗、已使用时长、理论续航。
- 应用耗电：区分前台与后台耗电，支持隐藏或显示系统应用，并按时长、耗电排序。
- 进程信息：读取进程 CPU、RES 内存占用，支持搜索与排序。
- 悬浮小窗：可在其他应用上方实时查看单个进程的线程 CPU 占用、运行核心、TID 和线程名。
- Root 守护：模块负责低频采样，支持管理器动作按钮或应用内一键开关。
- 深色模式：跟随系统自动切换。

## 当前版本

`2.0.0`

## 安装方式

1. 安装签名 APK：`battery-stats-v2.0.0-release-signed.apk`
2. 在 Magisk、APatch 或 KernelSU 管理器中刷入模块：`battery-stats-v2.0.0-magisk-module.zip`
3. 重启或在管理器中执行模块动作按钮启动采样。
4. 打开应用并授予 Root 权限。

## 数据与采样

模块默认把采样数据写入：

- `/data/adb/battery_stats/samples.csv`
- `/data/adb/battery_stats/app_usage.csv`
- `/data/adb/battery_stats/process_top.csv`

采样策略会根据充电状态和亮屏状态自动降低频率，避免无意义的频繁唤醒。进程悬浮窗只在用户打开并选择进程后才请求实时数据。

## 构建

```powershell
.\build_apk.ps1
.\build_magisk_module.ps1
```

签名配置通过本地 `signing.properties` 和 `release.keystore` 提供，这两个文件不会提交到仓库。
