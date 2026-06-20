#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/battery_stats
CSV="$DATA_DIR/samples.csv"
APP_CSV="$DATA_DIR/app_usage.csv"
PROC_REQ="$DATA_DIR/process_watch"
PROC_TOP="$DATA_DIR/process_top.csv"
PID="$DATA_DIR/daemon.pid"
ENABLED="$DATA_DIR/enabled"
REALTIME_REQ="$DATA_DIR/realtime_page"
APP_PKG="com.codex.batterystats"
GAUGE_DIR="/proc/oplus-votable/GAUGE_UPDATE"
REALTIME_PID=""
PROC_MON_PID=""

mkdir -p "$DATA_DIR"
[ -f "$ENABLED" ] || echo 1 > "$ENABLED"
[ "$(cat "$ENABLED" 2>/dev/null)" = "0" ] && exit 0
echo $$ > "$PID"

set_gauge_update() {
  [ -d "$GAUGE_DIR" ] || return 0
  chmod 666 "$GAUGE_DIR/force_val" "$GAUGE_DIR/force_active" 2>/dev/null
  if [ "$1" = "1" ]; then
    echo 1000 > "$GAUGE_DIR/force_val" 2>/dev/null
    echo 1 > "$GAUGE_DIR/force_active" 2>/dev/null
  else
    echo 0 > "$GAUGE_DIR/force_active" 2>/dev/null
  fi
}

cleanup() {
  set_gauge_update 0
  [ -n "$REALTIME_PID" ] && kill "$REALTIME_PID" 2>/dev/null
  [ -n "$PROC_MON_PID" ] && kill "$PROC_MON_PID" 2>/dev/null
}

trap cleanup EXIT TERM INT

read_node() {
  for p in "$@"; do
    if [ -r "$p" ]; then
      v="$(cat "$p" 2>/dev/null)"
      if [ -n "$v" ]; then
        echo "$v"
        return
      fi
    fi
  done
  echo 0
}

norm_voltage() {
  awk -v v="$1" 'BEGIN { if (v < 0) v=-v; if (v > 100000) printf "%.6f", v/1000000; else if (v > 100) printf "%.6f", v/1000; else printf "%.6f", v; }'
}

norm_current() {
  awk -v v="$1" 'BEGIN {
    sign=(v<0?-1:1); a=v; if (a<0) a=-a;
    c[1]=a/1000000; c[2]=a/1000000000; c[3]=a/1000; c[4]=a;
    for (i=1;i<=4;i++) if (c[i]>=0.02 && c[i]<=20) { printf "%.6f", sign*c[i]; exit }
    for (i=1;i<=4;i++) if (c[i]>0 && c[i]<=40) { printf "%.6f", sign*c[i]; exit }
    printf "0.000000";
  }'
}

status_code() {
  s="$(read_node /sys/class/power_supply/battery/status)"
  online="$(read_node /sys/class/power_supply/usb/online /sys/class/power_supply/ac/online /sys/class/power_supply/dc/online /sys/class/power_supply/wireless/online)"
  charge_type="$(read_node /sys/class/power_supply/battery/charge_type)"
  level="$(read_node /sys/class/power_supply/battery/capacity)"
  case "$s" in
    Charging) echo 2 ;;
    Discharging) echo 3 ;;
    Full) echo 5 ;;
    Not*) echo 4 ;;
    *)
      if [ "$online" = "1" ] || [ "$charge_type" != "N/A" ] && [ "$charge_type" != "0" ]; then
        if [ "${level:-0}" -ge 100 ]; then echo 5; else echo 2; fi
      else
        echo 3
      fi
      ;;
  esac
}

foreground_pkg() {
  line="$(dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1)"
  echo "$line" | sed -n 's/.*[ /]\([A-Za-z0-9_.-]*\)\/.*/\1/p' | head -n 1
}

screen_on() {
  dumpsys power 2>/dev/null | grep -qE 'mWakefulness=Awake|Display Power: state=ON|mScreenOn=true'
}

realtime_page_requested() {
  [ -f "$REALTIME_REQ" ] || return 1
  flag="$(awk '{print $1; exit}' "$REALTIME_REQ" 2>/dev/null)"
  [ "$flag" = "1" ]
}

realtime_gauge_manager() {
  gauge_active=0
  while true; do
    [ "$(cat "$ENABLED" 2>/dev/null)" = "0" ] && break
    need=0
    if realtime_page_requested && screen_on; then
      fg="$(foreground_pkg)"
      [ "$fg" = "$APP_PKG" ] && need=1
    fi
    if [ "$need" != "$gauge_active" ]; then
      set_gauge_update "$need"
      gauge_active="$need"
    fi
    sleep 2
  done
  set_gauge_update 0
}

