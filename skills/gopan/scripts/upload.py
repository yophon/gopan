#!/usr/bin/env python3
"""把本地文件传到 gopan 签发的预签名地址(只用 Python 标准库)。

MCP 的 JSON 不承载文件字节:agent 先调 prepare_upload 拿到上传目标,再用本脚本把字节
PUT 到对象存储,最后调 complete_upload + wait_upload。本脚本不调用 MCP,只管搬字节。

用法:
  python upload.py hash FILE
      输出 {"name","size","sha256","content_type"},直接用作 prepare_upload 的参数
      (带上 sha256 时,网盘里已有同样内容会直接秒传,mode 为 instant)。

  python upload.py put FILE PLAN.json      (PLAN 用 - 表示从标准输入读)
      PLAN 是 prepare_upload 或 get_upload_parts 的返回:可以是工具结果的 JSON 本身,
      也可以是带 structuredContent / content[0].text 的整个 MCP 结果。
      - mode=instant:无需上传,直接结束。
      - mode=single_put:整文件 PUT 到 put_url。
      - mode=multipart:把 part_urls 里、还不在 uploaded_parts 里的分片逐个 PUT。
        一次最多给 100 个分片地址;没覆盖到的分片会在输出的 next 里提示
        用 get_upload_parts(first_part=…, limit=100) 取下一批,再运行一次本命令。

输出一行 JSON 汇总;进度写到 stderr。退出码:0 成功,1 用法或输入错误,2 传输失败。
"""

from __future__ import annotations

import hashlib
import http.client
import json
import math
import mimetypes
import os
import sys
import time
import urllib.parse

CHUNK = 1 << 20  # 1 MiB 读写块
RETRIES = 4


class NoRetry(IOError):
    """对象存储明确拒绝(4xx):重试同一个地址没有意义。"""


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def fail(msg: str, code: int = 1) -> "None":
    print(json.dumps({"ok": False, "error": msg}, ensure_ascii=False))
    sys.exit(code)


def file_info(path: str) -> dict:
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
            size += len(b)
    ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
    return {"name": os.path.basename(path), "size": size, "sha256": h.hexdigest(), "content_type": ctype}


def load_plan(src: str) -> dict:
    raw = sys.stdin.read() if src == "-" else open(src, encoding="utf-8").read()
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError as e:
        fail(f"PLAN 不是合法 JSON:{e}")
    # 接受整个 MCP 结果:{"result":{...}} / {"structuredContent":{...}} / {"content":[{"type":"text","text":"{...}"}]}
    if isinstance(obj, dict) and "result" in obj and isinstance(obj["result"], dict):
        obj = obj["result"]
    if isinstance(obj, dict) and isinstance(obj.get("structuredContent"), dict):
        obj = obj["structuredContent"]
    if isinstance(obj, dict) and "mode" not in obj and isinstance(obj.get("content"), list):
        for item in obj["content"]:
            if item.get("type") == "text":
                try:
                    inner = json.loads(item["text"])
                except (json.JSONDecodeError, KeyError):
                    continue
                if isinstance(inner, dict) and "mode" in inner:
                    obj = inner
                    break
    if not isinstance(obj, dict) or "mode" not in obj:
        fail("PLAN 里找不到 mode 字段:请传 prepare_upload 或 get_upload_parts 的返回")
    return obj


def put_range(url: str, path: str, offset: int, length: int) -> None:
    """把文件 [offset, offset+length) 流式 PUT 到预签名地址,失败按指数退避重试。"""
    u = urllib.parse.urlsplit(url)
    target = u.path + ("?" + u.query if u.query else "")
    last = ""
    for attempt in range(1, RETRIES + 1):
        conn_cls = http.client.HTTPSConnection if u.scheme == "https" else http.client.HTTPConnection
        conn = conn_cls(u.netloc, timeout=120)
        try:
            # 不带 Content-Type:预签名没签它,MinIO / S3 会按对象原有元数据处理
            conn.putrequest("PUT", target, skip_accept_encoding=True)
            conn.putheader("Content-Length", str(length))
            conn.endheaders()
            with open(path, "rb") as f:
                f.seek(offset)
                left = length
                while left > 0:
                    b = f.read(min(CHUNK, left))
                    if not b:
                        raise IOError("文件在上传过程中变短了")
                    conn.send(b)
                    left -= len(b)
            resp = conn.getresponse()
            body = resp.read(2048)
            if 200 <= resp.status < 300:
                return
            last = f"HTTP {resp.status}: {body.decode('utf-8', 'replace')[:300]}"
            if 400 <= resp.status < 500:
                # 地址过期、签名不对或长度不符:原样重试没用,要换一批地址(get_upload_parts)
                raise NoRetry(last)
        except NoRetry:
            raise
        except (OSError, http.client.HTTPException) as e:
            last = f"{type(e).__name__}: {e}"
        finally:
            conn.close()
        if attempt < RETRIES:
            wait = 2 ** attempt
            log(f"  重试 {attempt}/{RETRIES - 1}({last}),{wait}s 后")
            time.sleep(wait)
    raise IOError(last or "上传失败")


