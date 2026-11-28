#!/usr/bin/env python3
"""T-057：准备 Spider 2.0-Lite SQLite 数据库（外部基准用）。

数据集约 950MB，不入库。本脚本只做三件事：
  1. 取官方 sqlite.zip（或用户给的本地 zip），校验整包 SHA256；
  2. 只解出 bench/external/db-checksums.json 里列出的 20 个库；
  3. 逐库校验 SHA256 与字节数（防截断、防换版本）。

用法：
  # 已有官方 zip：离线、可复现（推荐）
  python scripts/fetch-external-bench.py --zip D:/downloads/sqlite.zip

  # 直接下载官方包（固定官方 Google Drive 地址；网络受限时请手动下载后走 --zip）
  python scripts/fetch-external-bench.py

  # 只校验现有数据，不下载
  python scripts/fetch-external-bench.py --check-only

数据集目录默认 bench/external/data；也可用 --dest 或环境变量
STRAGENT_BENCH_EXTERNAL_DIR 指向别处（两者需一致，bench 运行时读环境变量）。

来源与许可：
  Spider 2.0-Lite，https://github.com/xlang-ai/Spider2（MIT License）
  本地 SQLite 包：Google Drive 文件 id 1coEVsCZq-Xvj9p2TnhBFoFTsY-UoYGmG
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXTERNAL = ROOT / "bench" / "external"
CHECKSUM_FILE = EXTERNAL / "db-checksums.json"
DEFAULT_DEST = EXTERNAL / "data"

# 本仓基线使用的官方 sqlite.zip（456,645,715 字节，2026-11-05 版：官方更新了 f1 库，
# 其余 19 库与旧包逐字节一致——T-092 显式版本升级，db-checksums.json 已同步重建）的 SHA256。
# 换包/换版本必须显式改这里并重建基线，不能在运行时静默接受。
ZIP_SHA256 = "2ae51410d3520acbae649853a15267a2592f6a090d0950a905bf640e901e7668"
OFFICIAL_URL = (
    "https://drive.usercontent.google.com/download"
    "?id=1coEVsCZq-Xvj9p2TnhBFoFTsY-UoYGmG&export=download&authuser=0"
)

CHUNK = 1 << 20


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_checksums() -> dict:
    if not CHECKSUM_FILE.is_file():
        sys.exit(f"缺少校验清单：{CHECKSUM_FILE}")
    return json.loads(CHECKSUM_FILE.read_text(encoding="utf-8"))


_SAFE_DB_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def _validate_db_name(name: str) -> str:
    """数据库文件名只允许安全字符：堵 zip 条目与清单键注入路径分隔符或相对跳转。"""
    if (
        not name
        or not _SAFE_DB_NAME.match(name)
        or ".." in name
        or Path(name).name != name
        or name in (".", "..")
    ):
        sys.exit(f"非法的数据库名，拒绝处理：{name!r}")
    return name


def load_checksums() -> dict:
    if not CHECKSUM_FILE.is_file():
        sys.exit(f"缺少校验清单：{CHECKSUM_FILE}")
    data = json.loads(CHECKSUM_FILE.read_text(encoding="utf-8"))
    for key in data:
        _validate_db_name(key)
    return data


def verify_databases(dest: Path, checksums: dict, quiet: bool = False) -> list[str]:
    """返回问题列表；空列表 = 全部通过。"""
    problems = []
    db_dir = dest / "db"
    for name, meta in checksums.items():
        path = db_dir / f"{name}.sqlite"
        if not path.is_file():
            problems.append(f"缺少 {path}")
            continue
        size = path.stat().st_size
        if size != meta["bytes"]:
            problems.append(f"{name}.sqlite 大小 {size}B != 期望 {meta['bytes']}B")
            continue
        actual = sha256_file(path)
        if actual != meta["sha256"]:
            problems.append(f"{name}.sqlite SHA256 {actual[:12]}... != 期望 {meta['sha256'][:12]}...")
        elif not quiet:
            print(f"  [ok] {name}.sqlite  {size / (1 << 20):.1f} MiB")
    return problems


# 确认页解析只认这一个官方主机；协议/域名/IP 边界三重校验（T-092，安全加固建议）
CONFIRM_HOST = "drive.usercontent.google.com"


class _PinnedRedirect(urllib.request.HTTPRedirectHandler):
    """重定向钉死在官方主机：离开白名单 = 不跟随（urllib 会抛 HTTPError）。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlparse(newurl)
        if parsed.scheme != "https" or parsed.hostname != CONFIRM_HOST:
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _assert_public_host(hostname: str) -> None:
    """DNS 解析后必须全是公网地址——阻断私网/环回/链路本地/云元数据面（防 rebinding 落点）。"""
    import ipaddress
    import socket

    for info in socket.getaddrinfo(hostname, 443):
        ip = ipaddress.ip_address(info[4][0])
        if not ip.is_global:
            sys.exit(f"下载地址解析到非公网 IP（{ip}），拒绝连接")