trim_csv() {
  lines="$(wc -l < "$CSV" 2>/dev/null)"
  if [ "${lines:-0}" -gt 20000 ]; then
    tail -n 16000 "$CSV" > "$CSV.tmp" && mv "$CSV.tmp" "$CSV"
  fi
  app_lines="$(wc -l < "$APP_CSV" 2>/dev/null)"
  if [ "${app_lines:-0}" -gt 40000 ]; then
    tail -n 32000 "$APP_CSV" > "$APP_CSV.tmp" && mv "$APP_CSV.tmp" "$APP_CSV"
  fi
}

cpu_snapshot() {
  out="$1"
  : > "$out"
  for stat in /proc/[0-9]*/stat; do
    pid="${stat%/stat}"
    pid="${pid##*/}"
    cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | awk '{print $1}')"
    case "$cmd" in
      ""|kworker*|migration*|rcu*|irq/*|kswapd*|system_server|surfaceflinger|android.hardware.*) continue ;;
    esac
    case "$cmd" in
      *:*) pkg="${cmd%%:*}" ;;
      *) pkg="$cmd" ;;
    esac
    case "$pkg" in
      *.*) ;;
      *) continue ;;
    esac
    set -- $(cat "$stat" 2>/dev/null)
    ticks=$(( $14 + $15 ))
    echo "$pkg $ticks" >> "$out"
  done
}

process_monitor() {
  prev="$DATA_DIR/process_prev.tmp"
  while true; do
    [ "$(cat "$ENABLED" 2>/dev/null)" = "0" ] && exit 0
    if [ ! -f "$PROC_REQ" ]; then
      sleep 2
      continue
    fi
    req="$(cat "$PROC_REQ" 2>/dev/null)"
    watch_pid="${req%%|*}"
    watch_rest="${req#*|}"
    watch_pkg="${watch_rest%%|*}"
    watch_name="${watch_rest#*|}"
    [ "$watch_name" = "$watch_rest" ] && watch_name=""
    case "$watch_pid" in ''|*[!0-9]*) watch_pid=0 ;; esac
    if [ ! -d "/proc/$watch_pid" ]; then
      np=""
      if [ -n "$watch_pkg" ]; then
        np="$(pidof "$watch_pkg" 2>/dev/null | awk '{print $1; exit}')"
      fi
      if [ -z "$np" ] && [ -n "$watch_name" ]; then
        np="$(pidof "$watch_name" 2>/dev/null | awk '{print $1; exit}')"
      fi
      if [ -z "$np" ]; then
        for x in /proc/[0-9]*; do
          [ -r "$x/cmdline" ] || continue
          cmd="$(tr '\0' ' ' < "$x/cmdline" 2>/dev/null | awk '{print $1}')"
          [ -z "$cmd" ] && cmd="$(cat "$x/comm" 2>/dev/null)"
          if [ -n "$watch_pkg" ]; then
            case "$cmd" in "$watch_pkg"|"$watch_pkg":*) np="${x##*/}"; break ;; esac
          fi
          if [ -n "$watch_name" ] && [ "$cmd" = "$watch_name" ]; then
            np="${x##*/}"
            break
          fi
        done
      fi
      [ -n "$np" ] && watch_pid="$np"
    fi
    if [ ! -d "/proc/$watch_pid" ]; then
      echo "END|$watch_pid|$watch_pkg" > "$PROC_TOP.tmp"
      mv "$PROC_TOP.tmp" "$PROC_TOP"
      chmod 0644 "$PROC_TOP" 2>/dev/null
      sleep 1
      continue
    fi

    total="$(awk '/^cpu / {print $2+$3+$4+$5+$6+$7+$8; exit}' /proc/stat 2>/dev/null)"
    cores="$(grep -c '^cpu[0-9]' /proc/stat 2>/dev/null)"
    [ -n "$cores" ] || cores=1
    rss="$(awk '/VmRSS:/ {print $2; exit}' "/proc/$watch_pid/status" 2>/dev/null)"
    [ -n "$rss" ] || rss=0
    proc_line="$(cat "/proc/$watch_pid/stat" 2>/dev/null)"
    proc_right="${proc_line#*) }"
    set -- $proc_right
    proc_ticks=$(( ${12:-0} + ${13:-0} ))
    ps_cpu="$(ps -p "$watch_pid" -o %CPU= 2>/dev/null | head -n 1 | tr -d ' %')"
    [ -n "$ps_cpu" ] || ps_cpu="$(ps -p "$watch_pid" -o PCPU= 2>/dev/null | head -n 1 | tr -d ' %')"
    [ -n "$ps_cpu" ] || ps_cpu=0

    old_total=0
    old_proc=0
    if [ -f "$prev" ]; then
      old_total="$(awk -v k="TOTAL" '$1==k {print $2; exit}' "$prev" 2>/dev/null)"
      old_proc="$(awk -v k="PROC" '$1==k {print $2; exit}' "$prev" 2>/dev/null)"
    fi
    calc_cpu=0
    if [ "${old_total:-0}" -gt 0 ] && [ "$total" -gt "$old_total" ] && [ "$proc_ticks" -ge "${old_proc:-0}" ]; then
      calc_cpu="$(awk -v d="$((proc_ticks-old_proc))" -v t="$((total-old_total))" -v c="$cores" 'BEGIN { printf "%.1f", d*c*100/t }')"
    fi
    awk -v a="$calc_cpu" -v b="$ps_cpu" 'BEGIN { if (a+0 > 0) printf "%.1f", a; else printf "%.1f", b+0 }' > "$DATA_DIR/process_cpu.tmp"
    proc_cpu="$(cat "$DATA_DIR/process_cpu.tmp" 2>/dev/null)"

    {
      echo "META|$watch_pid|$watch_pkg|$rss|$proc_cpu|$cores"
      ps -T -p "$watch_pid" -o TID,%CPU,PSR,COMM 2>/dev/null | while read tid pcpu psr comm rest; do
        [ "$tid" = "TID" ] && continue
        [ -z "$tid" ] && continue
        echo "PSTH|$tid|$pcpu|$psr|$comm"
      done
      for t in /proc/$watch_pid/task/[0-9]*; do
        [ -d "$t" ] || continue
        tid="${t##*/}"
        line="$(cat "$t/stat" 2>/dev/null)" || continue
        right="${line#*) }"
        set -- $right
        ticks=$(( ${12:-0} + ${13:-0} ))
        cpu="${37:--1}"
        comm="$(cat "$t/comm" 2>/dev/null)"
        allow="$(awk '/Cpus_allowed_list:/ {print $2; exit}' "$t/status" 2>/dev/null)"
        old_ticks="$(awk -v k="$tid" '$1==k {print $2; exit}' "$prev" 2>/dev/null)"
        th_cpu=0
        if [ "${old_total:-0}" -gt 0 ] && [ "$total" -gt "$old_total" ] && [ "$ticks" -ge "${old_ticks:-0}" ]; then
          th_cpu="$(awk -v d="$((ticks-old_ticks))" -v t="$((total-old_total))" -v c="$cores" 'BEGIN { printf "%.1f", d*c*100/t }')"
        fi
        echo "TH|$tid|$th_cpu|$cpu|$allow|$comm|$ticks"
      done
    } > "$PROC_TOP.tmp"
    mv "$PROC_TOP.tmp" "$PROC_TOP"
    chmod 0644 "$PROC_TOP" 2>/dev/null
    {
      echo "TOTAL $total"
      echo "PROC $proc_ticks"
      for t in /proc/$watch_pid/task/[0-9]*; do
        [ -d "$t" ] || continue
        tid="${t##*/}"
        line="$(cat "$t/stat" 2>/dev/null)" || continue
        right="${line#*) }"
        set -- $right
        echo "$tid $(( ${12:-0} + ${13:-0} ))"
      done
    } > "$prev.tmp"
    mv "$prev.tmp" "$prev"
    sleep 1
  done
}

