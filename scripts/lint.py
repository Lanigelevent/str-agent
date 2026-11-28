#!/usr/bin/env python3
"""
D15 T5 + T-072：静态分析脚本（零依赖，--offline 友好）。

检查项（每条都是真实破坏场景）：
  1. System.err.println / System.out.println 禁止出现在 main 源码
  2. 公开 public 类/函数必须有 KDoc 注释
  3. 无 TODO / FIXME / HACK 遗留标记（测试源码除外）
  4. 无硬编码中文字符串字面量（i18n 资源文件除外）
  5. 无 import 通配符（import *）
  6. 无空 catch 块（catch (e: Exception) { /* 空 */ ）
  7. 报告库生成物与 scripts/reportlib/*.json 逐字节一致（T-058 生成器 --check）

警告预算制（T-072，D21 Q-1）：
  WARNING 不再只是提示——基线（scripts/lint-baseline.json，per-file 计数）之外
  新增任何警告都升级为 ERROR：
    - 不在基线的文件出现任何警告 = ERROR（新代码必须零警告）
    - 文件警告数超过基线 = ERROR（存量文件不许新增）
    - 预算只收不放：--update-baseline 拒绝上调任何计数
    - 存量周清：基线文件即清理清单（按计数降序），清一批收窄一批
  输出：发现数 + 违规文件/行号 + 严重级别（ERROR/WARNING）
"""

import os, re, sys, json, subprocess, datetime
from pathlib import Path

# 仓库根 = 脚本所在目录的上一级（不再硬编码某台机器的绝对路径）
ROOT = Path(__file__).resolve().parent.parent

# 各模块的 main/test 源码目录
MODULES = ["core", "str-agent", "data", "host", "engine-duckdb"]

# 规则：正则 -> (严重级别, 描述)
RULES = [
    ("ERROR", r"System\.(err|out)\.print(ln)?\(", "System.err/out.println 禁止出现在 main 源码"),
    ("ERROR", r"public\s+(class|fun|val|var|interface|object)\s+\w+[^:{]*$", "公开声明缺少 KDoc（需人工复核）"),
    ("WARNING", r"\b(TODO|FIXME|HACK|XXX)\b", "遗留标记（测试源码除外）"),
    ("WARNING", r"[\u4e00-\u9fff]{2,}", "硬编码中文字符串（i18n 资源文件除外）"),
    ("ERROR", r"import\s+[\w.]+\*", "import 通配符禁止"),
    ("ERROR", r"catch\s*\(\s*\w+\s*:\s*\w+Exception\s*\)\s*\{\s*\}", "空 catch 块"),
]

# 排除目录/文件
EXCLUDE_DIRS = {"build", ".gradle", "node_modules", "i18n", "resources/i18n"}
EXCLUDE_FILES = {"Messages.kt", "messages_zh.properties", "messages_en.properties"}

# 硬编码中文规则的**内容型豁免**（T-087，Q-4 存量周清的第一批）：
# 这些文件的中文是**产品内容本体**，不是「该走 i18n 的 UI 文案」——
#   1. 生成文件（gen-report-libs.py 产出，勿手改）：中文是数据词表（行业信号词等），
#      且已有更强的门禁——gen-report-libs.py --check 与规范源逐字节比对（T-058）；
#      把 700 条数据字面量 i18n 化是方向性错误，逐行 lint:allow 是噪声。
#   2. Prompt 模板（InsightPrompt 等）：中文 prompt 是产品能力本身（报告以中文产出），
#      多语言 prompt 是独立的语义工程，不属于 lint 清理范畴。
# 豁免只作用于「硬编码中文字符串」一条规则；其余规则（通配符/System.err/TODO）照扫。
# 判据不放宽：新写的 UI/工具文案仍必须走 i18n（负控在 lint.py 自身语义里——豁免清单外零容忍）。
CHINESE_RULE_EXEMPT_FILES = {
    # gen-report-libs.py 产出（三库数据，字节级门禁另有其人）
    "core/src/main/kotlin/com/stragent/core/report/IndustryMatchData.kt",
    "core/src/main/kotlin/com/stragent/core/report/ReportLibData.kt",
    "core/src/main/kotlin/com/stragent/core/report/ReportCasesData.kt",
    # Prompt 模板与报告语料（中文是产品内容，非 UI 文案）
    "core/src/main/kotlin/com/stragent/core/ai/InsightPrompt.kt",
    "core/src/main/kotlin/com/stragent/core/brief/DatasetBrief.kt",
    "core/src/main/kotlin/com/stragent/core/report/ReportChapters.kt",
    "core/src/main/kotlin/com/stragent/core/report/WritingRules.kt",
    # T-192 ReviewerAgent：断言线索词表（CLAIM_TRACE_MARKERS/GENERIC）是产品词表数据，
    # 与 IndustryMatchData 同性质（词表即内容本体）；整文件豁免（文件内其余中文均为
    # 审计结论文案与既有基线同源）
    "core/src/main/kotlin/com/stragent/core/report/ReviewerAgent.kt",
    # T-096 TextSummarizer：提示词模板 + zh-first 用户面文案（产品语言定位 D19/D21），
    # prompt 不是 i18n 对象——与 InsightPrompt.kt 同类，不做行级伪装记账
    "core/src/main/kotlin/com/stragent/core/ai/TextSummarizer.kt",
    # T-186 ExternalDab：评测口径文案（SKIP notes/error 消息）是评测证据本体，非 i18n UI；
    # 与 Spider 套件（ExternalBench.kt 存量基线）同性质
    "bench/src/main/kotlin/com/stragent/bench/external/ExternalDab.kt",
}

