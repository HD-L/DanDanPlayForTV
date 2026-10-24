#!/usr/bin/env python3
"""测试用资源搜索节点：实现 /list /type /subgroup 协议，只返回开放授权的测试种子（Blender 基金会作品，CC-BY）。"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

TRACKERS = "&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=wss%3A%2F%2Ftracker.webtorrent.dev&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F"
RES = [
    {"Title": "[开放电影] Sintel 1080p（测试第 01 集）", "Magnet": "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel" + TRACKERS, "TypeName": "动画", "SubgroupName": "Blender 基金会", "FileSize": "129.2MB", "PublishDate": "2010-09-27"},
    {"Title": "[开放电影] Big Buck Bunny（测试第 02 集）", "Magnet": "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny" + TRACKERS, "TypeName": "动画", "SubgroupName": "Blender 基金会", "FileSize": "263.8MB", "PublishDate": "2008-05-30"},
    {"Title": "[开放电影] Tears of Steel（测试）", "Magnet": "magnet:?xt=urn:btih:209c8226b299b308beaf2b9cd3fb49212dbd13ec&dn=Tears+of+Steel" + TRACKERS, "TypeName": "电影", "SubgroupName": "Blender 基金会", "FileSize": "571.9MB", "PublishDate": "2012-09-26"},
    {"Title": "[测试字幕组] 示例 第 03 集 无效磁链（应被过滤）", "Magnet": "magnet:?xt=urn:btih:BADHASH", "TypeName": "动画", "SubgroupName": "测试字幕组"},
]

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path == "/list":
            kw = q.get("keyword", [""])[0].lower()
            words = [w for w in kw.split() if w]
            items = [r for r in RES if any(w in r["Title"].lower() for w in words) or not words or "测试" in kw or "sintel" in kw or "芙莉莲" in kw]
            body = {"HasMore": False, "Resources": items}
        elif u.path == "/type":
            body = {"Types": [{"Id": 1, "Name": "动画"}, {"Id": 2, "Name": "电影"}]}
        elif u.path == "/subgroup":
            body = {"Subgroups": [{"Id": 1, "Name": "Blender 基金会"}]}
        else:
            self.send_response(404); self.end_headers(); return
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(200); self.send_header("Content-Type", "application/json; charset=utf-8"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def log_message(self, *a): pass

HTTPServer(("0.0.0.0", 8767), H).serve_forever()
