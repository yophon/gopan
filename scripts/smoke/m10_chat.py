"""M10 smoke:「我的设备」文件传输助手(v2.2)。

覆盖前端实际走的那层 GraphQL:chatFolder / ensureChatFolder / sendChatMessage / chatMessages。
文件删掉后消息要留存、node 字段要退化成 null(前端据此显示「文件已不存在」)。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from common import gql, mkdir, register, upload  # noqa: E402

MSG_FIELDS = "id body createdAt node{id name kind size}"

uname, TOKEN = register("m10")
print(f"== 注册 {uname} ==")

# 1. 从未创建过 → chatFolder 为 null(查询不触发创建)
assert gql("query{chatFolder{id}}", token=TOKEN)["chatFolder"] is None, "首次查询应返回 null"
print("== chatFolder 初始为 null OK ==")

# 2. ensureChatFolder 幂等:两次拿到同一个「我的设备」文件夹
f1 = gql("mutation{ensureChatFolder{id parentId name kind}}", token=TOKEN)["ensureChatFolder"]
f2 = gql("mutation{ensureChatFolder{id}}", token=TOKEN)["ensureChatFolder"]
assert f1["id"] == f2["id"], f"ensureChatFolder 应幂等:{f1} vs {f2}"
assert f1["name"] == "我的设备" and f1["parentId"] is None and f1["kind"] == "FOLDER", f1
assert gql("query{chatFolder{id}}", token=TOKEN)["chatFolder"]["id"] == f1["id"]
print(f"== ensureChatFolder 幂等 OK(folder={f1['id']}) ==")

# 3. 文本消息:前后空白被 trim
sent = gql(
    "mutation($b:String){sendChatMessage(body:$b){%s}}" % MSG_FIELDS,
    {"b": "  你好,世界  "}, TOKEN,
)["sendChatMessage"]
assert sent["body"] == "你好,世界" and sent["node"] is None, sent
print("== 文本消息 trim OK ==")

# 4. 上传文件到「我的设备」,再发成带真实文件卡片的消息
data = b"chat attachment\n" * 100
fid = upload("chat-note.txt", data, f1["id"], TOKEN)
file_msg = gql(
    "mutation($n:ID){sendChatMessage(nodeId:$n){%s}}" % MSG_FIELDS,
    {"n": fid}, TOKEN,
)["sendChatMessage"]
assert file_msg["body"] == "", file_msg
assert file_msg["node"] and file_msg["node"]["id"] == fid, file_msg
assert file_msg["node"]["name"] == "chat-note.txt" and file_msg["node"]["size"] == len(data), file_msg
print("== 文件消息带出真实文件卡片 OK ==")

# 5. chatMessages:两条都在、按时间升序
items = gql("query($l:Int){chatMessages(limit:$l){%s}}" % MSG_FIELDS, {"l": 10}, TOKEN)["chatMessages"]
assert len(items) == 2, f"应 2 条,got {len(items)}"
assert items[0]["id"] == sent["id"] and items[1]["id"] == file_msg["id"], "应按时间升序"
print("== chatMessages 升序 OK ==")

# 6. 越权:发别人的 node、空 body、两者都不给
_, OTHER = register("m10other")
other_node = mkdir("not-yours", None, OTHER)
assert gql(
    "mutation($n:ID){sendChatMessage(nodeId:$n){id}}", {"n": other_node}, TOKEN,
    expect_code="NOT_FOUND",
) is None
gql('mutation{sendChatMessage(body:"   "){id}}', token=TOKEN, expect_code="INVALID_INPUT")
gql("mutation{sendChatMessage{id}}", token=TOKEN, expect_code="INVALID_INPUT")
print("== 越权 / 空消息拒收 OK ==")

# 7. 文件删掉:消息仍在,node 退化成 null(而不是把整条聊天弄挂)
gql("mutation($ids:[ID!]!){deleteNodes(ids:$ids)}", {"ids": [fid]}, TOKEN)
items2 = gql("query{chatMessages{%s}}" % MSG_FIELDS, token=TOKEN)["chatMessages"]
assert len(items2) == 2, f"删文件不应删消息,got {len(items2)}"
assert items2[1]["id"] == file_msg["id"] and items2[1]["node"] is None, items2[1]
# 回收站里的节点也不能再从 id 取到(下载/预览都走这条)
gql("query($i:ID!){node(id:$i){id}}", {"i": fid}, TOKEN, expect_code="NOT_FOUND")
print("== 删除文件后消息留存、node 置空 OK ==")

print("ALL M10 SMOKE PASSED")
