"""M4 分享与访客:创建/验密/子树边界/搜索限定/打包/og 落地页/吊销联动。"""
import io
import random
import urllib.request
import zipfile

from common import BASE, gql, http_get, mkdir, register, upload

uname, OWNER = register("m4")
print(f"== 注册 {uname} ==")

seed = random.randint(0, 999999)
a_data = f"hello from a.txt #{seed}\n".encode()
b_data = f"hello from sub/b.txt #{seed}\n".encode() * 3
secret_data = b"top secret\n"

root_id = mkdir("share-root", None, OWNER)
sub_id = mkdir("sub", root_id, OWNER)
empty_id = mkdir("empty", root_id, OWNER)
a_id = upload("a.txt", a_data, root_id, OWNER)
b_id = upload("b.txt", b_data, sub_id, OWNER)
priv_id = mkdir("private", None, OWNER)
secret_id = upload("secret.txt", secret_data, priv_id, OWNER)
print("== 目录树就绪 ==")

CS = 'mutation($n:ID!,$p:String,$e:Time){createShare(nodeId:$n,password:$p,expiresAt:$e){id token hasPassword expiresAt node{id name kind}}}'
sh = gql(CS, {"n": root_id, "p": "sesame88"}, OWNER)["createShare"]
assert sh["hasPassword"] and len(sh["token"]) == 10 and sh["node"]["name"] == "share-root", sh
gql(CS, {"n": "00000000-0000-0000-0000-000000000001"}, OWNER, expect_code="NOT_FOUND")
gql(CS, {"n": root_id, "e": "2000-01-01T00:00:00Z"}, OWNER, expect_code="INVALID_INPUT")
print(f"== createShare OK token={sh['token']} ==")

SI = 'query($t:String!){shareInfo(token:$t){name kind needPassword expired}}'
info = gql(SI, {"t": sh["token"]})["shareInfo"]
assert info == {"name": "share-root", "kind": "FOLDER", "needPassword": True, "expired": False}, info
gql(SI, {"t": "nosuchtoken"}, expect_code="NOT_FOUND")
print("== shareInfo OK ==")

VP = 'mutation($t:String!,$p:String!){verifySharePassword(token:$t,password:$p){accessToken}}'
gql(VP, {"t": sh["token"], "p": ""}, expect_code="SHARE_PASSWORD_REQUIRED")
gql(VP, {"t": sh["token"], "p": "wrong"}, expect_code="BAD_SHARE_PASSWORD")
GUEST = gql(VP, {"t": sh["token"], "p": "sesame88"})["verifySharePassword"]["accessToken"]
print("== verifySharePassword OK ==")

rootq = gql("query{shareRoot{id name kind parentId}}", token=GUEST)["shareRoot"]
assert rootq["id"] == root_id and rootq["parentId"] is None, rootq
kids = gql("query($p:ID){children(parentId:$p){items{id name kind} total}}", {"p": root_id}, GUEST)["children"]
assert sorted(i["name"] for i in kids["items"]) == ["a.txt", "empty", "sub"]
nb = gql("query($id:ID!){node(id:$id){name preview{kind contentUrl}}}", {"id": b_id}, GUEST)["node"]
assert nb["preview"]["kind"] == "TEXT" and urllib.request.urlopen(nb["preview"]["contentUrl"]).read() == b_data
gql("query($id:ID!){node(id:$id){id}}", {"id": secret_id}, GUEST, expect_code="NOT_FOUND")
gql("query($p:ID){children(parentId:$p){total}}", {"p": priv_id}, GUEST, expect_code="NOT_FOUND")
gql("query{children{total}}", token=GUEST, expect_code="FORBIDDEN")
gql("query{me{id}}", token=GUEST, expect_code="FORBIDDEN")
gql('mutation($p:ID){createFolder(parentId:$p,name:"evil"){id}}', {"p": root_id}, GUEST, expect_code="FORBIDDEN")
gql("mutation($id:ID!){requestPreview(nodeId:$id){kind}}", {"id": b_id}, GUEST, expect_code="FORBIDDEN")
print("== 访客子树边界 OK ==")

found = gql("query($q:String!){searchNodes(q:$q){items{name} total}}", {"q": ".txt"}, GUEST)["searchNodes"]
assert sorted(i["name"] for i in found["items"]) == ["a.txt", "b.txt"]
assert gql("query($q:String!){searchNodes(q:$q){total}}", {"q": ".txt"}, OWNER)["searchNodes"]["total"] == 3
print("== 访客搜索限子树 OK ==")

du = gql("query($id:ID!){node(id:$id){downloadUrl}}", {"id": a_id}, GUEST)["node"]["downloadUrl"]
assert urllib.request.urlopen(du).read() == a_data
print("== 访客 downloadUrl OK ==")

zbytes = http_get(f"/pack?nodes={root_id}&token={GUEST}")
zf = zipfile.ZipFile(io.BytesIO(zbytes))
assert sorted(zf.namelist()) == ["share-root/", "share-root/a.txt", "share-root/empty/", "share-root/sub/", "share-root/sub/b.txt"]
assert zf.read("share-root/a.txt") == a_data and zf.read("share-root/sub/b.txt") == b_data
http_get(f"/pack?nodes={root_id}", expect_status=401)
http_get(f"/pack?nodes={priv_id}&token={GUEST}", expect_status=404)
z2 = zipfile.ZipFile(io.BytesIO(http_get(f"/pack?nodes={a_id},{sub_id}&token={OWNER}")))
assert sorted(z2.namelist()) == ["a.txt", "sub/", "sub/b.txt"]
print("== /pack OK ==")

page = http_get(f"/s/{sh['token']}").decode()
assert "og:title" in page and "share-root" in page
assert "og:title" not in http_get("/s/nosuchtoken").decode()
print("== /s/:token og OK ==")

mine = gql("query{myShares{id token node{name} hasPassword}}", token=OWNER)["myShares"]
assert len(mine) == 1 and mine[0]["node"]["name"] == "share-root"
assert gql("mutation($id:ID!){revokeShare(id:$id)}", {"id": sh["id"]}, OWNER)["revokeShare"]
assert gql(SI, {"t": sh["token"]})["shareInfo"]["expired"]
gql(VP, {"t": sh["token"], "p": "sesame88"}, expect_code="SHARE_EXPIRED")
gql("query{shareRoot{id}}", token=GUEST, expect_code="SHARE_EXPIRED")
http_get(f"/pack?nodes={root_id}&token={GUEST}", expect_status=410)
assert gql("query{myShares{id}}", token=OWNER)["myShares"] == []
print("== revoke 全线失效 OK ==")

sh2 = gql(CS, {"n": sub_id}, OWNER)["createShare"]
G2 = gql(VP, {"t": sh2["token"], "p": ""})["verifySharePassword"]["accessToken"]
assert gql("query{shareRoot{name}}", token=G2)["shareRoot"]["name"] == "sub"
gql("mutation($ids:[ID!]!){deleteNodes(ids:$ids)}", {"ids": [root_id]}, OWNER)
gql("query{shareRoot{name}}", token=G2, expect_code="SHARE_EXPIRED")
gql("mutation($ids:[ID!]!){restoreNodes(ids:$ids){id}}", {"ids": [root_id]}, OWNER)
assert gql("query{shareRoot{name}}", token=G2)["shareRoot"]["name"] == "sub"
print("== 无密码分享 + 软删联动 OK ==")

print("ALL M4 SMOKE PASSED")