# 行内白名单：在代码行尾写 `// lint:allow <理由>`。
# 白名单必须贴着违规行，换行/重构后不会像行号白名单那样静默失效。
ALLOW_MARKER = "lint:allow"

# ---- 需要「作用域」判断的规则（单行正则表达不了，单独处理）----
#
# 热路径正则未预编译：`Regex(...)` 写在**函数体内**的表达式里 → 每次调用都重新编译
# Pattern。仓内正确先例：core/verify/VerifyCore.kt:17-38 全部 `private val` 预编译。
#
# 判据为什么必须看作用域：`private val R = listOf(Rule("R1", ..., Regex("...")))`
# 里的 `Regex(` 是**类属性初始化**——只编译一次，不是热路径。行级判据分不出
# 「属性初始化」与「函数体内的表达式」，本仓存量里两者各占约一半（误报率 50% 的规则
# 等于没有规则）。
#
# 作用域跟踪：逐行扫描，把字符串字面量与注释掩码成等长空格（保留列位），
# 再按 `{`/`}` 维护作用域栈：
#   - `fun` 开头 → 函数体作用域（热路径候选）
#   - `class`/`object`/`interface`/`enum` → 类体作用域（属性初始化，不是热路径）
#   - 其他 `{`（lambda、if、listOf 的花括号形式等）→ 继承当前作用域
HOT_PATH_REGEX = re.compile(r"Regex\(")
SCOPE_FUN = re.compile(r"\bfun\b")
SCOPE_CLASS = re.compile(r"\b(?:class|object|interface|enum)\b")

# 热路径正则的按文件配额表（存量清单 = 清理清单）
EXEMPT_PATH = ROOT / "scripts" / "lint-hotpath-exempt.txt"

# 裸 XML 解析：唯一允许的文件 = SafeXml（全仓唯一的 XML 入口）。
# 依据 OWASP《XXE Prevention Cheat Sheet》：XML 解析统一收口 + 禁外部实体。
RAW_XML_PATTERN = re.compile(
    r"\b(?:SAXParserFactory|DocumentBuilderFactory|XMLInputFactory|TransformerFactory)\s*\.\s*newInstance\s*\("
)
RAW_XML_ALLOWED_FILES = {
    "core/src/main/kotlin/com/stragent/core/security/SafeXml.kt",
}

# 警告预算基线（T-072）。键一律是仓库相对路径（正斜杠），与平台无关。
BASELINE_PATH = ROOT / "scripts" / "lint-baseline.json"


def iter_kotlin_files():
    """遍历所有模块的 main 和 test Kotlin 源码。"""
    for module in MODULES:
        for kind in ["main", "test"]:
            base = ROOT / module / "src" / kind / "kotlin"
            if not base.exists():
                continue
            for f in base.rglob("*.kt"):
                rel = f.relative_to(ROOT)
                # 排除 build 等目录
                if any(part in EXCLUDE_DIRS for part in f.parts):
                    continue
                if f.name in EXCLUDE_FILES:
                    continue
                yield f, rel, kind