write_app_usage() {
  old="$1"
  new="$2"
  ts="$3"
  dt_ms="$4"
  wh="$5"
  fg="$6"
  tmp="$DATA_DIR/app_delta.tmp"
  awk 'NR==FNR {old[$1]+=$2; next} {d=$2-old[$1]; if (d>0) print $1,d}' "$old" "$new" > "$tmp"
  total_ticks="$(awk '{s+=$2} END {print s+0}' "$tmp")"
  if [ "${total_ticks:-0}" -le 0 ]; then
    if [ -n "$fg" ]; then
      echo "$ts,$fg,$dt_ms,0,$wh,0,0" >> "$APP_CSV"
    fi
    return
  fi
  awk -v ts="$ts" -v dt="$dt_ms" -v wh="$wh" -v fg="$fg" -v total="$total_ticks" '
    {
      pkg=$1
      ticks=$2
      share=wh*ticks/total
      if (pkg==fg) {
        printf "%s,%s,%d,0,%.8f,0,%d\n", ts,pkg,dt,share,ticks
      } else {
        printf "%s,%s,0,%d,0,%.8f,%d\n", ts,pkg,dt,share,ticks
      }
    }
  ' "$tmp" >> "$APP_CSV"
}

if [ ! -f "$CSV" ]; then
  echo "time_ms,level,status,current_a,voltage_v,power_w,temp_c,screen_on,pkg" > "$CSV"
