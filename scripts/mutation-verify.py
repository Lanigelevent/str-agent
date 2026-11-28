#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
T-051 变异测试：核心校验器（verify 包）。

做法：逐条把 VerifyCore.kt / VerifyReport.kt 里的关键判断改成错误实现，
跑 `:core:test --tests com.stragent.core.verify.*`：
  - 变红 → KILLED（测试真的覆盖了这条行为）
  - 仍绿 → SURVIVED（测试没覆盖到）
  - 锚点找不到 / 编译失败 → INVALID（不计入分母）

门槛：杀死率 ≥ 80%，否则退出码 1。所有源文件在 finally 里原样写回。

用法：
  JAVA_HOME=/path/to/jdk17 python scripts/mutation-verify.py
  python scripts/mutation-verify.py --list    # 只做锚点自检，不改文件
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERIFY_DIR = ROOT / "core/src/main/kotlin/com/stragent/core/verify"
FILES = {
    "core": VERIFY_DIR / "VerifyCore.kt",
    "report": VERIFY_DIR / "VerifyReport.kt",
    "oauth": ROOT / "data/src/main/kotlin/com/stragent/data/OAuthResourceServer.kt",
    "injection": ROOT / "core/src/main/kotlin/com/stragent/core/security/PromptInjectionHeuristics.kt",
}

# T-152：每个文件域有独立测试域（module, test-pattern）；缺省仍打 verify 包
TEST_SCOPES = {
    "core": (":core:test", "com.stragent.core.verify.*"),
    "report": (":core:test", "com.stragent.core.verify.*"),
    "oauth": (":data:test", "com.stragent.data.OAuthResourceServerTest*"),
    "injection": (":core:test", "com.stragent.core.security.*"),
}