def lint():
    findings = []
    for fpath, rel, kind in iter_kotlin_files():
        try:
            lines = fpath.read_text(encoding="utf-8").splitlines()
        except Exception:
            continue

        is_main = kind == "main"

        for lineno, line in enumerate(lines, 1):
            # 跳过注释行（简单 heuristic：//、*（多行注释主体）、/*（块注释开行，
            # 含单行 KDoc `/** 中文 */` 形态）开头。
            # T-088：`/*` 前缀补进启发式——多行 KDoc 主体与 // 注释一直豁免，单行
            # KDoc 被抓纯属前缀盲区（955 条假阳性，占存量 37%）。规则意图是抓
            # **字符串字面量**里的硬编码中文——字符串行以 `val`/代码开头，不受影响。
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue

            for level, pattern, desc in RULES:
                # 某些规则只在 main 源码检查
                if "System.err" in pattern and not is_main:
                    continue
                if "TODO" in pattern and not is_main:
                    continue
                if "硬编码中文" in desc and not is_main:
                    continue
                # 内容型豁免（T-087）：中文是产品内容本体（数据词表/prompt 模板）的文件，
                # 不适用「硬编码中文」规则——理由见 CHINESE_RULE_EXEMPT_FILES 定义处
                if "硬编码中文" in desc and str(rel).replace("\\", "/") in CHINESE_RULE_EXEMPT_FILES:
                    continue

                if re.search(pattern, line):
                    # 行内白名单（贴着代码行，重构后不会静默失效）
                    if ALLOW_MARKER in line:
                        continue
                    # 测试代码允许通配符导入（kotlin.test.* 等）
                    if "import 通配符禁止" in desc and kind == "test":
                        continue
                    findings.append({
                        "file": str(rel),
                        "line": lineno,
                        "level": level,
                        "rule": desc,
                        "code": line.strip()[:120],
                    })

            # 热路径正则未预编译：由 check_hot_path_regex() 单独判定（需要作用域跟踪，
            # 且走按文件配额制，不进 warning 预算）

    return findings


def mask_code(line, state):
    """把字符串字面量与注释替换成**等长空格**（保留列位），供括号/关键字统计使用。

    不做掩码的话，"注释里写了 Regex(" 与 "字符串里写了 Regex(" 都会被误判，
    而字符串里出现正则片段在测试数据里很常见（见注入语料库）。

    state: {'raw': bool} —— 跨行三引号字符串的状态。
    """
    out = []
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if state["raw"]:
            if line.startswith('"""', i):
                out.append("   ")
                i += 3
                state["raw"] = False
                continue
            out.append(" ")
            i += 1
            continue
        if line.startswith('"""', i):
            out.append("   ")
            i += 3
            state["raw"] = True
            continue
        if c == '"':
            out.append(" ")
            i += 1
            while i < n:
                if line[i] == "\\" and i + 1 < n:
                    out.append("  ")
                    i += 2
                    continue
                if line[i] == '"':
                    out.append(" ")
                    i += 1
                    break
                out.append(" ")
                i += 1
            continue
        if c == "'":
            out.append(" ")
            i += 1
            while i < n:
                if line[i] == "\\" and i + 1 < n:
                    out.append("  ")
                    i += 2
                    continue
                if line[i] == "'":
                    out.append(" ")
                    i += 1
                    break
                out.append(" ")
                i += 1
            continue
        if line.startswith("//", i):
            out.append(" " * (n - i))
            break
        if line.startswith("/*", i):
            end = line.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append(" " * (end - i))
            i = end
            continue
        out.append(c)
        i += 1
    return "".join(out)


def hot_path_regex_violations(lines):
    """返回 [(行号, 原始行)]——**函数体内**的内联 `Regex(`。

    作用域栈：'file' / 'class' / 'fun'。只有 'fun' 作用域里的内联 Regex 是热路径
    （类体里的是属性初始化，只编译一次）。

    处理顺序：**先**按本行的花括号更新作用域栈，**再**判本行的 Regex——
    这样 `fun f() { if (Regex(...) }` 这类单行写法也能被正确识别为函数体。
    """
    state = {"raw": False}
    stack = ["file"]
    out = []
    for lineno, raw_line in enumerate(lines, 1):
        masked = mask_code(raw_line, state)
        for i, ch in enumerate(masked):
            if ch == "{":
                before = masked[:i]
                if SCOPE_FUN.search(before):
                    stack.append("fun")
                elif SCOPE_CLASS.search(before):
                    stack.append("class")
                else:
                    stack.append(stack[-1])
            elif ch == "}" and len(stack) > 1:
                stack.pop()
        fun_pos = masked.find("fun")
        for m in HOT_PATH_REGEX.finditer(masked):
            # 一行内既出现 `fun` 又出现 Regex：Regex 在 fun 之后 → 函数体
            if fun_pos >= 0 and m.start() > fun_pos:
                out.append((lineno, raw_line))
                break
            if stack[-1] == "fun":
                out.append((lineno, raw_line))
                break
    return out


