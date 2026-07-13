"""M7 smoke:管理端全链路。

需要能对目标实例的数据库执行 CLI promote(默认 dev 环境,GOPAN_DB_URL 可覆盖)。
远程实例上跑:预先备好 admin 账号,用 GOPAN_ADMIN_USER / GOPAN_ADMIN_PASS 传入,
此时跳过 CLI promote 步骤。
"""
import os
import subprocess
import sys

from common import gql, mkdir, register

DB_URL = os.environ.get("GOPAN_DB_URL", "postgres://gopan:gopan@127.0.0.1:5433/gopan?sslmode=disable")
SERVER_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "server")


def login(username, password, expect_code=None):
    d = gql(
        "mutation($u:String!,$p:String!){login(username:$u,password:$p){accessToken user{id isAdmin}}}",
        {"u": username, "p": password},
        expect_code=expect_code,
    )
    return d["login"] if d else None


def main():
    # 1. 拿到一个 admin 身份
    admin_user = os.environ.get("GOPAN_ADMIN_USER")
    if admin_user:
        admin = login(admin_user, os.environ["GOPAN_ADMIN_PASS"])
    else:
        # 注册普通用户,走真实 CLI 子命令提升(顺带验证 gopan admin promote)
        admin_user, _ = register("m7admin")
        subprocess.run(
            ["go", "run", "./cmd/gopan", "admin", "promote", admin_user],
            cwd=SERVER_DIR, env={**os.environ, "GOPAN_DB_URL": DB_URL},
            check=True, capture_output=True,
        )
        admin = login(admin_user, "password123")
    assert admin["user"]["isAdmin"], "promote 后 isAdmin 应为 true"
    atok = admin["accessToken"]
    print("admin 就绪:", admin_user)

    # 2. 非 admin 访问管理接口一律 FORBIDDEN
    _, vtok = register("m7victim")
    gql("query{adminUsers{id}}", token=vtok, expect_code="FORBIDDEN")
    gql("query{adminOverview{userCount}}", token=vtok, expect_code="FORBIDDEN")
    print("非 admin 拒绝 ✓")

    # 3. 建号(带配额)→ 新号可登录
    import random
    uname = f"m7created_{random.randint(0, 999999):06d}"
    cu = gql(
        "mutation($u:String!){adminCreateUser(username:$u,password:\"password123\",quotaBytes:1048576){id quotaBytes disabled}}",
        {"u": uname}, atok,
    )["adminCreateUser"]
    assert cu["quotaBytes"] == 1048576 and not cu["disabled"]
    created = login(uname, "password123")
    ctok = created["accessToken"]
    print("建号+登录 ✓")

    # 4. 调配额 → 超额上传被拒(配额生效的行为证明)
    gql(
        "mutation($id:ID!){adminSetQuota(userId:$id,quotaBytes:10){quotaBytes}}",
        {"id": cu["id"]}, atok,
    )
    gql(
        'mutation{initUpload(name:"big.bin",sha256:"' + "a" * 64 + '",size:1000000){instant}}',
        token=ctok, expect_code="QUOTA_EXCEEDED",
    )
    print("配额生效 ✓")

    # 5. 建分享 → 禁用 → 登录拒 / 访客拒;解禁恢复
    folder = mkdir("m7docs", None, ctok)
    sh = gql(
        "mutation($n:ID!){createShare(nodeId:$n){token}}", {"n": folder}, ctok,
    )["createShare"]
    info = gql('query($t:String!){shareInfo(token:$t){expired}}', {"t": sh["token"]})["shareInfo"]
    assert not info["expired"]

    gql("mutation($id:ID!){adminSetDisabled(userId:$id,disabled:true){disabled}}", {"id": cu["id"]}, atok)
    login(uname, "password123", expect_code="ACCOUNT_DISABLED")
    info = gql('query($t:String!){shareInfo(token:$t){expired}}', {"t": sh["token"]})["shareInfo"]
    assert info["expired"], "禁用后分享应连带失效"
    gql(
        'mutation($t:String!){verifySharePassword(token:$t,password:""){accessToken}}',
        {"t": sh["token"]}, expect_code="SHARE_EXPIRED",
    )
    gql("mutation($id:ID!){adminSetDisabled(userId:$id,disabled:false){disabled}}", {"id": cu["id"]}, atok)
    assert login(uname, "password123"), "解禁后应能登录"
    print("禁用/解禁语义 ✓")

    # 6. 重置密码:旧密码拒,新密码过
    newpw = gql("mutation($id:ID!){adminResetPassword(userId:$id)}", {"id": cu["id"]}, atok)["adminResetPassword"]
    login(uname, "password123", expect_code="BAD_CREDENTIALS")
    assert login(uname, newpw), "新密码应可登录"
    print("重置密码 ✓")

    # 7. 概览与失败任务重排(可能为 0,只验证接口可用)
    ov = gql("query{adminOverview{userCount totalUsedBytes blobCount taskCounts{status count}}}", token=atok)["adminOverview"]
    assert ov["userCount"] >= 3
    n = gql("mutation{adminRetryFailedTasks}", token=atok)["adminRetryFailedTasks"]
    print(f"概览 ✓(用户 {ov['userCount']},重排失败任务 {n})")

    print("M7 smoke 全部通过")


if __name__ == "__main__":
    sys.exit(main())
