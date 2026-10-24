#!/bin/bash
# 文件桥：在 Mac 上运行，循环执行 bridge/queue/*.cmd 里的命令（每个文件一条 shell 命令），
# 把标准输出写到同名 .out，并把退出码写到 .done。用于让云端会话通过共享目录操作 adb / 模拟器。
cd "$(dirname "$0")"
mkdir -p queue
echo "tv-bridge 已启动，监视 $(pwd)/queue，按 Ctrl+C 退出"
while true; do
  for f in queue/*.cmd; do
    [ -e "$f" ] || continue
    base="${f%.cmd}"
    [ -e "$base.done" ] && continue
    cmd=$(cat "$f")
    echo "$(date '+%H:%M:%S') 执行: $cmd"
    bash -c "$cmd" > "$base.out" 2>&1
    echo $? > "$base.done"
  done
  sleep 1
done
