#!/usr/bin/env python3
"""报告库代码生成器（T-058 / D17 G-19）。

规范源（入库、可评审）：
  scripts/reportlib/report-libs.json     报告类型 / 文风 / 行业三库
  scripts/reportlib/report-cases.json    真实案例（总/文风/行业/类型）
  scripts/reportlib/industry-match.json  行业信号词与匹配阈值

生成物（禁手改，check 模式会逐字节对拍）：
  core/src/main/kotlin/com/stragent/core/report/ReportLibData.kt
  core/src/main/kotlin/com/stragent/core/report/ReportCasesData.kt
  core/src/main/kotlin/com/stragent/core/report/IndustryMatchData.kt

用法：
  python scripts/gen-report-libs.py            # 校验生成物与规范源一致（CI / 本地门禁）
  python scripts/gen-report-libs.py --write    # 重新生成（改规范源后唯一正确入口）
  python scripts/gen-report-libs.py --check    # 同默认
  python scripts/gen-report-libs.py --root DIR # 在指定仓库根上运行（测试/负控）

纪律：生成物永远由本脚本产出；手改生成物会在 --check 下失败。上游 the upstream host project
TS 报告库的文案通过规范源逐字保留（JSON 只做结构化，不改一个字）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HEADER_TAG = "由 scripts/gen-report-libs.py 从 scripts/reportlib/%s 生成——勿手改；上游文案逐字保留"


def kotlin_string(value: str) -> str:
    out = []
    for ch in value:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "$":
            out.append("\\$")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ord(ch) < 0x20:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    return '"' + "".join(out) + '"'


def load(root: Path, name: str) -> dict:
    path = root / "scripts" / "reportlib" / name
    if not path.is_file():
        raise SystemExit(f"[FAIL] 规范源缺失：{path}")
    try:
        with path.open(encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"[FAIL] 规范源不是合法 JSON：{path}：{exc}")
    if not isinstance(data, dict):
        raise SystemExit(f"[FAIL] 规范源顶层必须是对象：{path}")
    return data


def require_list(data: dict, key: str, where: str) -> list:
    value = data.get(key)
    if not isinstance(value, list):
        raise SystemExit(f"[FAIL] {where} 缺少数组字段 {key}")
    return value


def require_str(item: dict, key: str, where: str) -> str:
    value = item.get(key)
    if not isinstance(value, str):
        raise SystemExit(f"[FAIL] {where} 缺少字符串字段 {key}")
    return value


def gen_report_libs(data: dict) -> str:
    lines = [
        "package com.stragent.core.report",
        "",
        "/** " + HEADER_TAG % "report-libs.json" + " */",
        "internal object ReportLibData {",
        "    internal val reportTypes = listOf(",
    ]
    for index, item in enumerate(require_list(data, "reportTypes", "report-libs.json")):
        where = f"reportTypes[{index}]"
        lines.append(
            "        ReportType(id = %s, label = %s, hint = %s, prompt = %s),"
            % (
                kotlin_string(require_str(item, "id", where)),
                kotlin_string(require_str(item, "label", where)),
                kotlin_string(require_str(item, "hint", where)),
                kotlin_string(require_str(item, "prompt", where)),
            )
        )
    lines += ["    )", "", "    internal val reportStyles = listOf("]
    for index, item in enumerate(require_list(data, "reportStyles", "report-libs.json")):
        where = f"reportStyles[{index}]"
        lines.append(
            "        ReportStyle(id = %s, label = %s, hint = %s, tone = %s, length = %s, prompt = %s),"
            % (
                kotlin_string(require_str(item, "id", where)),
                kotlin_string(require_str(item, "label", where)),
                kotlin_string(require_str(item, "hint", where)),
                kotlin_string(require_str(item, "tone", where)),
                kotlin_string(require_str(item, "length", where)),
                kotlin_string(require_str(item, "prompt", where)),
            )
        )
    lines += ["    )", "", "    internal val industries = listOf("]
    for index, item in enumerate(require_list(data, "industries", "report-libs.json")):
        where = f"industries[{index}]"
        lines.append(
            "        IndustryProfile(id = %s, label = %s, category = %s, hint = %s, focus = %s, format = %s, prompt = %s),"
            % (
                kotlin_string(require_str(item, "id", where)),
                kotlin_string(require_str(item, "label", where)),
                kotlin_string(require_str(item, "category", where)),
                kotlin_string(require_str(item, "hint", where)),
                kotlin_string(require_str(item, "focus", where)),
                kotlin_string(require_str(item, "format", where)),
                kotlin_string(require_str(item, "prompt", where)),
            )
        )
    lines += ["    )", "}", ""]
    return "\n".join(lines)


def case_block(item: dict, indent: str, where: str) -> list[str]:
    rules = item.get("rules")
    if not isinstance(rules, list):
        raise SystemExit(f"[FAIL] {where} 缺少 rules 数组")
    lines = [
        indent + "KtRealCase(",
        indent + "    source = " + kotlin_string(require_str(item, "source", where)) + ",",
        indent + "    url = " + kotlin_string(require_str(item, "url", where)) + ",",
        indent + "    rules = listOf(",
    ]
    for rule in rules:
        if not isinstance(rule, str):
            raise SystemExit(f"[FAIL] {where}.rules 必须是字符串")
        lines.append(indent + "        " + kotlin_string(rule) + ",")
    lines += [indent + "    ),", indent + "),"]
    return lines


def case_list(items: list, indent: str, where: str) -> list[str]:
    if not isinstance(items, list):
        raise SystemExit(f"[FAIL] {where} 必须是数组")
    lines = [indent + "listOf("]
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            raise SystemExit(f"[FAIL] {where}[{index}] 必须是对象")
        lines += case_block(item, indent + "    ", f"{where}[{index}]")
    lines.append(indent + "),")
    return lines


def case_map(data: dict, key: str) -> list[str]:
    value = data.get(key)
    if not isinstance(value, dict):
        raise SystemExit(f"[FAIL] report-cases.json 缺少对象字段 {key}")
    lines = [f"    internal val {key} = mapOf("]
    for name, items in value.items():
        if not isinstance(items, list):
            raise SystemExit(f"[FAIL] {key}[{name}] 必须是数组")
        lines.append("        " + kotlin_string(name) + " to listOf(")
        for index, item in enumerate(items):
            if not isinstance(item, dict):
                raise SystemExit(f"[FAIL] {key}[{name}][{index}] 必须是对象")
            lines += case_block(item, "            ", f"{key}[{name}][{index}]")
        lines.append("        ),")
    lines.append("    )")
    return lines


def case_single_map(data: dict, key: str) -> list[str]:
    value = data.get(key)
    if not isinstance(value, dict):
        raise SystemExit(f"[FAIL] report-cases.json 缺少对象字段 {key}")
    lines = [f"    internal val {key} = mapOf("]
    for name, item in value.items():
        if not isinstance(item, dict):
            raise SystemExit(f"[FAIL] {key}[{name}] 必须是对象")
        lines.append("        " + kotlin_string(name) + " to ")
        lines += case_block(item, "            ", f"{key}[{name}]")
    lines.append("    )")
    return lines


def gen_report_cases(data: dict) -> str:
    lines = [
        "package com.stragent.core.report",
        "",
        "/** " + HEADER_TAG % "report-cases.json" + " */",
        "internal object ReportCasesData {",
        "    internal data class KtRealCase(val source: String, val url: String, val rules: List<String>)",
        "",
        "    internal val generalCases = listOf(",
    ]
    for index, item in enumerate(require_list(data, "generalCases", "report-cases.json")):
        if not isinstance(item, dict):
            raise SystemExit(f"[FAIL] generalCases[{index}] 必须是对象")
        lines += case_block(item, "        ", f"generalCases[{index}]")
    lines += ["    )", "", "    internal val styleCases = listOf("]
    for index, item in enumerate(require_list(data, "styleCases", "report-cases.json")):
        if not isinstance(item, dict):
            raise SystemExit(f"[FAIL] styleCases[{index}] 必须是对象")
        lines += case_block(item, "        ", f"styleCases[{index}]")
    lines += ["    )", ""]
    lines += case_map(data, "industryCasesMap")
    lines.append("")
    lines += case_single_map(data, "typeCasesMap")
    lines += ["}", ""]
    return "\n".join(lines)


def signal_map(data: dict, key: str) -> list[str]:
    value = data.get(key)
    if not isinstance(value, dict):
        raise SystemExit(f"[FAIL] industry-match.json 缺少对象字段 {key}")
    lines = [f"    internal val {key} = mapOf("]
    for name, signals in value.items():
        if not isinstance(signals, list) or not all(isinstance(s, str) for s in signals):
            raise SystemExit(f"[FAIL] {key}[{name}] 必须是字符串数组")
        lines.append("        " + kotlin_string(name) + " to listOf(")
        for signal in signals:
            lines.append("            " + kotlin_string(signal) + ",")
        lines.append("        ),")
    lines.append("    )")
    return lines


def gen_industry_match(data: dict) -> str:
    threshold = data.get("matchThreshold")
    if not isinstance(threshold, (int, float)):
        raise SystemExit("[FAIL] industry-match.json 缺少数值字段 matchThreshold")
    lines = [
        "package com.stragent.core.report",
        "",
        "/** " + HEADER_TAG % "industry-match.json" + " */",
        "internal object IndustryMatchData {",
        "    internal const val MATCH_THRESHOLD = " + format(threshold, ".15g"),
        "",
    ]
    lines += signal_map(data, "strongSignals")
    lines.append("")
    lines += signal_map(data, "industrySignals")
    lines += ["}", ""]
    return "\n".join(lines)


def targets(root: Path) -> list[tuple[Path, str]]:
    libs = load(root, "report-libs.json")
    cases = load(root, "report-cases.json")
    match = load(root, "industry-match.json")
    base = root / "core" / "src" / "main" / "kotlin" / "com" / "stragent" / "core" / "report"
    return [
        (base / "ReportLibData.kt", gen_report_libs(libs)),
        (base / "ReportCasesData.kt", gen_report_cases(cases)),
        (base / "IndustryMatchData.kt", gen_industry_match(match)),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="报告库代码生成 / 一致性校验")
    parser.add_argument("--write", action="store_true", help="重新生成三个 Kotlin 数据文件")
    parser.add_argument("--check", action="store_true", help="校验生成物与规范源逐字节一致（默认）")
    parser.add_argument("--root", default=str(Path(__file__).resolve().parent.parent), help="仓库根目录")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    generated = targets(root)
    if args.write:
        for path, text in generated:
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("w", encoding="utf-8", newline="\n") as f:
                f.write(text)
            print(f"[WRITE] {path.relative_to(root)}")
        return 0

    failures = 0
    for path, text in generated:
        actual = path.read_text(encoding="utf-8") if path.is_file() else None
        if actual == text:
            print(f"[OK] {path.relative_to(root)}")
            continue
        failures += 1
        print(f"[FAIL] {path.relative_to(root)} 与规范源不一致（不要手改生成物，跑 --write）")
        if actual is None:
            print("  文件不存在")
            continue
        expected_lines = text.splitlines()
        actual_lines = actual.splitlines()
        for index in range(min(len(expected_lines), len(actual_lines))):
            if expected_lines[index] != actual_lines[index]:
                print(f"  首个差异在第 {index + 1} 行：")
                print(f"    期望: {expected_lines[index][:160]}")
                print(f"    实际: {actual_lines[index][:160]}")
                break
        else:
            print(f"  行数不同：期望 {len(expected_lines)} 行，实际 {len(actual_lines)} 行")
    if failures:
        print(f"[FAIL] {failures} 个生成物与规范源不一致")
        return 1
    print("[OK] 报告库生成物与规范源逐字节一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
