# T-206: delta-sync the export repo to GitHub via Git Data API (dual-write, fork-guarded).
# 进仓版本化（原 C:/tmp/sync_delta.py，2026-11-28 双仓同源轮实战沉淀）。
# 本工具是本机运维工具：默认路径指向本机约定，均可用环境变量覆盖；
# 凭据只读文件路径，token 值永不进入本文件与输出。
#
# 机制（2026-11-28 实证沉淀）：
#   fork guard    —— 本地 main != 远端 head 即中止（双写会把分叉固化进远端）；
#   mode 感知     —— delta 判定比 (mode, sha) 对（执行位漂移 blob sha 不变）；
#   归一字节上传  —— 读索引归一字节（git cat-file blob），CRLF 工作树字节会致 delta 死循环；
#   同源双写      —— GitHub API 构造忠实（同字段对象 sha 逐字节相等，实测验证）：
#                    远端创建后本地以同字段 commit-tree 重建并前进 main，两侧逐 sha 相等。
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPO_DIR = Path(os.environ.get(
    "STRAGENT_EXPORT_DIR", r"C:\Users/<user>\Desktop\agent\str-agent\build\export-public"))
TOKEN_FILE = Path(os.environ.get(
    "STRAGENT_GITHUB_TOKEN_FILE", r"C:\Users/<user>\.reference-harness\secrets\github_token"))
API = "https://api.github.com"
OWNER, REPO, BR = "Lanigelevent", "str-agent", "main"
AUTHOR = {"name": "Lanigelevent", "email": "359437478+Lanigelevent@users.noreply.github.com"}


def api(method, path, payload=None, token=None):
    req = urllib.request.Request(API + path, method=method,
        data=json.dumps(payload).encode() if payload is not None else None)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "str-agent-export")
    last = None
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read().decode()
                return json.loads(body) if body.strip() else {}
        except urllib.error.HTTPError as e:
            if e.code == 403 and e.headers.get("X-RateLimit-Remaining") == "0":
                wait = int(e.headers.get("X-RateLimit-Reset", "0")) - int(time.time()) + 3
                print("rate limit: sleeping", wait, "s", flush=True)
                time.sleep(max(wait, 5))
                continue
            if e.code >= 500:
                last = e
                time.sleep(3 * (attempt + 1))
                continue
            try:
                print("API-ERROR", e.code, e.read().decode()[:400], flush=True)
            except Exception:
                pass
            raise
        except (urllib.error.URLError, OSError) as e:
            last = e
            time.sleep(3 * (attempt + 1))
    raise RuntimeError("api failed: " + path + " / " + str(last))


# ------------------------------------------------- 可单测的纯函数（--self-test 覆盖）

def parse_local_index(ls_output):
    """解析 `git ls-files -z -s` 输出 → {path: (mode, blob_sha)}"""
    m = {}
    for rec in ls_output.split("\0"):
        if not rec:
            continue
        meta, path = rec.split("\t", 1)
        mode, sha, _stage = meta.split()
        m[path] = (mode, sha)
    return m


def compute_delta(local, remote):
    """mode 感知 delta：local {path:(mode,sha)} vs remote {path:(mode,sha)}。
    新增 / 内容变更 / mode-only 漂移都算；完全一致不算。"""
    delta = {}
    for p, mv in local.items():
        rv = remote.get(p)
        if rv is None or rv != mv:
            delta[p] = mv
    return delta


def compute_removed(local_keys, remote_keys):
    return sorted(set(remote_keys) - set(local_keys))


def fork_guard(local_ref, remote_head):
    """同源前置校验：本地 ref 必须与远端 head 相等（空 ref = 未初始化，放行）。
    返回 None=通过，或失败原因字符串。"""
    if not local_ref:
        return None
    if local_ref != remote_head:
        return "FORK: local main %s != remote %s; sync aborted (align first)" % (
            local_ref[:10], remote_head[:10])
    return None


def git(args, cwd, input_bytes=None, env_extra=None):
    env = dict(os.environ)
    env.setdefault("SYSTEMROOT", "C:/Windows")
    if env_extra:
        env.update(env_extra)
    return subprocess.run(["git"] + args, cwd=str(cwd), input=input_bytes,
                          capture_output=True, check=True, env=env).stdout


