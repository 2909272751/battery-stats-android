# 电池统计 Root 模块

该模块在 Root 环境下运行轻量采样脚本，并把电池、应用耗电和进程小窗数据写入：

- `/data/adb/battery_stats/samples.csv`
- `/data/adb/battery_stats/app_usage.csv`
- `/data/adb/battery_stats/process_top.csv`

## 采样策略

- 充电 + 亮屏：5 秒
- 充电 + 熄屏：15 秒
- 放电 + 亮屏：10 秒
- 放电 + 熄屏：30 秒
- 应用 CPU 分摊统计：亮屏约 30 秒，熄屏约 180 秒
- 进程悬浮小窗：用户选择进程后 1 秒刷新

## 智能实时电量计

在支持 `/proc/oplus-votable/GAUGE_UPDATE` 的 OPPO/realme 设备上，模块会按条件临时开启 1 秒电量计刷新：

- 应用在前台
- 屏幕点亮
- 当前页面是总览、耗电或充电

任一条件不满足时，模块会写入 `force_active=0` 恢复低频。即使应用被杀，只要模块检测到应用不在前台，也会自动关闭高频。

## 开关

- 支持 APatch、KernelSU、Magisk 管理器通过模块动作按钮执行 `action.sh`，无需重启即可启停采样。
- 应用内也可以通过 Root 启停采样。