MUTANTS = [
    # ---------- 数字解析：百分比 ----------
    dict(
        id="percent-claim-not-normalized",
        file="core",
        note="公式右侧 50.0% 不当 0.5 处理",
        old='if (unit == "%" || unit == "％") return NaturalToken(raw, base / 100, true, 0, raw.length)',
        new='if (unit == "%" || unit == "％") return NaturalToken(raw, base, true, 0, raw.length)',
    ),
    dict(
        id="percent-token-not-normalized",
        file="core",
        note="正文 50.0% 不当 0.5 处理",
        old="                value = if (percent) base / 100 else base * scale,",
        new="                value = base * scale,",
    ),
    dict(
        id="normalize-drops-halfwidth-percent",
        file="core",
        note="半角 % 不再去掉（token 直接丢失）",
        old='private val NORMALIZE_PERCENT = Regex("[%％]$")',
        new='private val NORMALIZE_PERCENT = Regex("[％]$")',
    ),
    # ---------- 中文数字 ----------
    dict(
        id="chinese-ten-as-one",
        file="core",
        note="「十」按 1 计",
        old="val unit = when (ch) { '十' -> 10.0; '百' -> 100.0; else -> 1000.0 }",
        new="val unit = when (ch) { '十' -> 1.0; '百' -> 100.0; else -> 1000.0 }",
    ),
    dict(
        id="chinese-leading-ten-zero",
        file="core",
        note="「十四万」的十位系数当 0",
        old="if (!hasAny) number = 1.0 // 十一 = 11",
        new="if (!hasAny) number = 0.0 // 十一 = 11",
    ),
    dict(
        id="chinese-wan-wrong-scale",
        file="core",
        note="「万」按 1e8 展开",
        old="total = if (ch == '亿') (total + section) * 1e8 else total + section * 1e4",
        new="total = if (ch == '亿') (total + section) * 1e8 else total + section * 1e8",
    ),
    dict(
        id="chinese-percent-not-normalized",
        file="core",
        note="「百分之五十」不当 0.5 处理",
        old="return NaturalToken(raw, value / 100, true, 0, raw.length)",
        new="return NaturalToken(raw, value, true, 0, raw.length)",
    ),
    # ---------- 算式：运算符与优先级 ----------
    dict(
        id="op-mul-as-plus",
        file="core",
        note="× 解析成 +",
        old='    "×", "*", "✕", "乘", "乘以" -> \'*\'',
        new='    "×", "*", "✕", "乘", "乘以" -> \'+\'',
    ),
    dict(
        id="op-div-as-mul",
        file="core",
        note="÷ 解析成 ×",
        old='    "÷", "/", "除以" -> \'/\'',
        new='    "÷", "/", "除以" -> \'*\'',
    ),
    dict(
        id="mul-evaluated-as-add",
        file="core",
        note="乘法按加法求值",
        old="            '*' -> additive[additive.size - 1] *= v",
        new="            '*' -> {\n                addOps.add('+')\n                additive.add(v)\n            }",
    ),
    dict(
        id="div-evaluated-as-mul",
        file="core",
        note="除法按乘法求值",
        old="""            '/' -> {
                if (v == 0.0) return null
                additive[additive.size - 1] /= v
            }""",
        new="""            '/' -> {
                if (v == 0.0) return null
                additive[additive.size - 1] *= v
            }""",
    ),
    dict(
        id="arithmetic-tolerance-unbounded",
        file="core",
        note="任何算式都算对",
        old="val matched = abs(expected - claimed.value) <= maxOf(abs(expected) * 0.005, 0.0005)",
        new="val matched = abs(expected - claimed.value) <= 1e18",
    ),
    dict(
        id="claim-requires-no-leading-space",
        file="core",
        note="等号后带空格就解析不到结果",
        old='private val EXPR_HEAD = Regex("^\\\\s*($EXPR)")',
        new='private val EXPR_HEAD = Regex("^($EXPR)")',
    ),
    dict(
        id="chain-operand-off-by-one",
        file="core",
        note="链式解析游标差一位",
        old="""            val token = parseNumberExpression(m.groupValues[1]) ?: return null
            operands.add(token)
            i += m.range.last + 1""",
        new="""            val token = parseNumberExpression(m.groupValues[1]) ?: return null
            operands.add(token)
            i += m.range.last""",
    ),
    dict(
        id="formula-line-never-detected",
        file="core",
        note="公式行识别失效（只认中文算子）",
        old=r'private val FORMULA_LINE = Regex("[=＝]|÷|×|\\/\\s*\\d|相加|合计为|除以|之和|减去|乘以")',
        new=r'private val FORMULA_LINE = Regex("相加|合计为|除以|之和|减去|乘以")',
    ),
    # ---------- 豁免 ----------
    dict(
        id="structural-exemption-disabled",
        file="report",
        note="结构性数字不再豁免（年份/序号全报）",
        old="            if (isStructural(item.raw, line, item.value, inFormula)) continue",
        new="            if (false) continue",
    ),
    dict(
        id="zero-one-always-structural",
        file="core",
        note="0/1 永远当结构数",
        old="""    if (numeric == 0.0 || numeric == 1.0) {
        if (inFormula) return false
        if (rawPos >= 0) {""",
        new="""    if (numeric == 0.0 || numeric == 1.0) {
        return true
        if (rawPos >= 0) {""",
    ),
    dict(
        id="ordinal-exempt-in-formula",
        file="core",
        note="公式行里的序数也被豁免（操作数失去溯源）",
        old='        if (!inFormula && trimmed.substring(0, rawPos).trimEnd().endsWith("第")) return true',
        new='        if (trimmed.substring(0, rawPos).trimEnd().endsWith("第")) return true',
    ),
    dict(
        id="ordinal-exemption-disabled",
        file="core",
        note="序数不再豁免（第 15 章被当编造数）",
        old='        if (!inFormula && trimmed.substring(0, rawPos).trimEnd().endsWith("第")) return true',
        new='        if (false && !inFormula && trimmed.substring(0, rawPos).trimEnd().endsWith("第")) return true',
    ),
    dict(
        id="format-example-ignores-factual",
        file="core",
        note="格式词豁免不再检查事实主张",
        old="    FORMAT_EXAMPLE_MARKER.containsMatchIn(line) && !FACTUAL_CLAIM.containsMatchIn(line)",
        new="    FORMAT_EXAMPLE_MARKER.containsMatchIn(line)",
    ),
    dict(
        id="hypothetical-number-cap-10",
        file="core",
        note="假设句豁免放宽到 10 个数字",
        old="    return countNumbers(line) <= 2",
        new="    return countNumbers(line) <= 10",
    ),
    dict(
        id="formula-issue-kind-unsourced",
        file="report",
        note="公式里的可疑数归入 unsourced（失去 formula 类别）",
        old="            if (inFormula) formula.add(issue) else unsourced.add(issue)",
        new="            unsourced.add(issue)",
    ),
    # ---------- 授权 ----------
    dict(
        id="trust-any-operand",
        file="report",
        note="任何操作数都算有依据（公式可洗白）",
        old="    fun trusted(token: NaturalToken): Boolean = hitsBriefToken(token) || authorized.contains(token.value)",
        new="    fun trusted(token: NaturalToken): Boolean = true",
    ),
    dict(
        id="brief-hits-never",
        file="report",
        note="白名单命中永远为假",
        old="    fun hitsBriefToken(token: NaturalToken): Boolean = hitsBrief(token.value, briefNatural)",
        new="    fun hitsBriefToken(token: NaturalToken): Boolean = false",
    ),
    dict(
        id="brief-natural-scaled",
        file="report",
        note="摘要自然值乘 100",
        old="        briefNatural.add(item.value)",
        new="        briefNatural.add(item.value * 100)",
    ),
    dict(
        id="arithmetic-checks-disabled",
        file="report",
        note="完全不算算式",
        old="        val checks = if (inFormula) checkArithmeticAll(line).sortedBy { it.range.first } else emptyList()",
        new="        val checks = emptyList<ArithmeticCheck>()",
    ),
    # ---------- T-152：OAuth 验证链（data 域）----------
    dict(
        id="oauth-alg-check-removed",
        file="oauth",
        note="算法白名单移除（alg=none/HS256 放行）",
        old='if (algorithm != "RS256") {',
        new='if (false) {',
    ),
    dict(
        id="oauth-exp-check-removed",
        file="oauth",
        note="过期校验移除",
        old='if (nowSeconds - skewSeconds >= expiresAt) return OAuthVerdict.Unauthorized("令牌已过期")',
        new='if (false) { return OAuthVerdict.Unauthorized("令牌已过期") }',
    ),
    dict(
        id="oauth-iss-check-removed",
        file="oauth",
        note="issuer 匹配移除",
        old='if (issuer != config.issuer) {',
        new='if (false) {',
    ),
    dict(
        id="oauth-aud-check-removed",
        file="oauth",
        note="audience 匹配移除",
        old='if (config.audience !in audience) {',
        new='if (false) {',
    ),
    dict(
        id="oauth-scope-check-removed",
        file="oauth",
        note="scope 校验移除",
        old='if (missing.isNotEmpty()) return OAuthVerdict.InsufficientScope(missing)',
        new='if (false) { return OAuthVerdict.InsufficientScope(missing) }',
    ),
    dict(
        id="oauth-signature-inverted",
        file="oauth",
        note="签名校验反转",
        old='            verify(jwt.signature)',
        new='            !verify(jwt.signature)',
    ),
    # ---------- T-152：注入净化规则（core/security 域）----------
    dict(
        id="injection-r1-chinese-directive-blinded",
        file="injection",
        note="R1 中文指令覆盖致盲",
        old='忽略(?:掉)?(?:之前|以上|前面|上述)',
        new='忽略ZZZ(?:掉)?(?:之前|以上|前面|上述)',
    ),
    dict(
        id="injection-r2-inst-tag-blinded",
        file="injection",
        note="R2 [INST] 标记致盲",
        old='[/?INST]',
        new='[/?INSTX]',
    ),
    dict(
        id="injection-r3-role-rewrite-blinded",
        file="injection",
        note="R3 角色重写致盲（你现在扮演分支——语料覆盖分支）",
        old='你现在扮演',
        new='你现在扮演X',
    ),
    dict(
        id="injection-r8-decode-blinded",
        file="injection",
        note="R8 中文解码载荷致盲",
        old='(?:解码|解密|还原)',
        new='(?:解码X|解密|还原)',
    ),
]


