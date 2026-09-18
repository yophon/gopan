"""M11 登录设备:登录建档、当前会话标记、吊销、拒绝吊销自己、跨用户隔离。

前置:dev 依赖与服务已跑(GOPAN_API 可指向任意实例)。
"""
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C  # noqa: E402

UA_DESKTOP = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
)
UA_PHONE = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
)

SESSIONS = "query{sessions{familyId userAgent ip active current}}"


def register_with_ua(prefix, ua):
    uname = f"{prefix}_{random.randint(0, 999999):06d}"
    d = C.gql(
        'mutation($u:String!){register(username:$u,password:"password123"){accessToken}}',
        {"u": uname},
        ua=ua,
    )["register"]
    return uname, d["accessToken"]


def login_with_ua(uname, ua):
    return C.gql(
        'mutation($u:String!){login(username:$u,password:"password123"){accessToken}}',
        {"u": uname},
        ua=ua,
    )["login"]["accessToken"]


# ---- 1. 注册(桌面 UA)+ 再登录(手机 UA)⇒ 两条会话 ----
uname, token_desktop = register_with_ua("dev", UA_DESKTOP)
token_phone = login_with_ua(uname, UA_PHONE)

rows = C.gql(SESSIONS, token=token_phone)["sessions"]
assert len(rows) == 2, f"应有 2 个会话,实际 {len(rows)}: {rows}"

current = [r for r in rows if r["current"]]
assert len(current) == 1, f"应当且只当有一个当前会话: {rows}"
phone = current[0]
assert UA_PHONE in phone["userAgent"], f"当前会话应是手机 UA: {phone}"

desktop = next(r for r in rows if r["familyId"] != phone["familyId"])
assert UA_DESKTOP in desktop["userAgent"], f"另一条应是桌面 UA: {desktop}"
assert desktop["active"] and phone["active"], f"两条都该 active: {rows}"
assert desktop["ip"], f"IP 不该为空(可信代理没配好时会退化成网关地址,但不是空)"
print("== 登录建档 + 当前会话标记 OK ==")

# ---- 2. 不能吊销当前会话 ----
C.gql(
    "mutation($id:ID!){revokeSession(familyId:$id)}",
    {"id": phone["familyId"]},
    token_phone,
    expect_code="CANNOT_REVOKE_CURRENT",
)
print("== 拒绝吊销当前会话 OK ==")

# ---- 3. 吊销另一条 ----
assert C.gql(
    "mutation($id:ID!){revokeSession(familyId:$id)}",
    {"id": desktop["familyId"]},
    token_phone,
)["revokeSession"] is True

rows = C.gql(SESSIONS, token=token_phone)["sessions"]
revoked = next(r for r in rows if r["familyId"] == desktop["familyId"])
assert revoked["active"] is False, f"被吊销的会话应 active=false: {revoked}"
assert sum(1 for r in rows if r["active"]) == 1, f"只该剩一个活跃会话: {rows}"
print("== 吊销指定会话 OK ==")

# ---- 4. 跨用户隔离:别人的 familyId 一律 NotFound ----
_, other_token = register_with_ua("devother", UA_DESKTOP)
C.gql(
    "mutation($id:ID!){revokeSession(familyId:$id)}",
    {"id": phone["familyId"]},
    other_token,
    expect_code="NOT_FOUND",
)
print("== 跨用户吊销被拒 OK ==")

print("\nM11 SESSIONS SMOKE PASSED")
