#!/usr/bin/env python3
"""文档一致性检查（C-13 机器门禁）。

检查项：
  1. 八类主干文档 D1–D8 存在，且头部含「创建 / 状态 / 权威范围」三字段；
  2. 记忆主文件 M1/M3 存在且头部字段齐全；
  3. Markdown 相对链接可达（文件/目录存在），.md 锚点能对上标题；
  4. 当前态文档不含已失效的字面陈述（旧版本号、旧工具数、旧测试数等）；
  5. D7 每个任务标题含状态关键字，任务编号不重复；
  6. D8 / M1 不落盘易漂移的测试数/评测分（数值现取纪律）。

用法：python scripts/check-docs.py
退出码：0 = 通过；1 = 有错误。警告不阻断。
"""

from __future__ import annotations

import os
import re
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

CORE_DOCS = [
    "D1-overview.md",
    "D2-architecture.md",
    "D3-decisions.md",
    "D4-progress-log.md",
    "D5-troubleshooting.md",
    "D6-operations.md",
    "D7-tasks.md",
    "D8-记忆索引.md",
]
MEMORY_DOCS = ["MEMORY.md", "CONVENTIONS.md"]
HEADER_FIELDS = ["创建：", "状态：", "权威范围："]

# i18n 已知未接线的键（T-140 登记）。
#
# 这些键在 messages_zh/messages_en 里都有，但生产代码里没有任何引用点——属于**存量死键**。
# 本清单只拦**新增**死键；清一条（接线或删除）收一条，与 lint 警告预算同口径的棘轮思路。
#
# 逐条说明（接线时照此接线即可从清单里删掉）：
#   error.unknown_method          —— MCP 未知方法的报错；当前 McpBridge 对未知 method
#                                   走的是协议级 -32601，尚未用上这条用户面文案。
#   error.db.param_required       —— 数据库工具的必填参数缺失文案；当前由各工具自己拼中文。
#   error.db.describe_table       —— db_describe_table 工具描述；当前工具描述是硬编码中文。
#   error.db.sample_rows          —— db_sample_rows 工具描述；同上。
#   error.db.column_distribution  —— db_column_distribution 工具描述；同上。
I18N_KNOWN_UNUSED = {
    "error.unknown_method",
    "error.db.param_required",
    "error.db.describe_table",
    "error.db.sample_rows",
    "error.db.column_distribution",
}

