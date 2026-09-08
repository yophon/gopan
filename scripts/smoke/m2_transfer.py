"""M2 传输链路:分片直传 → 服务端校验 → 下载比对;断点续传;秒传;hash 谎报回收。"""
import hashlib
import os
import time
import urllib.request

from common import complete_upload, gql, put, register, upload

_, TOKEN = register("m2")
print("== 注册 ==")

# ---- 分片直传 + verify + 下载逐字节比对 ----
data = os.urandom(12 * 1024 * 1024)
sha = hashlib.sha256(data).hexdigest()
init = gql(
    "mutation($n:String!,$s:String!,$z:Int64!){initUpload(name:$n,sha256:$s,size:$z)"
    "{instant session{id partSize partUrls{partNumber url} uploadedParts status}}}",
    {"n": "big.bin", "s": sha, "z": len(data)},
    TOKEN,
)["initUpload"]
assert not init["instant"], "首传不应秒传"
sess = init["session"]
ps = sess["partSize"]
expect_parts = (len(data) + ps - 1) // ps
assert len(sess["partUrls"]) == expect_parts, (len(sess["partUrls"]), expect_parts)
print(f"== initUpload: {expect_parts} 片 x {ps} ==")

# 只传一片模拟断网,uploadSession 应只签缺失片
first = sess["partUrls"][0]
put(first["url"], data[: ps])
view = gql(
    "query($id:ID!){uploadSession(id:$id){partSize partUrls{partNumber url} uploadedParts status}}",
    {"id": sess["id"]},
    TOKEN,
)["uploadSession"]
assert view["uploadedParts"] == [1], view["uploadedParts"]
assert len(view["partUrls"]) == expect_parts - 1
print("== 断点续传视图 OK ==")

for p in view["partUrls"]:
    n = p["partNumber"]
    put(p["url"], data[(n - 1) * ps : n * ps])
node_id = complete_upload(sess["id"], TOKEN)

# 等异步 verify(校验通过后 downloadUrl 一直可用,session 状态转 done)
for _ in range(60):
    st = gql("query($id:ID!){uploadSession(id:$id){status}}", {"id": sess["id"]}, TOKEN)["uploadSession"]["status"]
    if st == "done":
        break
    time.sleep(0.5)
assert st == "done", f"verify 未完成:{st}"
du = gql("query($id:ID!){node(id:$id){downloadUrl}}", {"id": node_id}, TOKEN)["node"]["downloadUrl"]
assert urllib.request.urlopen(du).read() == data
print("== 上传 + verify + 下载比对 OK ==")

# ---- 秒传:同内容第二次 instant ----
init2 = gql(
    "mutation($n:String!,$s:String!,$z:Int64!){initUpload(name:$n,sha256:$s,size:$z){instant node{id}}}",
    {"n": "copy.bin", "s": sha, "z": len(data)},
    TOKEN,
)["initUpload"]
assert init2["instant"] and init2["node"], init2
print("== 秒传 OK ==")

# ---- hash 谎报:上传的数据与申报 sha 不符 → verify 全链回收 ----
lie_data = os.urandom(64 * 1024)
fake_sha = hashlib.sha256(b"not-this-content").hexdigest()
init3 = gql(
    "mutation($n:String!,$s:String!,$z:Int64!){initUpload(name:$n,sha256:$s,size:$z)"
    "{instant session{id partUrls{partNumber url}}}}",
    {"n": "lie.bin", "s": fake_sha, "z": len(lie_data)},
    TOKEN,
)["initUpload"]
for p in init3["session"]["partUrls"]:
    put(p["url"], lie_data)
try:
    complete_upload(init3["session"]["id"], TOKEN)
except RuntimeError as err:
    assert err.args[0][0]["extensions"]["code"] == "HASH_MISMATCH", err
else:
    raise AssertionError("hash mismatch must never create a node")
for _ in range(60):
    st = gql("query($id:ID!){uploadSession(id:$id){status}}", {"id": init3["session"]["id"]}, TOKEN)["uploadSession"]
    if st["status"] == "failed":
        break
    time.sleep(0.5)
assert st["status"] == "failed", f"谎报应判 failed:{st}"
print("== hash 谎报被识别,未创建节点 ==")

# 谎报回收也退配额:再传一个正常小文件确认账目还能走
upload("after.bin", os.urandom(1024), None, TOKEN)
print("ALL M2 SMOKE PASSED")