def cmd_hash(path: str) -> None:
    if not os.path.isfile(path):
        fail(f"不是文件:{path}")
    print(json.dumps(file_info(path), ensure_ascii=False))


def cmd_put(path: str, plan_src: str) -> None:
    if not os.path.isfile(path):
        fail(f"不是文件:{path}")
    plan = load_plan(plan_src)
    size = os.path.getsize(path)
    mode = plan["mode"]
    upload_id = plan.get("upload_id")
    summary: dict = {"ok": True, "mode": mode, "upload_id": upload_id}

    if mode == "instant" or plan.get("status") == "ready":
        summary["next"] = "无需上传:内容已在网盘里(秒传)或会话已完成"
        node = plan.get("node")
        if node:
            summary["node"] = node
        print(json.dumps(summary, ensure_ascii=False))
        return

    if plan.get("status") not in (None, "uploading"):
        fail(f"会话状态是 {plan.get('status')},不能再上传字节")

    if mode == "single_put":
        url = plan.get("put_url")
        if not url:
            fail("single_put 的 PLAN 里没有 put_url(可能已过期,调 get_upload_parts 刷新)")
        log(f"PUT 整个文件 {size} 字节")
        t0 = time.time()
        try:
            put_range(url, path, 0, size)
        except IOError as e:
            fail(f"上传失败:{e}(地址过期时调 get_upload_parts 刷新 put_url 后重试)", 2)
        summary.update(bytes=size, seconds=round(time.time() - t0, 2),
                       next=f"调 complete_upload(upload_id={upload_id}),再 wait_upload 到 ready")
        print(json.dumps(summary, ensure_ascii=False))
        return

    if mode != "multipart":
        fail(f"不认识的 mode:{mode}")

    part_size = int(plan.get("part_size") or 0)
    total = int(plan.get("total_parts") or 0)
    if part_size <= 0 or total <= 0:
        fail("multipart 的 PLAN 缺 part_size / total_parts")
    expect = max(1, math.ceil(size / part_size))
    if expect != total:
        fail(f"文件大小 {size} 按 {part_size} 分片应为 {expect} 片,会话里是 {total} 片:是不是传错了文件?")

    done = set(int(n) for n in (plan.get("uploaded_parts") or []))
    urls = {int(p["part_number"]): p["url"] for p in (plan.get("part_urls") or [])}
    todo = sorted(n for n in urls if n not in done)
    log(f"分片 {total} 片 × {part_size} 字节;已传 {len(done)},本次可传 {len(todo)}")
    uploaded: list[int] = []
    t0 = time.time()
    for n in todo:
        offset = (n - 1) * part_size
        length = min(part_size, size - offset)
        log(f"  第 {n}/{total} 片({length} 字节)")
        try:
            put_range(urls[n], path, offset, length)
        except IOError as e:
            summary.update(ok=False, uploaded_parts=uploaded, failed_part=n, error=str(e),
                           next=f"调 get_upload_parts(upload_id={upload_id}, first_part={n}, limit=100) 刷新地址后重跑本命令")
            print(json.dumps(summary, ensure_ascii=False))
            sys.exit(2)
        uploaded.append(n)
    have = done | set(uploaded)
    missing = [n for n in range(1, total + 1) if n not in have]
    summary.update(uploaded_parts=uploaded, already_uploaded=len(done), seconds=round(time.time() - t0, 2),
                   missing_parts=len(missing))
    if missing:
        summary["next"] = (f"还有 {len(missing)} 片没有地址:调 get_upload_parts(upload_id={upload_id}, "
                           f"first_part={missing[0]}, limit=100),把结果再交给本命令")
    else:
        summary["next"] = f"全部分片已传:调 complete_upload(upload_id={upload_id}),再 wait_upload 到 ready"
    print(json.dumps(summary, ensure_ascii=False))


def main(argv: list[str]) -> None:
    # Windows 控制台默认 cp1252/GBK,中文输出会抛 UnicodeEncodeError;统一 UTF-8
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    if len(argv) >= 2 and argv[0] == "hash":
        cmd_hash(argv[1])
    elif len(argv) >= 3 and argv[0] == "put":
        cmd_put(argv[1], argv[2])
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv[1:])