# 当前态文档的失效字面量（历史文档 D4/D5 与归档区不在此列）。
STALE_LITERALS = [
    # P-232/D7 收口后的旧口径（曾与同文档的“已清偿”条目自相矛盾，check-docs 当时没抓到）
    ("README.md", r"result\.tools` 共 \*\*48\*\*", "README 仍写 2026-11-06 的旧工具面数（现为按形态现取）"),
    ("docs/D26-行业顶尖对标评审.md", r"硬编码 gen_ai\*", "D26 仍写 gen_ai.* 硬编码（T-164 已建 GenAiAttrs 映射层）"),
    ("docs/D26-行业顶尖对标评审.md", r"\| \*\*缺口\*\* \|", "D26 协议表格仍写 MUST 缺口（已清零，见同文档评级行）——保留历史对照请用“评审当时结论”措辞"),
    ("docs/D26-行业顶尖对标评审.md", r"文档内嵌图表[^。]*未做", "D26 仍写文档内嵌图表未做（T-170 已引入 batik 三格式内嵌）"),
    ("docs/D1-overview.md", r"AD-001~019", "D1 的 AD 范围过期（应为 AD-001~026）"),
    ("docs/D1-overview.md", r"17 工具", "D1 仍写旧工具数（现为 44 条工具面）"),
    ("docs/D2-architecture.md", r"6 套件", "D2 仍写 6 套件（现为 12 个自建套件 + 1 个可选外部套件）"),
    ("docs/D2-architecture.md", r"8 套件", "D2 仍写旧套件数（现为 12 个自建套件 + 1 个可选外部套件）"),
    ("docs/D6-operations.md", r"8 套件", "D6 仍写旧套件数（现为 12 个自建套件 + 1 个可选外部套件）"),
    ("docs/D6-operations.md", r"9 个自建套件", "D6 仍写旧套件数（现为 12 个自建套件）"),
    ("docs/D6-operations.md", r"9 个自建", "D6 仍写旧套件数（现为 12 个自建套件）"),
    ("docs/D3-decisions.md", r"8 个自建套件", "D3 仍写旧套件数（现为 12 个自建套件）"),
    ("docs/D3-decisions.md", r"8 套件", "D3 仍写旧套件数（现为 12 个自建套件）"),
    ("docs/D1-overview.md", r"8 个自建套件", "D1 仍写旧套件数（现为 12 个自建套件）"),
    ("docs/D8-记忆索引.md", r"8 个自建套件", "D8 仍写旧套件数（现为 12 个自建套件）"),
    ("memory/MEMORY.md", r"8 个自建套件", "M1 仍写旧套件数（现为 12 个自建套件）"),
    ("docs/D2-architecture.md", r"81\.6", "D2 仍写旧分发体积（应给 du 命令现取）"),
    ("docs/D2-architecture.md", r"76\.8", "D2 仍写旧 DuckDB 体积（应给 du 命令现取）"),
    ("docs/D2-architecture.md", r"单线程服务", "D2 仍写 HTTP 单线程（现为固定 4 线程池）"),
    ("docs/D3-decisions.md", r"当前编译不通过", "D3 仍保留旧的编译中间态陈述"),
    ("docs/D3-decisions.md", r"状态：\*\*进行中\*\*", "D3 仍有「进行中」的旧决策状态"),
    ("docs/D6-operations.md", r"Kotlin\s*\|\s*1\.9\.24", "D6 仍写 Kotlin 1.9.24（现为 2.0.21）"),
    ("docs/D6-operations.md", r"1\.9\.24", "D6 仍写 Kotlin 1.9.24（现为 2.0.21）"),
    # 工具面规模：运行时事实源 = `--list-tools`（53 条，2026-11-11 实测）
    ("docs/D6-operations.md", r"4[89] 条工具", "D6 仍写旧工具面数（现为 53 条）"),
    ("docs/D6-operations.md", r"5[12] 条工具", "D6 仍写旧工具面数（现为 53 条）"),
    ("docs/D8-记忆索引.md", r"4[89] 条（", "D8 仍写旧工具面数（现为 53 条）"),
    ("docs/D8-记忆索引.md", r"4[89] 条工具快照", "D8 仍写旧工具面数（现为 53 条）"),
    ("docs/D1-overview.md", r"现值 4[89] 条", "D1 仍写旧工具面数（现为 53 条）"),
    ("docs/D1-overview.md", r"4[89] 条工具的运行时快照", "D1 仍写旧工具面数（现为 53 条）"),
    ("memory/MEMORY.md", r"工具面 4[89] 条", "M1 仍写旧工具面数（现为 53 条）"),
    ("memory/MEMORY.md", r"4[89] 条工具快照", "M1 仍写旧工具面数（现为 53 条）"),
    # 严格逐字段 schema：现为 0 条（全部宽松对象）
    ("docs/D8-记忆索引.md", r"严格逐字段 schema 1[0-9] 条", "D8 仍写严格 schema 条数（现为 0 条，全为宽松对象）"),
    ("memory/MEMORY.md", r"严格逐字段 schema 1[0-9] 条", "M1 仍写严格 schema 条数（现为 0 条，全为宽松对象）"),
    ("docs/D6-operations.md", r"本仓当前无远程", "D6 仍写「无远程」（origin 已存在）"),
    ("docs/D6-operations.md", r"全部提交仅本地", "D6 仍写「全部提交仅本地」（origin 已存在）"),
    ("docs/D6-operations.md", r"sync-report-libs\.mjs", "D6 仍写上游 Node 生成器（T-058 起为 scripts/gen-report-libs.py）"),
    ("memory/MEMORY.md", r"真 Python/pandas 子集", "M1 仍称 Python 子集未做（T-031 已完成）"),
    ("memory/MEMORY.md", r"需 Pyodide-WASM", "M1 仍称需 Pyodide（子进程沙箱已落地）"),
]

