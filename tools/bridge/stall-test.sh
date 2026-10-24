#!/bin/bash
# 真机卡顿测试（不截图）：播放“继续观看”第一项，每秒记录播放状态、位置与 wlan0 接收速度，最后输出 PlaybackNet 日志
# 用法：TV=电视地址:5555 ./stall-test.sh [秒数，默认 150]。电视需已开启网络调试并用 adb connect 连上。
# 注意：测试会真的播放该视频，并把它的观看进度往后推。
# 状态：3=播放 6=缓冲 2=暂停
S=${TV:?请用 TV=电视地址:5555 指定电视}
N=${1:-150}
rx() { adb -s $S shell cat /proc/net/dev | awk '/wlan0/{print $2}'; }
st() { adb -s $S shell dumpsys media_session | awk '/package=tv.dandan.clean/{f=1} f && /state=PlaybackState/{match($0,/state=[0-9]+/); s=substr($0,RSTART+6,RLENGTH-6); match($0,/position=[0-9]+/); p=substr($0,RSTART+9,RLENGTH-9); print s, int(p/1000); exit}'; }
adb -s $S logcat -c
# 播放日志单独写到文件，避免电视日志缓冲区滚动丢掉开头
LOG=$(mktemp); adb -s $S logcat -v time -s PlaybackNet:I > $LOG 2>/dev/null & LP=$!
adb -s $S shell am start -W -n tv.dandan.clean/tv.dandan.app.MainActivity | grep -E 'Status'
sleep 10
adb -s $S shell input keyevent 23
sleep 2
start=$(date +%s); prev=$(rx); pt=$start
while [ $(( $(date +%s) - start )) -lt $N ]; do
  s=$(st); now=$(rx); t=$(date +%s)
  dt=$((t-pt)); [ $dt -lt 1 ] && dt=1
  echo "t=$((t-start)) state/pos=$s rx=$(( (now-prev)/1024/dt ))KB/s"
  prev=$now; pt=$t
  sleep 1
done
adb -s $S shell input keyevent 4; sleep 1.5; adb -s $S shell input keyevent 4; sleep 1
kill $LP 2>/dev/null; cut -c1-300 $LOG | grep PlaybackNet; rm -f $LOG
adb -s $S logcat -d -v time | grep -E 'FATAL|AndroidRuntime: (FATAL|Process: tv.dandan)' | cut -c1-200
