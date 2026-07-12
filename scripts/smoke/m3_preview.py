"""M3 预览管线:图片缩略图、视频封面/时长、Office 转 PDF、文本/PDF 直出、列表缩略图。

测试媒体用 ffmpeg 现场生成(宿主需装 ffmpeg),RTF 内容随机化——派生物按源
hash 全站去重,固定内容第二次跑会直接命中缓存,转换状态断言就失效了。
"""
import random
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path

from common import gql, register, upload

_, TOKEN = register("m3")
print("== 注册 ==")

tmp = Path(tempfile.mkdtemp(prefix="gopan-m3-"))

def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-v", "quiet", "-y", *args], check=True)

# 生成:PNG、5 秒带音轨 MP4、随机 RTF、TXT、最小 PDF
png = tmp / "pic.png"
ffmpeg("-f", "lavfi", "-i", "color=c=steelblue:s=800x600", "-frames:v", "1", str(png))
mp4 = tmp / "clip.mp4"
ffmpeg("-f", "lavfi", "-i", "testsrc=duration=5:size=320x240:rate=10",
       "-f", "lavfi", "-i", "sine=frequency=440:duration=5",
       "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(mp4))
seed = random.randint(0, 999999)
rtf = tmp / "doc.rtf"
rtf.write_text(r"{\rtf1\ansi Hello gopan #%d \par}" % seed)
txt_data = f"gopan text preview #{seed}\n".encode() * 20
txt = tmp / "note.txt"
txt.write_bytes(txt_data)
pdf = tmp / "mini.pdf"
pdf.write_bytes(b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
                b"trailer<</Root 1 0 R>>\n%%EOF\n")
print("== 测试媒体生成 ==")

ids = {}
for f in (png, mp4, rtf, txt, pdf):
    ids[f.name] = upload(f.name, f.read_bytes(), None, TOKEN)
print("== 五类文件上传完成 ==")

PV = "query($id:ID!){node(id:$id){name preview{kind thumbUrl largeUrl contentUrl status durationSec}}}"

def preview(nid):
    return gql(PV, {"id": nid}, TOKEN)["node"]["preview"]

def wait(nid, cond, sec=45):
    p = None
    for _ in range(sec * 2):
        p = preview(nid)
        if cond(p):
            return p
        time.sleep(0.5)
    raise TimeoutError(p)

def fetch(u):
    return urllib.request.urlopen(u).read()

def fetchable(u):
    try:
        urllib.request.urlopen(u)
        return True
    except Exception:
        return False

# 图片:thumb256/2048 均为 JPEG
p = wait(ids["pic.png"], lambda p: p["thumbUrl"] and fetchable(p["thumbUrl"]))
assert p["kind"] == "IMAGE"
assert fetch(p["thumbUrl"])[:2] == b"\xff\xd8", "thumb256 应为 JPEG"
assert fetch(p["largeUrl"])[:2] == b"\xff\xd8", "thumb2048 应为 JPEG"
print("== IMAGE 缩略图 OK ==")

# 视频:封面 + 时长 5 秒
p = wait(ids["clip.mp4"], lambda p: p["durationSec"] is not None and p["largeUrl"] and fetchable(p["largeUrl"]))
assert p["kind"] == "VIDEO" and p["durationSec"] == 5, p
assert fetch(p["largeUrl"])[:2] == b"\xff\xd8", "封面应为 JPEG"
assert p["contentUrl"], "视频应有直出 contentUrl"
print("== VIDEO 封面/时长 OK ==")

# Office:两种部署都要能跑。没部署 Gotenberg(GOPAN_GOTENBERG_URL 置空)时预览回 UNAVAILABLE
# 且 requestPreview 不入队;部署了才是 惰性触发 → PENDING/RUNNING → DONE,产物是 PDF。
p = preview(ids["doc.rtf"])
assert p["kind"] == "OFFICE", p
if p["status"] == "UNAVAILABLE":
    st = gql("mutation($id:ID!){requestPreview(nodeId:$id){status}}",
             {"id": ids["doc.rtf"]}, TOKEN)["requestPreview"]["status"]
    assert st == "UNAVAILABLE", f"关闭 Office 后 requestPreview 不该入队,got {st}"
    print("== OFFICE 未启用:UNAVAILABLE,前端引导下载(精简档)==")
else:
    assert p["status"] is None, p
    gql("mutation($id:ID!){requestPreview(nodeId:$id){status}}", {"id": ids["doc.rtf"]}, TOKEN)
    p = wait(ids["doc.rtf"], lambda p: p["status"] == "DONE", sec=90)
    assert fetch(p["contentUrl"])[:4] == b"%PDF", "Office 产物应为 PDF"
    print("== OFFICE 转 PDF OK ==")

# 文本直出逐字节;PDF 原文直出
p = preview(ids["note.txt"])
assert p["kind"] == "TEXT" and fetch(p["contentUrl"]) == txt_data
p = preview(ids["mini.pdf"])
assert p["kind"] == "PDF" and fetch(p["contentUrl"])[:4] == b"%PDF"
print("== TEXT/PDF 直出 OK ==")

# 列表路径:children 里图片有乐观缩略图字段
kids = gql("query{children{items{name preview{kind thumbUrl}}}}", token=TOKEN)["children"]["items"]
pics = [k for k in kids if k["name"] == "pic.png"]
assert pics and pics[0]["preview"]["thumbUrl"], "列表应带乐观缩略图 URL"
print("ALL M3 SMOKE PASSED")