def _resolve_confirm_url(url: str, first_bytes: bytes) -> str | None:
    """Google Drive 对大文件先回「病毒扫描确认页」（HTML form，带 confirm/uuid 等隐藏字段）。
    从确认页解析出 form action 与**全部隐藏字段**（缺字段会 400），拼成真实下载地址
    （仅追加官方 form 的参数到同一主机）；不是确认页返回 None。"""
    if b"virus" not in first_bytes.lower():
        return None
    html = first_bytes.decode("utf-8", errors="replace")
    action = re.search(r'<form[^>]+action="([^"]+)"', html)
    if not action:
        return None
    base = action.group(1).replace("&amp;", "&")
    if base.startswith("/"):
        base = f"https://{CONFIRM_HOST}{base}"
    parsed = urllib.parse.urlparse(base)
    # 协议 + 主机白名单（域名不匹配 = 放弃自动确认，走手动 --zip 路径）
    if parsed.scheme != "https" or parsed.hostname != CONFIRM_HOST:
        return None
    fields = re.findall(r'<input[^>]+name="([^"]+)"[^>]+value="([^"]*)"', html)
    if not fields:
        return None
    sep = "&" if parsed.query else "?"
    extra = "&".join(f"{name}={value}" for name, value in fields)
    return f"{base}{sep}{extra}"