# D8/M1 中禁止落盘的「易漂移计数」形态。
DRIFT_COUNT_PATTERNS = [
    (r"\b\d{2,4}\s*例\s*(0 失败|全绿|通过)", "测试用例数不应写死在 D8/M1"),
    (r"\b\d{3}/\d{3}\b", "bench 通过数不应写死在 D8/M1"),
    (r"\b\d+\s*个提交\b", "提交数不应写死在 D8/M1"),
]

TASK_STATUS = ["待办", "进行中", "受阻", "待授权", "已完成", "作废", "收敛"]

LINK_RE = re.compile(r"\[([^\]]*)\]\(([^)]+)\)")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def slugify_heading(heading: str) -> str:
    """近似 GitHub 锚点：小写、保留文字/数字/下划线/连字符/空格，空格变连字符。"""
    text = heading.strip().lower()
    kept = "".join(ch for ch in text if ch.isalnum() or ch in "-_ \t")
    slug = kept.replace("\t", " ").replace(" ", "-")
    return slug.strip("-")


def collect_anchors(path: Path) -> set[str]:
    anchors: set[str] = set()
    in_fence = False
    for line in read(path).splitlines():
        if line.strip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = HEADING_RE.match(line)
        if m:
            anchors.add(slugify_heading(m.group(2)))
    return anchors


def iter_markdown_files() -> list[Path]:
    files = sorted(DOCS.glob("*.md")) + sorted((ROOT / "memory").glob("*.md"))
    logs = ROOT / "memory" / "logs"
    if logs.exists():
        files += sorted(logs.glob("*.md"))
    return files


def check_headers(errors: list[str]) -> None:
    for name in CORE_DOCS + MEMORY_DOCS:
        path = DOCS / name if name in CORE_DOCS else ROOT / "memory" / name
        if not path.exists():
            errors.append(f"[缺失] {path.relative_to(ROOT)} 不存在")
            continue
        head = "\n".join(read(path).splitlines()[:8])
        for field in HEADER_FIELDS:
            if field not in head:
                errors.append(f"[头字段] {path.relative_to(ROOT)} 前 8 行缺少「{field}」")


def check_links(errors: list[str], warnings: list[str]) -> None:
    anchor_cache: dict[Path, set[str]] = {}
    for file in iter_markdown_files():
        in_fence = False
        for lineno, line in enumerate(read(file).splitlines(), 1):
            if line.strip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for _, raw_target in LINK_RE.findall(line):
                target = raw_target.strip()
                if target.startswith(("http://", "https://", "mailto:", "tel:")):
                    continue
                if target.startswith("<") and target.endswith(">"):
                    target = target[1:-1]
                path_part, _, anchor = target.partition("#")
                path_part = urllib.parse.unquote(path_part)
                anchor = urllib.parse.unquote(anchor)
                if path_part:
                    resolved = (file.parent / path_part).resolve()
                    try:
                        rel = resolved.relative_to(ROOT)
                    except ValueError:
                        rel = resolved
                    if not resolved.exists():
                        errors.append(
                            f"[链接] {file.relative_to(ROOT)}:{lineno} → {target}（目标不存在：{rel}）"
                        )
                        continue
                    if anchor and resolved.is_file() and resolved.suffix == ".md":
                        if resolved not in anchor_cache:
                            anchor_cache[resolved] = collect_anchors(resolved)
                        if anchor not in anchor_cache[resolved]:
                            errors.append(
                                f"[锚点] {file.relative_to(ROOT)}:{lineno} → {target}（标题锚点不存在）"
                            )
                else:
                    if anchor and anchor not in collect_anchors(file):
                        errors.append(
                            f"[锚点] {file.relative_to(ROOT)}:{lineno} → {target}（本文件标题锚点不存在）"
                        )
                    elif not anchor:
                        warnings.append(f"[链接] {file.relative_to(ROOT)}:{lineno} 空链接")


