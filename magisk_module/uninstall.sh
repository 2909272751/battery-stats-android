#!/system/bin/sh
PID=/data/adb/battery_stats/daemon.pid
GAUGE_DIR=/proc/oplus-votable/GAUGE_UPDATE
if [ -f "$PID" ]; then
  kill "$(cat "$PID")" 2>/dev/null
fi
if [ -d "$GAUGE_DIR" ]; then
  echo 0 > "$GAUGE_DIR/force_active" 2>/dev/null
fi