def download(target: Path) -> None:
    """只从固定官方地址下载（OFFICIAL_URL 常量内联使用，不接受外部 URL）。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    print(f"下载 {OFFICIAL_URL}")
    opener = urllib.request.build_opener(_PinnedRedirect)
    try:
        _assert_public_host(CONFIRM_HOST)
        with opener.open(OFFICIAL_URL, timeout=60) as response:
            head = response.read(CHUNK)
        confirm_url = _resolve_confirm_url(OFFICIAL_URL, head)
        if confirm_url:
            print("  病毒扫描确认页 → 携带 confirm token 重试")
            url = confirm_url
        else:
            url = OFFICIAL_URL
        with opener.open(url, timeout=60) as response, partial.open("wb") as out:
            out.write(head)
            done = len(head)
            total = int(response.headers.get("Content-Length") or 0)
            # 非确认页时先写入的头字节也是 zip 本体的一部分；确认页则整段丢弃重下
            if confirm_url:
                done = 0
            while True:
                chunk = response.read(CHUNK)
                if not chunk:
                    break
                out.write(chunk)
                done += len(chunk)
                if total:
                    print(f"\r  {done / (1 << 20):.0f}/{total / (1 << 20):.0f} MiB", end="", flush=True)
            print()
    except Exception as exc:
        partial.unlink(missing_ok=True)
        sys.exit(
            f"下载失败：{exc}\n"
            "Google Drive 在受限网络下常不可达；请手动下载官方 sqlite.zip，再用\n"
            f"  python scripts/fetch-external-bench.py --zip <sqlite.zip>\n"
            f"包 SHA256 应为 {ZIP_SHA256}"
        )
    partial.replace(target)


def extract(zip_path: Path, dest: Path, checksums: dict) -> None:
    db_dir = dest / "db"
    db_dir.mkdir(parents=True, exist_ok=True)
    needed = {f"{name}.sqlite" for name in checksums}
    written = set()
    with zipfile.ZipFile(zip_path) as zf:
        members = {
            Path(info.filename).name: info
            for info in zf.infolist()
            if not info.is_dir() and Path(info.filename).name in needed
        }
        missing = sorted(needed - members.keys())
        if missing:
            sys.exit(f"zip 中缺少 {len(missing)} 个库：{', '.join(missing)}")
        for name in sorted(members):
            info = members[name]
            _validate_db_name(name)
            target = (db_dir / name).resolve()
            if not target.is_relative_to(db_dir.resolve()):
                sys.exit(f"zip 条目越界，拒绝写出：{name!r}")
            digest = hashlib.sha256()
            with zf.open(info) as src, target.open("wb") as out:
                for chunk in iter(lambda: src.read(CHUNK), b""):
                    digest.update(chunk)
                    out.write(chunk)
            actual = digest.hexdigest()
            expected = checksums[name[:-len('.sqlite')]]["sha256"]
            if actual != expected:
                target.unlink(missing_ok=True)
                sys.exit(f"{name} SHA256 不匹配：{actual} != {expected}（zip 版本不对）")
            written.add(name)
            print(f"  [ok] 解出 {name}  {target.stat().st_size / (1 << 20):.1f} MiB")
    if written != needed:
        sys.exit("解压结果不完整")


def main() -> int:
    parser = argparse.ArgumentParser(description="准备 Spider 2.0-Lite SQLite 子集数据库")
    parser.add_argument("--zip", type=Path, help="本地 sqlite.zip（官方包）")
    parser.add_argument("--dest", type=Path, default=None, help=f"数据集目录（默认 {DEFAULT_DEST}）")
    parser.add_argument("--check-only", action="store_true", help="只校验现有数据")
    parser.add_argument("--force", action="store_true", help="已就绪也重新解压")
    args = parser.parse_args()

    dest = args.dest or Path(os.environ.get("STRAGENT_BENCH_EXTERNAL_DIR") or DEFAULT_DEST)
    dest = dest.expanduser().resolve()
    checksums = load_checksums()

    if args.check_only:
        problems = verify_databases(dest, checksums)
        if problems:
            print("校验失败：", file=sys.stderr)
            for p in problems:
                print(f"  [x] {p}", file=sys.stderr)
            return 1
        print(f"[ok] {len(checksums)} 个数据库全部通过校验：{dest / 'db'}")
        return 0

    if not args.force:
        problems = verify_databases(dest, checksums, quiet=True)
        if not problems:
            print(f"[ok] 数据已就绪（{len(checksums)} 库），无需重新下载：{dest / 'db'}")
            return 0

    if args.zip:
        zip_path = args.zip.expanduser().resolve()
        if not zip_path.is_file():
            sys.exit(f"zip 不存在：{zip_path}")
    else:
        zip_path = dest / ".cache" / "sqlite.zip"
        if not zip_path.is_file() or args.force:
            download(zip_path)

    actual_zip = sha256_file(zip_path)
    if actual_zip != ZIP_SHA256:
        sys.exit(
            f"sqlite.zip SHA256 不匹配：\n  实际 {actual_zip}\n  期望 {ZIP_SHA256}\n"
            "请确认用的是 Spider 2.0-Lite 官方本地数据库包（换版本需同步更新基线与校验清单）。"
        )

    print(f"解出 {len(checksums)} 个库到 {dest / 'db'} ...")
    extract(zip_path, dest, checksums)
    problems = verify_databases(dest, checksums, quiet=True)
    if problems:
        print("解压后校验失败：", file=sys.stderr)
        for p in problems:
            print(f"  [x] {p}", file=sys.stderr)
        return 1
    print(f"[ok] 完成：{len(checksums)} 库已通过 SHA256 校验")
    print(f"运行外部基准：STRAGENT_BENCH_EXTERNAL_DIR={dest} gradle :bench:externalBench")
    if not args.zip:
        shutil.rmtree(zip_path.parent, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