def choose_gradle() -> list[str]:
    if os.name == "nt":
        return ["gradlew.bat"]
    return ["./gradlew"]


def run_tests(module: str = ":core:test", pattern: str = "com.stragent.core.verify.*") -> tuple[int, str]:
    cmd = choose_gradle() + [
        module,
        "--tests",
        pattern,
        "--offline",
        "--console=plain",
    ]
    started = time.time()
    proc = subprocess.run(
        cmd,
        cwd=str(ROOT),
        env=os.environ.copy(),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=600,
    )
    elapsed = time.time() - started
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "") + f"\n[elapsed {elapsed:.1f}s]"


def is_compile_error(output: str) -> bool:
    return "e: file:" in output or "Compilation error" in output or "compileKotlin FAILED" in output


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def preflight() -> list[str]:
    problems = []
    ids = set()
    for mutant in MUTANTS:
        if mutant["id"] in ids:
            problems.append(f"重复 id：{mutant['id']}")
        ids.add(mutant["id"])
        source = FILES[mutant["file"]].read_text(encoding="utf-8")
        count = source.count(mutant["old"])
        if count != 1:
            problems.append(f"{mutant['id']}：锚点出现 {count} 次（需恰好 1 次）")
    return problems


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    problems = preflight()
    if problems:
        print("锚点自检失败：")
        for item in problems:
            print("  -", item)
        return 2
    print(f"锚点自检通过：{len(MUTANTS)} 个变异体")
    if "--list" in sys.argv:
        for mutant in MUTANTS:
            print(f"  {mutant['id']:<38} {mutant['file']:<7} {mutant['note']}")
        return 0

    selected = MUTANTS
    if "--only" in sys.argv:
        index = sys.argv.index("--only")
        if index + 1 >= len(sys.argv):
            print("--only 需要逗号分隔的变异体 id")
            return 2
        wanted = [item.strip() for item in sys.argv[index + 1].split(",") if item.strip()]
        known = {mutant["id"] for mutant in MUTANTS}
        unknown = [item for item in wanted if item not in known]
        if unknown:
            print("未知变异体：" + ", ".join(unknown))
            return 2
        selected = [mutant for mutant in MUTANTS if mutant["id"] in wanted]
        print(f"只跑 {len(selected)} 个变异体：{', '.join(wanted)}")

    backups = {key: path.read_text(encoding="utf-8") for key, path in FILES.items()}
    results = []
    started_at = _dt.datetime.now()
    try:
        for index, mutant in enumerate(selected, start=1):
            path = FILES[mutant["file"]]
            source = backups[mutant["file"]]
            mutated = source.replace(mutant["old"], mutant["new"], 1)
            path.write_text(mutated, encoding="utf-8")
            print(f"[{index}/{len(selected)}] {mutant['id']} … ", end="", flush=True)
            try:
                scope = TEST_SCOPES[mutant["file"]]
                code, output = run_tests(scope[0], scope[1])
            except subprocess.TimeoutExpired:
                results.append((mutant, "INVALID", "超时（>600s）", ""))
                print("INVALID(timeout)")
                continue
            finally:
                path.write_text(source, encoding="utf-8")
            if code == 0:
                status, detail = "SURVIVED", "测试仍全绿"
            elif is_compile_error(output):
                status, detail = "INVALID", "编译失败"
            else:
                status, detail = "KILLED", "测试变红"
            results.append((mutant, status, detail, output))
            print(status)

        # 写回自检
        for key, path in FILES.items():
            if sha(path.read_text(encoding="utf-8")) != sha(backups[key]):
                print(f"严重：{path} 未恢复原样！")
                return 3
    finally:
        for key, path in FILES.items():
            path.write_text(backups[key], encoding="utf-8")

    killed = sum(1 for _, status, _, _ in results if status == "KILLED")
    survived = [row for row in results if row[1] == "SURVIVED"]
    invalid = [row for row in results if row[1] == "INVALID"]
    scored = killed + len(survived)
    rate = killed / scored if scored else 0.0

    report_dir = ROOT / "build/reports"
    report_dir.mkdir(parents=True, exist_ok=True)
    stamp = started_at.strftime("%Y-%m-%d_%H%M%S")
    report_path = report_dir / f"mutation-verify-{stamp}.md"
    lines = [
        "# 变异测试报告（verify 包）",
        "",
        f"- 时间：{started_at.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 变异体：{len(results)}（有效 {scored}，无效 {len(invalid)}）",
        f"- 杀死：{killed}，存活：{len(survived)}",
        f"- 杀死率：{rate * 100:.1f}%（门槛 80%）",
        "",
        "| 变异体 | 文件 | 结果 | 说明 |",
        "| --- | --- | --- | --- |",
    ]
    for mutant, status, detail, _ in results:
        lines.append(f"| {mutant['id']} | {mutant['file']} | {status} | {mutant['note']}（{detail}） |")
    if survived:
        lines += ["", "## 存活变异体输出摘录", ""]
        for mutant, _, _, output in survived:
            tail = "\n".join(output.splitlines()[-12:])
            lines += [f"### {mutant['id']}", "", "```", tail, "```", ""]
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print()
    print(f"杀死 {killed}/{scored}，杀死率 {rate * 100:.1f}%（无效 {len(invalid)}）")
    for mutant, _, detail, _ in invalid:
        print(f"  INVALID {mutant['id']}：{detail}")
    for mutant, _, _, _ in survived:
        print(f"  SURVIVED {mutant['id']}：{mutant['note']}")
    print(f"报告：{report_path}")
    return 0 if rate >= 0.8 and not invalid else 1


if __name__ == "__main__":
    raise SystemExit(main())