def check_raw_xml_parsing():
    """裸 XML 解析——只允许走 SafeXml（唯一 XML 入口）。

    依据 OWASP《XXE Prevention Cheat Sheet》：XML 解析必须**统一收口**并禁外部实体。
    散落的 `SAXParserFactory.newInstance()` 是 XXE 的温床——JDK 默认**会**解析外部
    实体（实测三项特性默认全为 true），每一处裸调用都是一个可被恶意文档触发的
    本地文件读 / SSRF 入口。

    级别 ERROR：这是安全边界，不是风格偏好。
    """
    findings = []
    for fpath, rel, kind in iter_kotlin_files():
        if kind != "main":
            continue
        key = norm_path(rel)
        if key in RAW_XML_ALLOWED_FILES:
            continue
        try:
            lines = fpath.read_text(encoding="utf-8").splitlines()
        except Exception:
            continue
        for lineno, line in enumerate(lines, 1):
            if RAW_XML_PATTERN.search(line):
                findings.append({
                    "file": key,
                    "line": lineno,
                    "level": "ERROR",
                    "rule": ("裸 XML 解析（必须走 com.stragent.core.security.SafeXml——"
                             "JDK 默认会解析外部实体，散落的裸调用是 XXE 入口）"),
                    "code": line.strip()[:120],
                })
    return findings


def check_hot_path_regex():
    """热路径正则未预编译——按文件配额判 ERROR（配额表见 scripts/lint-hotpath-exempt.txt）。

    - 不在配额表里的文件：任何函数体内的内联 Regex 都报 ERROR；
    - 在配额表里的文件：超过配额的部分逐条报 ERROR（存量不许增长）。
    配额表同时是**清理清单**：清一批 → 调低配额 → 该类违规被彻底堵死。

    级别取 ERROR（不进 warning 预算）：这条规则要有牙齿——「新文件里的热路径正则」
    是 warning 预算看不见的（那个文件本来 0 条 warning），只有 ERROR 才拦得住。
    """
    quota = {}
    if EXEMPT_PATH.exists():
        for line in EXEMPT_PATH.read_text(encoding="utf-8").splitlines():
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            parts = s.rsplit(" ", 1)
            if len(parts) == 2 and parts[1].isdigit():
                quota[parts[0].strip()] = int(parts[1])

    findings = []
    for fpath, rel, kind in iter_kotlin_files():
        # 只管生产代码：测试里的内联正则不构成热路径（跑一次的事）
        if kind != "main":
            continue
        try:
            lines = fpath.read_text(encoding="utf-8").splitlines()
        except Exception:
            continue
        violations = hot_path_regex_violations(lines)
        if not violations:
            continue
        key = norm_path(rel)
        allowed = quota.get(key)
        for idx, (lineno, code) in enumerate(violations):
            if allowed is None:
                findings.append({
                    "file": key,
                    "line": lineno,
                    "level": "ERROR",
                    "rule": ("热路径正则未预编译（函数体内的 Regex(...) 每次调用都重新编译 "
                             "Pattern，应提为 private val 单例）"),
                    "code": code.strip()[:120],
                })
            elif idx >= allowed:
                findings.append({
                    "file": key,
                    "line": lineno,
                    "level": "ERROR",
                    "rule": (f"热路径正则未预编译（该文件配额 {allowed} 条已用尽，超出部分"
                             f"不许新增；清一批即可下调配额）"),
                    "code": code.strip()[:120],
                })
    return findings


def norm_path(p):
    """仓库相对路径统一成正斜杠（基线键与平台无关）。"""
    return str(p).replace("\\", "/")