def rebuild_local_commit(repo_dir, tree_sha, parent_sha, message, author, when_iso):
    """双写重建：与远端 API 同字段 commit-tree，返回本地对象 sha（构造忠实前提=同字段同 sha）。"""
    env_extra = {
        "GIT_AUTHOR_NAME": author["name"], "GIT_AUTHOR_EMAIL": author["email"],
        "GIT_AUTHOR_DATE": when_iso,
        "GIT_COMMITTER_NAME": author["name"], "GIT_COMMITTER_EMAIL": author["email"],
        "GIT_COMMITTER_DATE": when_iso,
    }
    pargs = ["commit-tree", tree_sha]
    if parent_sha:
        pargs += ["-p", parent_sha]
    return git(pargs, repo_dir, input_bytes=message.encode("utf-8"),
               env_extra=env_extra).decode().strip()


# ------------------------------------------------- self-test（CI 可跑，零网络零凭据）

def self_test():
    import tempfile
    fails = []
    ok = lambda name: print("  PASS", name, flush=True)

    # 1) 索引解析
    sample = "100644 aaa111 0" + chr(9) + "b.txt" + chr(0)
    idx = parse_local_index(sample)
    if idx.get("b.txt") == ("100644", "aaa111"):
        ok("parse_local_index")
    else:
        fails.append("parse_local_index: %r" % idx)

    # 2) mode 感知 delta
    local = {"a.txt": ("100644", "s1"), "g": ("100755", "sG"), "n.txt": ("100644", "sN")}
    remote_same = {"a.txt": ("100644", "s1")}
    remote_mode_only = {"a.txt": ("100644", "s1"), "g": ("100644", "sG")}
    remote_content = {"a.txt": ("100644", "sOLD"), "g": ("100755", "sG")}
    d_content = compute_delta(local, remote_content)
    if (compute_delta(local, remote_same) == {"n.txt": ("100644", "sN"), "g": ("100755", "sG")}
            and compute_delta(local, remote_mode_only).get("g") == ("100755", "sG")
            and d_content.get("a.txt") == ("100644", "s1")
            and "g" not in d_content
            and "n.txt" in d_content):
        ok("compute_delta (mode-aware)")
    else:
        fails.append("compute_delta cases failed")

    # 3) removed
    if compute_removed(["a", "b"], ["b", "c"]) == ["c"]:
        ok("compute_removed")
    else:
        fails.append("compute_removed failed")

    # 4) fork guard
    if (fork_guard("", "head") is None
            and fork_guard("head", "head") is None
            and fork_guard("other", "head") is not None):
        ok("fork_guard")
    else:
        fails.append("fork_guard failed")

    # 5) 双写重建的 sha 确定性（同字段两次重建必须同 sha——GitHub 构造忠实性的本地前提）
    with tempfile.TemporaryDirectory() as td:
        repo = Path(td)
        git(["init", "-b", "main"], repo)
        (repo / "f.txt").write_bytes(b"hello\n")
        git(["add", "f.txt"], repo)
        tree = git(["write-tree"], repo).decode().strip()
        author = {"name": "T", "email": "t@example.test"}
        s1 = rebuild_local_commit(repo, tree, None, "msg\n", author, "2026-01-01T00:00:00Z")
        s2 = rebuild_local_commit(repo, tree, None, "msg\n", author, "2026-01-01T00:00:00Z")
        if s1 == s2:
            ok("dual-write determinism (same-field same-sha)")
        else:
            fails.append("dual-write determinism: %s != %s" % (s1[:10], s2[:10]))
        # 父链参与 sha：不同 parent 必产生不同 commit
        child1 = rebuild_local_commit(repo, tree, s1, "c\n", author, "2026-01-01T00:00:01Z")
        if child1 != s1:
            ok("dual-write parent linkage")
        else:
            fails.append("parent linkage failed")

    if fails:
        for f in fails:
            print("  FAIL", f, flush=True)
        return 1
    print("SELF-TEST PASSED", flush=True)
    return 0


# ------------------------------------------------- 主流程

