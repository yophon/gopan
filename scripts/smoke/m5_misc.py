"""M5 欠账清零:copyNodes(配额/结构/环拒绝)、changePassword、软删入回收站。"""
import random
import urllib.request

from common import gql, mkdir, register, upload

uname, TOKEN = register("m5")
pw2 = "password456"
print(f"== 注册 {uname} ==")

data = f"m5 file {random.random()}\n".encode() * 10
src = mkdir("src", None, TOKEN)
sub = mkdir("sub", src, TOKEN)
upload("f1.txt", data, src, TOKEN)
upload("f2.txt", data, sub, TOKEN)

used0 = gql("query{me{usedBytes}}", token=TOKEN)["me"]["usedBytes"]
copied = gql(
    "mutation($ids:[ID!]!,$t:ID){copyNodes(ids:$ids,targetParentId:$t){id name kind}}",
    {"ids": [src], "t": None}, TOKEN,
)["copyNodes"]
assert len(copied) == 1 and copied[0]["name"] == "src (1)" and copied[0]["kind"] == "FOLDER", copied
used1 = gql("query{me{usedBytes}}", token=TOKEN)["me"]["usedBytes"]
assert used1 - used0 == 2 * len(data), f"复制应精确 +{2*len(data)},got +{used1-used0}"

kids = gql("query($p:ID){children(parentId:$p){items{id name kind}}}", {"p": copied[0]["id"]}, TOKEN)["children"]["items"]
assert sorted(i["name"] for i in kids) == ["f1.txt", "sub"]
f1q = [i for i in kids if i["kind"] == "FILE"][0]
du = gql("query($id:ID!){node(id:$id){downloadUrl}}", {"id": f1q["id"]}, TOKEN)["node"]["downloadUrl"]
assert urllib.request.urlopen(du).read() == data
# 复制到自身子树被拒(目标必须取原树)
gql("mutation($ids:[ID!]!,$t:ID){copyNodes(ids:$ids,targetParentId:$t){id}}",
    {"ids": [src], "t": sub}, TOKEN, expect_code="CYCLIC_COPY")
print("== copyNodes OK ==")

CP = "mutation($o:String!,$n:String!){changePassword(oldPassword:$o,newPassword:$n){accessToken}}"
gql(CP, {"o": "wrongwrong", "n": pw2}, TOKEN, expect_code="BAD_CREDENTIALS")
assert gql(CP, {"o": "password123", "n": pw2}, TOKEN)["changePassword"]["accessToken"]
LOGIN = "mutation($u:String!,$p:String!){login(username:$u,password:$p){accessToken}}"
gql(LOGIN, {"u": uname, "p": "password123"}, expect_code="BAD_CREDENTIALS")
assert gql(LOGIN, {"u": uname, "p": pw2})["login"]["accessToken"]
print("== changePassword OK ==")

gql("mutation($ids:[ID!]!){deleteNodes(ids:$ids)}", {"ids": [copied[0]["id"]]}, TOKEN)
assert gql("query{trash{total}}", token=TOKEN)["trash"]["total"] == 1
print("== 软删入回收站 OK(过期清理由集成测试覆盖)==")

print("ALL M5 SMOKE PASSED")