def load_baseline():
    """读基线。返回 (files dict, None) 或 (None, 错误消息)。

    基线缺失/非法时按「不可用」处理——预算制 fail closed：
    此时凡有警告的文件都会报 ERROR，绝不在无法核对预算时放行。
    """
    if not BASELINE_PATH.exists():
        return None, f"基线文件缺失：{BASELINE_PATH.name}（python scripts/lint.py --update-baseline 生成）"
    try:
        raw = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        return None, f"基线文件解析失败：{e}"
    files = raw.get("files")
    if not isinstance(files, dict):
        return None, "基线格式非法：缺少 files 对象"
    for k, v in files.items():
        if not isinstance(v, int) or v < 1:
            return None, f"基线格式非法：{k} = {v!r}（必须是 >=1 的整数）"
    return files, None


def budget_check(warning_findings):
    """T-072 警告预算门禁。

    返回 (budget_errors, notices, counts)：
      budget_errors — 预算违规（并入 findings，直接拉红退出码）
      notices       — 收窄提示/陈旧条目提示（不拦截，但每次都看得见）
      counts        — 当前 per-file 警告计数（供报告与 --update-baseline 复用）
    """
    counts = {}
    for f in warning_findings:
        key = norm_path(f["file"])
        counts[key] = counts.get(key, 0) + 1

    baseline, err = load_baseline()
    if err is not None:
        fake = {"file": "scripts/lint-baseline.json", "line": 0, "rule": f"警告预算基线不可用：{err}", "code": ""}
        return [{"level": "ERROR", **fake}], [], counts

    budget_errors = []
    notices = []
    for key in sorted(counts):
        cur = counts[key]
        base = baseline.get(key)
        if base is None:
            budget_errors.append({
                "file": key, "line": 0, "level": "ERROR",
                "rule": "新文件带警告（预算制：新代码必须零警告，或该行 lint:allow 带理由）",
                "code": f"当前 {cur} 条警告，基线无此文件",
            })
        elif cur > base:
            budget_errors.append({
                "file": key, "line": 0, "level": "ERROR",
                "rule": "超出警告预算（存量文件不许新增警告：修掉或 lint:allow 带理由）",
                "code": f"当前 {cur} 条 > 基线 {base} 条（新增 {cur - base} 条）",
            })

    for key in sorted(baseline):
        cur = counts.get(key, 0)
        if not (ROOT / key).exists():
            notices.append(f"陈旧基线条目（文件已不存在）：{key}（{baseline[key]} 条）—— --update-baseline 清除")
        elif cur < baseline[key]:
            notices.append(f"基线可收窄：{key} 当前 {cur} 条 < 基线 {baseline[key]} 条 —— --update-baseline 收窄")

    return budget_errors, notices, counts