def check_stale_literals(errors: list[str]) -> None:
    for rel, pattern, reason in STALE_LITERALS:
        path = ROOT / rel
        if not path.exists():
            continue
        for lineno, line in enumerate(read(path).splitlines(), 1):
            if re.search(pattern, line):
                errors.append(f"[失效陈述] {rel}:{lineno} {reason}")


def check_task_states(errors: list[str]) -> None:
    path = DOCS / "D7-tasks.md"
    if not path.exists():
        return
    seen: dict[str, int] = {}
    for lineno, line in enumerate(read(path).splitlines(), 1):
        m = re.match(r"^##\s+(T-\d+)\b", line)
        if not m:
            continue
        task = m.group(1)
        if task in seen:
            errors.append(f"[任务] D7:{lineno} {task} 重复（首次出现在第 {seen[task]} 行）")
        seen[task] = lineno
        if not any(key in line for key in TASK_STATUS):
            errors.append(f"[任务] D7:{lineno} {task} 标题缺少状态关键字（{'/'.join(TASK_STATUS)}）")


def check_drift_counts(errors: list[str]) -> None:
    for name in ["D8-记忆索引.md", "MEMORY.md"]:
        path = DOCS / name if name.startswith("D") else ROOT / "memory" / name
        if not path.exists():
            continue
        for lineno, line in enumerate(read(path).splitlines(), 1):
            for pattern, reason in DRIFT_COUNT_PATTERNS:
                if re.search(pattern, line):
                    errors.append(f"[数值纪律] {path.relative_to(ROOT)}:{lineno} {reason}")


def check_d26_rating_consistency(errors: list[str]) -> None:
    """D26 §二的八维评级必须与 §三评分表同一维的评级同类别（P-232）。

    2026-11-16 的状态漂移就是这类：§二③标题写「部分落后（有 MUST 缺口）」，
    §三表格写「持平偏上」；§二⑦写「持平」，§三写「持平偏上」。语义检查难，
    但**同一文档、同一维号、同一评级概念**的结构性对拍是精确且零误报的。
    """
    path = DOCS / "D26-行业顶尖对标评审.md"
    if not path.exists():
        return
    lines = read(path).splitlines()
    circled = "①②③④⑤⑥⑦⑧"

    def category(text: str) -> str | None:
        # 顺序要紧：先判更具体的复合评级
        for key, name in [
            ("补强完成", "done"),
            ("部分落后", "partial"),
            ("持平偏上", "above"),
            ("路线差异", "route"),
            ("领先", "lead"),
            ("持平", "same"),
            ("落后", "behind"),
        ]:
            if key in text:
                return name
        return None

    section2: dict[str, tuple[int, str | None]] = {}
    for lineno, line in enumerate(lines, 1):
        for dim in circled:
            if line.startswith(f"### {dim} ") and dim not in section2:
                cat = category(line)
                if cat:
                    section2[dim] = (lineno, cat)

    section3: dict[str, tuple[int, str | None]] = {}
    for lineno, line in enumerate(lines, 1):
        if not line.startswith("| "):
            continue
        for dim in circled:
            if line.startswith(f"| {dim} ") and dim not in section3:
                # 表格第 4 列是评级（| 维 | 参照 | str-agent | 评级 | 证据 |）
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                if len(cells) >= 4:
                    cat = category(cells[3])
                    if cat:
                        section3[dim] = (lineno, cat)

    for dim in circled:
        if dim not in section2 or dim not in section3:
            continue  # 文档结构变了（维度增删）→ 交给其它规则，不在这里误报
        l2, c2 = section2[dim]
        l3, c3 = section3[dim]
        if c2 != c3:
            errors.append(
                f"[评级漂移] D26 {dim} 维：§二:{l2}=「{c2}」与 §三:{l3}=「{c3}」不一致"
                "（同一维的评级必须同类别；改现状时同步两处）"
            )


