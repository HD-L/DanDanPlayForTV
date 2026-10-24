#!/bin/bash
# 读取电视当前手机表单的地址，并以 JSON 提交表单值。用法: submit-form.sh '<values json>'
set -e
cd "$(dirname "$0")"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb exec-out cat /sdcard/ui.xml > ui.xml
url=$(python3 - <<'PY'
import re
s=open('ui.xml',encoding='utf-8').read()
m=re.search(r'http://[0-9.]+:\d+/input/[0-9a-f-]+\?token=[A-Za-z0-9_-]+', s.replace('&amp;','&'))
print(m.group(0) if m else '')
PY
)
[ -n "$url" ] || { echo "屏幕上没有找到表单地址"; exit 1; }
port=$(echo "$url" | sed -E 's#http://[0-9.]+:([0-9]+)/.*#\1#'); id=$(echo "$url" | sed -E 's#.*/input/([0-9a-f-]+)\?.*#\1#'); token=$(echo "$url" | sed -E 's#.*token=##')
adb forward tcp:$port tcp:$port >/dev/null
echo "表单: $id 端口: $port"
curl -s -X POST "http://127.0.0.1:$port/api/input" -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d "{\"id\":\"$id\",\"values\":$1}"
echo
