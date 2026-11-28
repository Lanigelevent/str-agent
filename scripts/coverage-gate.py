#!/usr/bin/env python3
"""覆盖率门禁（D17 G-20 / T-057）。

比较 JaCoCo 行覆盖率与入库基线：**低于基线即失败**（允许 0.1pp 的测量噪声）。
基线只记录「产品模块」——bench 是评测基座，单元测试覆盖率低不代表产品退化，
把它算进来会让每次补评测套件都变成覆盖率事故。

用法：
  ./gradlew test jacocoTestReport -Pcoverage   # 先产出各模块 XML
  python scripts/coverage-gate.py              # 对照基线
  python scripts/coverage-gate.py --write      # 覆盖基线（发版/有意扩测后）

退出码：0 = 不低于基线；1 = 低于基线或报告缺失。
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "bench" / "baseline" / "coverage-baseline.json"

# 产品模块：覆盖率门禁的统计范围（bench 是评测基座，不含在内）
MODULES = ["core", "str-agent", "data", "engine-duckdb", "host"]

# 测量噪声容忍：不同 JDK/JaCoCo 版本的行号口径可能差几行，
# 0.1pp 以内不算退化；基线本身按两位小数入库。
TOLERANCE_PP = 0.1


def module_line_coverage(module: str) -> tuple[int, int]:
    report = ROOT / module / "build" / "reports" / "jacoco" / "test" / "jacocoTestReport.xml"
    if not report.is_file():
        raise SystemExit(
            f"缺少覆盖率报告：{report}\n"
            "先运行：./gradlew test jacocoTestReport -Pcoverage"
        )
    root = ET.parse(report).getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered", 0)), int(counter.get("missed", 0))
    raise SystemExit(f"{report} 里没有 LINE 计数器")


def measure() -> dict:
    per_module: dict[str, dict] = {}
    covered_total = 0
    missed_total = 0
    for module in MODULES:
        covered, missed = module_line_coverage(module)
        covered_total += covered
        missed_total += missed
        total = covered + missed
        per_module[module] = {
            "covered": covered,
            "missed": missed,
            "percent": round(100.0 * covered / total, 2) if total else 0.0,
        }
    total = covered_total + missed_total
    return {
        "metric": "LINE",
        "tool": "org.jacoco 0.8.12（Gradle 插件，-Pcoverage）",
        "modules": MODULES,
        "excluded": ["bench（评测基座，覆盖率不代表产品质量）"],
        "lineCovered": covered_total,
        "lineTotal": total,
        "linePercent": round(100.0 * covered_total / total, 2) if total else 0.0,
        "tolerancePp": TOLERANCE_PP,
        "perModule": per_module,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="JaCoCo 行覆盖率门禁")
    parser.add_argument("--write", action="store_true", help="把当前测量写入基线文件")
    args = parser.parse_args()

    current = measure()
    print("=== 覆盖率门禁（D17 G-20）===")
    print(f"范围：{' / '.join(MODULES)}（LINE）")
    for module, row in current["perModule"].items():
        print(f"  {module:14s} {row['covered']:6d}/{row['covered'] + row['missed']:<6d}  {row['percent']:6.2f}%")
    print(f"  {'合计':12s} {current['lineCovered']:6d}/{current['lineTotal']:<6d}  {current['linePercent']:6.2f}%")

    if args.write:
        current["measuredAt"] = __import__("datetime").date.today().isoformat()
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"\n已写入基线：{BASELINE.relative_to(ROOT)}")
        return 0

    if not BASELINE.is_file():
        print(f"\n基线不存在：{BASELINE.relative_to(ROOT)}；先运行本脚本 --write 建立基线。", file=sys.stderr)
        return 1

    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    floor = float(baseline["linePercent"]) - TOLERANCE_PP
    print(f"\n基线：{baseline['linePercent']:.2f}%（{baseline.get('measuredAt', '?')}） 下限：{floor:.2f}%")
    if current["linePercent"] < floor:
        print(f"FAILED: 覆盖率 {current['linePercent']:.2f}% 低于基线 {baseline['linePercent']:.2f}%", file=sys.stderr)
        return 1
    print(f"PASSED: 覆盖率 {current['linePercent']:.2f}% ≥ 基线下限 {floor:.2f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
