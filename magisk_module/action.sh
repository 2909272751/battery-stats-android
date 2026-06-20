#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/battery_stats
PID="$DATA_DIR/daemon.pid"
ENABLED="$DATA_DIR/enabled"
REALTIME_REQ="$DATA_DIR/realtime_page"
GAUGE_DIR="/proc/oplus-votable/GAUGE_UPDATE"

mkdir -p "$DATA_DIR"

is_running() {
  [ -f "$PID" ] || return 1
  pid="$(cat "$PID" 2>/dev/null)"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null
}

stop_daemon() {
  echo 0 > "$ENABLED"
  echo "0 $(date +%s)" > "$REALTIME_REQ"
  if [ -d "$GAUGE_DIR" ]; then
    echo 0 > "$GAUGE_DIR/force_active" 2>/dev/null
  fi
  if is_running; then
    kill "$(cat "$PID")" 2>/dev/null
  fi
  echo "电池统计: 已暂停采样"
}

start_daemon() {
  echo 1 > "$ENABLED"
  if is_running; then
    echo "电池统计: 已在运行"
    return
  fi
  nohup sh "$MODDIR/service.sh" >/dev/null 2>&1 &
  echo "电池统计: 已启动采样"
}

if is_running; then
  stop_daemon
else
  start_daemon
fi