def check_navigation(errors: list[str]) -> None:
    d1 = DOCS / "D1-overview.md"
    d8 = DOCS / "D8-记忆索引.md"
    required = [
        "D2-architecture.md",
        "D3-decisions.md",
        "D4-progress-log.md",
        "D5-troubleshooting.md",
        "D6-operations.md",
        "D7-tasks.md",
        "D8-记忆索引.md",
        "mcp-tool-surface.md",
        "D15-upgrade-report.md",
    ]
    for name, text in [("D1", read(d1) if d1.exists() else ""), ("D8", read(d8) if d8.exists() else "")]:
        for token in required:
            if token not in text:
                errors.append(f"[导航] {name} 缺少对 {token} 的指针")


def check_adr_index(errors: list[str]) -> None:
    """T-078（D21 D-2）：D3 的 ADR 索引块由脚本生成，漂移即错（生成器是唯一更新路径）。"""
    import subprocess
    script = ROOT / "scripts" / "gen-adr-index.py"
    if not script.exists():
        errors.append("[ADR索引] scripts/gen-adr-index.py 缺失")
        return
    proc = subprocess.run(
        [sys.executable, str(script), "--check"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )
    if proc.returncode != 0:
        detail = (proc.stdout or proc.stderr or "").strip().splitlines()
        errors.append("[ADR索引] " + (detail[0] if detail else "gen-adr-index.py --check 失败"))


def check_runtime_facts(errors: list[str]) -> None:
    """**当前态文档的数字必须与运行时事实源一致**（防漂移自动化）。

    事实源：
      - 工具面规模 = `host` 运行时的 `tools/list`（`stragent_` 规范名计数）；
      - bench 自建套件数 = `BenchMain.kt` 的 `suites` 列表项数（外部套件另计）。

    这两条曾多次漂移（文档写 48/51/52、套件写 8/9/10，实际 53/12），
    靠人工同步不可靠——本检查让漂移**当场变红**。
    """
    # --- 工具面规模：从工具面文档的「### `stragent_xxx`」条目数取（该文档由运行时目录生成）
    surface = DOCS / "mcp-tool-surface.md"
    if surface.exists():
        entries = len(re.findall(r"(?m)^###\s+`stragent_[a-z_]+`", read(surface)))
        head = "\n".join(read(surface).splitlines()[:6])
        m = re.search(r"工具总数：\*\*(\d+)\*\*", head)
        if m and int(m.group(1)) != entries:
            errors.append(
                f"[工具面] mcp-tool-surface.md 头部声明 {m.group(1)} 条，"
                f"但正文实列 {entries} 条——两者必须一致（以运行时 --list-tools 为准）"
            )
    # --- bench 自建套件数：从 BenchMain.kt 的 suites 列表数 run*() 调用（externalSuite 另计）
    bench_main = ROOT / "bench" / "src" / "main" / "kotlin" / "com" / "stragent" / "bench" / "BenchMain.kt"
    if bench_main.exists():
        block = read(bench_main)
        seg = block.split("val suites = listOf(", 1)
        if len(seg) == 2:
            # 取到该 listOf(...) 的配平右括号——不能按第一个 ')' 截断（run*() 自带括号）
            depth = 1
            body_chars = []
            for ch in seg[1]:
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        break
                body_chars.append(ch)
            body = "".join(body_chars)
            # 每个 run*() 调用计 1（runConcurrencyBaseline(updateBaseline) 亦计 1）
            self_built = len(re.findall(r"\brun[A-Z]\w*\(", body))
            for rel in ["docs/D2-architecture.md", "docs/D6-operations.md"]:
                path = ROOT / rel
                if not path.exists():
                    continue
                for lineno, line in enumerate(read(path).splitlines(), 1):
                    m = re.search(r"(\d+)\s*个?自建套件", line)
                    if m and int(m.group(1)) != self_built:
                        errors.append(
                            f"[失效陈述] {rel}:{lineno} 写 {m.group(1)} 个自建套件，"
                            f"实际 {self_built} 个（事实源：bench/BenchMain.kt 的 suites 列表）"
                        )


def check_i18n_key_usage(errors: list[str]) -> None:
    """i18n 消息表：①中英键集必须一致 ②每个键必须被生产代码引用。

    ①键集一致：`Messages.get` 按 locale 选表——某一侧缺键，那一侧就回退成
      **键名本身**（如 `error.db.param_required` 直接出现在用户面）。这类漂移
      靠人工同步不可靠（README 曾长期写着「messages_en 仅覆盖框架性键」，
      实际早就是全量双语）。

    ②键被引用：定义了却没人用的键 = 死键。它要么是接线漏了（用户面拿不到
      预期文案），要么是需求变了但没删——两种都会误导后来人。

    已知未接线的键列在 [I18N_KNOWN_UNUSED]：本检查**只拦新增**，存量不红
    （清一条收一条，与 lint 警告预算同口径的棘轮思路）。
    """
    zh_path = ROOT / "core/src/main/resources/i18n/messages_zh.properties"
    en_path = ROOT / "core/src/main/resources/i18n/messages_en.properties"
    if not zh_path.exists() or not en_path.exists():
        return

    def keys_of(path: Path) -> list[str]:
        out = []
        for line in read(path).splitlines():
            s = line.strip()
            if not s or s.startswith(("#", "!")) or "=" not in s:
                continue
            out.append(s.split("=", 1)[0].strip())
        return out

    zh_keys = keys_of(zh_path)
    en_keys = keys_of(en_path)
    zh_set, en_set = set(zh_keys), set(en_keys)
    for k in sorted(zh_set - en_set):
        errors.append(f"[i18n] messages_en.properties 缺键 {k}（英文宿主会回退成键名本身）")
    for k in sorted(en_set - zh_set):
        errors.append(f"[i18n] messages_zh.properties 缺键 {k}（中文宿主会回退成键名本身）")

    # 生产代码里对键的引用（"key" 形式）
    sources = []
    for mod in ("core", "str-agent", "data", "host"):
        base = ROOT / mod / "src" / "main"
        if base.exists():
            sources += list(base.rglob("*.kt"))
    blob = "\n".join(read(p) for p in sources)
    for k in zh_keys:
        if f'"{k}"' in blob:
            continue
        if k in I18N_KNOWN_UNUSED:
            continue
        errors.append(
            f"[i18n] 键 {k} 定义了却没有任何生产代码引用（死键：要么接线漏了，"
            f"要么该删）——如已确属无用，请登记进 I18N_KNOWN_UNUSED"
        )


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    errors: list[str] = []
    warnings: list[str] = []

    check_headers(errors)
    check_links(errors, warnings)
    check_stale_literals(errors)
    check_task_states(errors)
    check_drift_counts(errors)
    check_d26_rating_consistency(errors)
    check_navigation(errors)
    check_adr_index(errors)
    check_runtime_facts(errors)
    check_i18n_key_usage(errors)

    print("=== 文档一致性检查（C-13）===")
    print(f"检查文档：{len(iter_markdown_files())} 个 Markdown 文件")
    print(f"错误：{len(errors)} ｜ 警告：{len(warnings)}")
    if warnings:
        print("\n--- 警告 ---")
        for w in warnings:
            print(f"  {w}")
    if errors:
        print("\n--- 错误 ---")
        for e in errors:
            print(f"  {e}")
        print("\nFAILED: 文档一致性检查未通过")
        return 1
    print("\nPASSED: 文档一致性检查通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