def main():
    if "--self-test" in sys.argv:
        return self_test()
    rest = [a for a in sys.argv[1:] if not a.startswith("--")]
    args_msg = rest[0] if len(rest) >= 1 else None
    args_sha = rest[1] if len(rest) >= 2 else None
    token = TOKEN_FILE.read_text().strip()
    local = parse_local_index(subprocess.run(
        ["git", "ls-files", "-z", "-s"], cwd=str(REPO_DIR),
        capture_output=True, check=True).stdout.decode())
    ref = api("GET", "/repos/%s/%s/git/refs/heads/%s" % (OWNER, REPO, BR), token=token)
    head = ref["object"]["sha"]
    base = subprocess.run(["git", "rev-parse", "main"], cwd=str(REPO_DIR),
                          capture_output=True).stdout.decode().strip()
    guard = fork_guard(base, head)
    if guard:
        print(guard, flush=True)
        return 1
    commit = api("GET", "/repos/%s/%s/git/commits/%s" % (OWNER, REPO, head), token=token)
    base_tree = commit["tree"]["sha"]
    remote_tree = api("GET", "/repos/%s/%s/git/trees/%s?recursive=1" % (OWNER, REPO, base_tree), token=token)
    remote = {t["path"]: (t.get("mode"), t["sha"]) for t in remote_tree["tree"] if t["type"] == "blob"}
    print("local files:", len(local), "| remote blobs:", len(remote), flush=True)

    delta = compute_delta(local, remote)
    print("delta (new/changed/mode):", len(delta), flush=True)
    for p in sorted(delta)[:20]:
        print("  +", p, flush=True)
    removed = compute_removed(local.keys(), remote.keys())
    if removed:
        print("removed-from-local (will drop from tree):", removed, flush=True)

    def upload_blob(item):
        path, (mode, sha) = item
        # 归一字节上传：读索引 blob（git cat-file），不读工作树字节（CRLF 死循环教训）
        data = git(["cat-file", "blob", sha], REPO_DIR)
        blob = api("POST", "/repos/%s/%s/git/blobs" % (OWNER, REPO),
                   {"content": base64.b64encode(data).decode(), "encoding": "base64"}, token=token)
        return path, mode, blob["sha"]

    tree_items = []
    if delta:
        with ThreadPoolExecutor(max_workers=5) as pool:
            for path, mode, sha in pool.map(upload_blob, delta.items()):
                tree_items.append({"path": path, "mode": mode, "type": "blob", "sha": sha})
    for p in removed:
        tree_items.append({"path": p, "mode": "100644", "type": "blob", "sha": None})

    if not tree_items:
        print("nothing to sync")
        return 0
    tree = api("POST", "/repos/%s/%s/git/trees" % (OWNER, REPO),
               {"base_tree": base_tree, "tree": tree_items}, token=token)
    print("tree:", tree["sha"], flush=True)
    import datetime
    now_iso = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    msg = args_msg or ("sync: export pipeline delta (auto)" + chr(10) + chr(10) +
                       "internal master @ %s" % (args_sha or "unknown"))
    payload = {
        "message": msg, "tree": tree["sha"], "parents": [head],
        "author": {"name": AUTHOR["name"], "email": AUTHOR["email"], "date": now_iso},
        "committer": {"name": AUTHOR["name"], "email": AUTHOR["email"], "date": now_iso},
    }
    commit = api("POST", "/repos/%s/%s/git/commits" % (OWNER, REPO), payload, token=token)
    api("PATCH", "/repos/%s/%s/git/refs/heads/%s" % (OWNER, REPO, BR),
        {"sha": commit["sha"], "force": False}, token=token)
    print("main ->", commit["sha"], flush=True)
    # 同源双写（机制见文件头）
    lsf = git(["write-tree"], REPO_DIR).decode().strip()
    if lsf != tree["sha"]:
        print("WARN: local write-tree %s != remote tree %s; dual-write abort, ref untouched" % (
            lsf[:10], tree["sha"][:10]), flush=True)
        return 0
    local_sha = rebuild_local_commit(REPO_DIR, lsf, head, msg, AUTHOR, now_iso)
    if local_sha != commit["sha"]:
        print("WARN: dual-write sha mismatch local=%s remote=%s; keep local ref, manual check" % (
            local_sha[:10], commit["sha"][:10]), flush=True)
        return 0
    subprocess.run(["git", "update-ref", "refs/heads/main", local_sha], cwd=str(REPO_DIR), check=True)
    print("dual-write: local main -> %s (== remote)" % local_sha[:10], flush=True)
    n = api("GET", "/repos/%s/%s/git/trees/%s?recursive=1" % (OWNER, REPO, tree["sha"]), token=token)
    print("VERIFY: remote tree entries =", len(n.get("tree", [])), "truncated =", n.get("truncated"), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