fi
if [ ! -f "$APP_CSV" ]; then
  echo "time_ms,pkg,fg_ms,bg_ms,fg_wh,bg_wh,cpu_ticks" > "$APP_CSV"
fi

cached_pkg=""
cached_pkg_at=0
last_ms=0
last_power=0
last_status=0
last_cpu_ms=0
pending_wh=0
prev_cpu="$DATA_DIR/cpu_prev.tmp"
next_cpu="$DATA_DIR/cpu_next.tmp"
cpu_snapshot "$prev_cpu"
process_monitor &
PROC_MON_PID="$!"
realtime_gauge_manager &
REALTIME_PID="$!"

while true; do
  [ "$(cat "$ENABLED" 2>/dev/null)" = "0" ] && exit 0
  now_s="$(date +%s)"
  now_ms="${now_s}000"
  level="$(read_node /sys/class/power_supply/battery/capacity)"
  status="$(status_code)"
  raw_current="$(read_node /sys/class/power_supply/battery/current_now /sys/class/power_supply/bms/current_now /sys/class/power_supply/maxfg/current_now)"
  raw_voltage="$(read_node /sys/class/power_supply/battery/voltage_now /sys/class/power_supply/bms/voltage_now)"
  raw_temp="$(read_node /sys/class/power_supply/battery/temp)"
  current="$(norm_current "$raw_current")"
  voltage="$(norm_voltage "$raw_voltage")"
  temp="$(awk -v t="$raw_temp" 'BEGIN { if (t > 1000 || t < -1000) printf "%.1f", t/1000; else printf "%.1f", t/10; }')"
  power="$(awk -v c="$current" -v v="$voltage" -v s="$status" 'BEGIN { if (c < 0) c=-c; p=c*v; max=(s==2 || s==5) ? 100 : 35; if (p > max) p=max; if (p < 0) p=0; printf "%.6f", p; }')"

  if [ $((now_s - cached_pkg_at)) -ge 60 ] || [ -z "$cached_pkg" ]; then
    cached_pkg="$(foreground_pkg)"
    cached_pkg_at="$now_s"
  fi

  if screen_on; then
    is_screen_on=1
    cpu_interval=30000
  else
    is_screen_on=0
    cpu_interval=180000
  fi

  echo "$now_ms,$level,$status,$current,$voltage,$power,$temp,$is_screen_on,$cached_pkg" >> "$CSV"
  if [ "$last_ms" != "0" ]; then
    dt_ms=$((now_ms - last_ms))
    if [ "$dt_ms" -gt 0 ] && [ "$last_status" = "3" ]; then
      pending_wh="$(awk -v old="$pending_wh" -v p="$last_power" -v dt="$dt_ms" 'BEGIN { printf "%.8f", old + p*dt/3600000 }')"
    fi
  fi

  if [ "$status" = "3" ] && [ $((now_ms - last_cpu_ms)) -ge "$cpu_interval" ]; then
    cpu_snapshot "$next_cpu"
    if [ "$last_cpu_ms" != "0" ]; then
      write_app_usage "$prev_cpu" "$next_cpu" "$now_ms" $((now_ms - last_cpu_ms)) "$pending_wh" "$cached_pkg"
    fi
    mv "$next_cpu" "$prev_cpu"
    last_cpu_ms="$now_ms"
    pending_wh=0
  fi

  last_ms="$now_ms"
  last_power="$power"
  last_status="$status"
  chmod 0644 "$CSV" 2>/dev/null
  chmod 0644 "$APP_CSV" 2>/dev/null
  trim_csv

  if [ "$status" = "2" ] || [ "$status" = "5" ]; then
    if [ "$is_screen_on" = "1" ]; then sleep 5; else sleep 15; fi
  else
    if [ "$is_screen_on" = "1" ]; then sleep 10; else sleep 30; fi
  fi
done
