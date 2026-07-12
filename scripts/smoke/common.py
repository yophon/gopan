"""smoke 公共件:GraphQL 调用与上传编排。

四套 smoke 都打真实服务(默认 127.0.0.1:8080,GOPAN_API 可指向任意实例),
需要 dev 依赖在跑:make dev-env && make dev-server。
"""
import hashlib
import json
import os
import random
import urllib.error
import urllib.request

API = os.environ.get("GOPAN_API", "http://127.0.0.1:8080/query")
BASE = API.rsplit("/", 1)[0]


def gql(q, v=None, token=None, expect_code=None):
    req = urllib.request.Request(
        API,
        data=json.dumps({"query": q, "variables": v or {}}).encode(),
        headers={"Content-Type": "application/json"},
    )
    if token:
        req.add_header("Authorization", "Bearer " + token)
    r = json.loads(urllib.request.urlopen(req).read())
    if expect_code:
        codes = [e.get("extensions", {}).get("code") for e in r.get("errors") or []]
        assert expect_code in codes, f"期待错误 {expect_code},实际 {r.get('errors')}"
        return None
    if r.get("errors"):
        raise RuntimeError(r["errors"])
    return r["data"]


def http_get(path, expect_status=200):
    try:
        resp = urllib.request.urlopen(BASE + path)
        assert resp.status == expect_status, f"{path}: {resp.status} != {expect_status}"
        return resp.read()
    except urllib.error.HTTPError as e:
        assert e.code == expect_status, f"{path}: {e.code} != {expect_status}"
        return e.read()


def register(prefix):
    uname = f"{prefix}_{random.randint(0, 999999):06d}"
    d = gql(
        'mutation($u:String!){register(username:$u,password:"password123"){accessToken user{id usedBytes}}}',
        {"u": uname},
    )["register"]
    return uname, d["accessToken"]


def put(url, data):
    urllib.request.urlopen(urllib.request.Request(url, data=data, method="PUT"))


def upload(name, data, parent, token):
    """完整上传(秒传或分片直传),返回 node id。"""
    sha = hashlib.sha256(data).hexdigest()
    d = gql(
        "mutation($p:ID,$n:String!,$s:String!,$z:Int64!){initUpload(parentId:$p,name:$n,sha256:$s,size:$z)"
        "{instant node{id} session{id partSize partUrls{partNumber url}}}}",
        {"p": parent, "n": name, "s": sha, "z": len(data)},
        token,
    )["initUpload"]
    if d["instant"]:
        return d["node"]["id"]
    s = d["session"]
    ps = s["partSize"]
    for p in s["partUrls"]:
        n = p["partNumber"]
        put(p["url"], data[(n - 1) * ps : n * ps])
    return gql(
        "mutation($id:ID!){completeUpload(sessionId:$id,etags:[]){id}}",
        {"id": s["id"]},
        token,
    )["completeUpload"]["id"]


def mkdir(name, parent, token):
    return gql(
        "mutation($p:ID,$n:String!){createFolder(parentId:$p,name:$n){id}}",
        {"p": parent, "n": name},
        token,
    )["createFolder"]["id"]
