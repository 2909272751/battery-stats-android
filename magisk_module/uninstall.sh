#!/system/bin/sh
PID=/data/adb/battery_stats/daemon.pid
if [ -f "$PID" ]; then
  kill "$(cat "$PID")" 2>/dev/null
fi