def write_report(errors, warnings, findings, notices, counts):
    result = {
        "errors": len(errors),
        "warnings": len(warnings),
        "budget": {
            "baseline": norm_path(BASELINE_PATH.relative_to(ROOT)),
            "filesWithWarnings": len(counts),
            "totalWarnings": sum(counts.values()),
            "notices": notices,
        },
        "findings": findings,
    }
    (ROOT / "build" / "static-analysis-report.json").parent.mkdir(parents=True, exist_ok=True)
    (ROOT / "build" / "static-analysis-report.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def update_baseline(counts):
    """T-072：把当前警告计数写进基线。

    预算只收不放——基线已存在时，任何计数上调都被拒绝；
    基线文件不存在时允许一次性创建（初建快照随提交入库，可被 review 审）。
    """
    bootstrap = not BASELINE_PATH.exists()
    baseline, err = load_baseline()
    if err is not None:
        if not bootstrap:
            print(f"FAILED: {err}")
            sys.exit(1)
        baseline = {}

    increases = []
    if not bootstrap:  # 初建快照不做拒升检查（空基线对照一切文件都是「上调」）
        for key in sorted(counts):
            old = baseline.get(key, 0)
            if counts[key] > old:
                increases.append((key, counts[key], old))
    if increases:
        print("FAILED: 预算只收不放（ratchet），以下文件当前警告数超过基线，拒绝上调：")
        for key, cur, old in increases:
            print(f"  {key}: 当前 {cur} 条 > 基线 {old} 条")
        print("先修掉新增警告（或对合理保留行加 `// lint:allow <理由>`），再执行 --update-baseline。")
        sys.exit(1)

    new_files = {key: counts[key] for key in sorted(counts) if counts[key] > 0}
    removed = sorted(set(baseline) - set(new_files))
    shrunk = sorted(k for k in new_files if k in baseline and new_files[k] < baseline[k])

    payload = {
        "_comment": (
            "lint 警告预算基线（T-072，D21 Q-1）。键=仓库相对路径（正斜杠），值=该文件允许的警告上限。"
            "不在基线的文件出现任何警告=ERROR；超出计数=ERROR；预算只收不放（--update-baseline 拒绝上调）。"
            "存量周清：按计数降序清理，清完跑 python scripts/lint.py --update-baseline 收窄。手改无效（会被 --update-baseline 重写）。"
        ),
        "updated": datetime.date.today().isoformat(),
        "total": sum(new_files.values()),
        "files": new_files,
    }
    BASELINE_PATH.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print("=== Warning Baseline Updated (T-072) ===")
    print(f"Total warnings: {payload['total']} across {len(new_files)} files")
    print(f"Shrunk: {len(shrunk)} file(s), removed: {len(removed)} entry/ies")
    for key in shrunk[:10]:
        print(f"  shrunk: {key} {baseline[key]} -> {new_files[key]}")
    for key in removed[:10]:
        print(f"  removed: {key}")


def check_generated_reportlibs():
    """调用报告库生成器 --check：生成物必须与 scripts/reportlib 的规范源逐字节一致。

    生成器本身是唯一更新路径（`--write`）；手改任何一个生成文件都会在这里变成 ERROR。
    """
    script = ROOT / "scripts" / "gen-report-libs.py"
    if not script.exists():
        return [{
            "file": "scripts/gen-report-libs.py",
            "line": 0,
            "level": "ERROR",
            "rule": "报告库生成器缺失",
            "code": str(script),
        }]
    proc = subprocess.run(
        [sys.executable, str(script), "--check"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )
    if proc.returncode == 0:
        return []
    detail = (proc.stderr or proc.stdout or "").strip().splitlines()
    message = next((line for line in detail if line.startswith("[FAIL]")), "生成物与规范源不一致")
    return [{
        "file": "scripts/reportlib/",
        "line": 0,
        "level": "ERROR",
        "rule": "报告库生成物漂移（改法：python scripts/gen-report-libs.py --write，禁手改生成文件）",
        "code": message[:120],
    }]


if __name__ == "__main__":
    update_mode = "--update-baseline" in sys.argv
    findings = lint()

    if update_mode:
        warnings_only = [f for f in findings if f["level"] == "WARNING"]
        _, _, counts = budget_check(warnings_only)
        update_baseline(counts)
        sys.exit(0)

    budget_errors, notices, counts = budget_check([f for f in findings if f["level"] == "WARNING"])
    findings = findings + budget_errors
    # 热路径正则：ERROR 级、按文件配额（不进 warning 预算）
    findings = findings + check_hot_path_regex()
    # 裸 XML 解析：ERROR 级（XXE 边界）
    findings = findings + check_raw_xml_parsing()

    errors = [f for f in findings if f["level"] == "ERROR"]
    warnings = [f for f in findings if f["level"] == "WARNING"]

    print(f"=== Static Analysis (D15 T5) ===")
    print(f"Files scanned: {len(list(iter_kotlin_files()))}")
    print(f"Errors:   {len(errors)}")
    print(f"Warnings: {len(warnings)}")
    print()
    print("=== Warning Budget (T-072) ===")
    baseline, err = load_baseline()
    if err is None:
        print(f"Baseline: {len(baseline)} files / {sum(baseline.values())} warnings; "
              f"current: {len(counts)} files / {sum(counts.values())} warnings")
    print(f"Budget violations: {len(budget_errors)}")
    print()

    if errors:
        print("--- ERRORS ---")
        for f in errors:
            print(f"  {f['file']}:{f['line']} [{f['rule']}]")
            if f["code"]:
                print(f"    {f['code']}")
        print()

    if warnings:
        print("--- WARNINGS ---")
        for f in warnings[:20]:  # 限制输出
            print(f"  {f['file']}:{f['line']} [{f['rule']}]")
        if len(warnings) > 20:
            print(f"  ... and {len(warnings) - 20} more warnings")
        print()

    if notices:
        print("--- BUDGET NOTICES ---")
        for n in notices[:20]:
            print(f"  {n}")
        if len(notices) > 20:
            print(f"  ... and {len(notices) - 20} more notices")
        print()

    # 输出 JSON 供 CI 消费
    write_report(errors, warnings, findings, notices, counts)

    if errors:
        print("FAILED: static analysis found errors")
        sys.exit(1)
    else:
        print("PASSED: no errors found")
        sys.exit(0)
