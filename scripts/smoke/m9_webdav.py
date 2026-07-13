"""M9 smoke:WebDAV 全链路(裸 HTTP 打协议方法,与真实客户端同路径)。

覆盖:应用密码生成/认证边界(错密码/主密码/吊销)、PROPFIND、MKCOL、
PUT/GET 往返、Range、覆盖语义(不进回收站)、MOVE、DELETE 进回收站、
GraphQL 侧可见性(两个入口看到同一棵树)。
"""
import base64
import sys
import urllib.error
import urllib.request

from common import BASE, gql, register

PASSWORD = "password123"


def dav(method, path, user, pwd, body=None, headers=None, expect=None):
    req = urllib.request.Request(BASE + "/dav" + path, data=body, method=method)
    req.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{pwd}".encode()).decode())
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        resp = urllib.request.urlopen(req)
        code, data = resp.status, resp.read()
    except urllib.error.HTTPError as e:
        code, data = e.code, e.read()
    if expect is not None:
        assert code in (expect if isinstance(expect, tuple) else (expect,)), \
            f"{method} {path}: {code},期待 {expect}\n{data[:300]}"
    return code, data


def main():
    uname, tok = register("m9dav")

    # 生成应用密码
    plain = gql('mutation{createAppPassword(name:"smoke-device")}', token=tok)["createAppPassword"]
    assert plain.startswith("gopan_"), "应用密码应带 gopan_ 前缀"

    # 认证边界
    dav("PROPFIND", "/", uname, "gopan_wrong0000", headers={"Depth": "1"}, expect=401)
    dav("PROPFIND", "/", uname, PASSWORD, headers={"Depth": "1"}, expect=401)  # 主密码进不来
    dav("PROPFIND", "/", uname, plain, headers={"Depth": "1"}, expect=207)
    print("认证边界 ✓(应用密码通,主密码/错密码 401)")

    # MKCOL + PUT + GET + Range
    dav("MKCOL", "/docs", uname, plain, expect=201)
    content = "hello webdav via smoke 你好".encode()
    dav("PUT", "/docs/hello.txt", uname, plain, body=content, expect=(201, 204))
    _, got = dav("GET", "/docs/hello.txt", uname, plain, expect=200)
    assert got == content, "GET 往返内容不一致"
    code, part = dav("GET", "/docs/hello.txt", uname, plain, headers={"Range": "bytes=0-4"}, expect=206)
    assert part == b"hello"
    print("MKCOL/PUT/GET/Range ✓")

    # GraphQL 侧可见:同一棵树
    root = gql("query{children{items{id name kind}}}", token=tok)["children"]["items"]
    docs = next((n for n in root if n["name"] == "docs"), None)
    assert docs and docs["kind"] == "FOLDER", "WebDAV 建的目录应在 GraphQL 可见"
    inner = gql('query($p:ID!){children(parentId:$p){items{name size}}}', {"p": docs["id"]}, tok)["children"]["items"]
    assert any(n["name"] == "hello.txt" and n["size"] == len(content) for n in inner), "文件应可见且大小一致"
    print("GraphQL 侧可见 ✓")

    # 覆盖:不进回收站
    dav("PUT", "/docs/hello.txt", uname, plain, body=b"v2", expect=(201, 204))
    _, got = dav("GET", "/docs/hello.txt", uname, plain, expect=200)
    assert got == b"v2"
    trash = gql("query{trash{total}}", token=tok)["trash"]["total"]
    assert trash == 0, f"覆盖不应产生回收站副本,got {trash}"
    print("覆盖语义 ✓")

    # MOVE + DELETE
    dav("MOVE", "/docs/hello.txt", uname, plain,
        headers={"Destination": BASE + "/dav/docs/renamed.txt"}, expect=(201, 204))
    dav("GET", "/docs/hello.txt", uname, plain, expect=404)
    dav("GET", "/docs/renamed.txt", uname, plain, expect=200)
    dav("DELETE", "/docs/renamed.txt", uname, plain, expect=204)
    trash = gql("query{trash{total}}", token=tok)["trash"]["total"]
    assert trash == 1, f"DELETE 应进回收站,got {trash}"
    print("MOVE/DELETE ✓")

    # 吊销后立即失效
    ap = gql("query{appPasswords{id name}}", token=tok)["appPasswords"]
    gql("mutation($id:ID!){revokeAppPassword(id:$id)}", {"id": ap[0]["id"]}, tok)
    dav("PROPFIND", "/", uname, plain, headers={"Depth": "1"}, expect=401)
    print("吊销即失效 ✓")

    print("M9 smoke 全部通过")


if __name__ == "__main__":
    sys.exit(main())
