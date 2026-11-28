#!/usr/bin/env python3
"""T-052 盲评材料生成器：从 48 份语料切出 A/B 两包匿名评审包与映射。

用法：python scripts/model-eval-packets.py
输出（均在 host/build/model-eval/，gitignored）：
  review-packet-a.md / review-packet-b.md   匿名评审包（P01–P30 / Q01–Q20）
  review-map-a.tsv / review-map-b.tsv       匿名编号 → 真实 id

设计：
  - A 包 30 份 = 全部 R1（24）+ 6 份 strong R2（standard/deepdive/compare/anomaly/trend/quality）；
  - B 包 20 份 = 8 weak R1 + 8 medium R1 + 4 strong R1（standard/deepdive/compare/anomaly）；
  - 编号随机排序（固定种子 20261029），只暴露报告类型，不暴露档位/轮次/真实 id；
  - 评审标签由独立上下文采集后再按映射落回真实 id（见 labels-reviewer-a/b.tsv）。
"""

from __future__ import annotations

import random
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORPUS = ROOT / "host/src/test/resources/model-eval/corpus.md"
OUT = ROOT / "host/build/model-eval"
SEED = 20261029
HEADER = re.compile(r"^@@ id=(\S+) type=(\S+) tier=(\S+) run=(\d+)$")

RULES = (
    "评分口径：1–5 整数。5=可直接交付；4=好但有可改之处；3=合格；2=勉强；1=不合格。\n"
    "只依据报告文本与报告类型评分；编号是随机匿名编号，与本报告的质量档、轮次、作者无关，\n"
    "不要从编号推断任何信息；也不要读取本目录以外或语料/标签文件。\n"
)


def load_corpus() -> dict[str, tuple[dict[str, str | int], str]]:
    items: dict[str, tuple[dict[str, str | int], str]] = {}
    current: dict[str, str | int] | None = None
    body: list[str] = []
    for line in CORPUS.read_text(encoding="utf-8").splitlines():
        match = HEADER.match(line)
        if match:
            if current is not None:
                items[str(current["id"])] = (current, "\n".join(body).strip())
            current = {
                "id": match.group(1),
                "type": match.group(2),
                "tier": match.group(3),
                "run": int(match.group(4)),
            }
            body = []
        elif current is not None:
            body.append(line)
    if current is not None:
        items[str(current["id"])] = (current, "\n".join(body).strip())
    if len(items) != 48:
        sys.exit(f"语料应为 48 份，实际 {len(items)}")
    return items


def emit(ids: list[str], prefix: str, title: str, items) -> tuple[str, list[tuple[str, str]]]:
    order = list(ids)
    random.Random(SEED).shuffle(order)
    lines = [title, "", RULES]
    mapping: list[tuple[str, str]] = []
    for number, real_id in enumerate(order, 1):
        anon = f"{prefix}{number:02d}"
        mapping.append((anon, real_id))
        meta, body = items[real_id]
        lines.append(f"## {anon}（type={meta['type']}）")
        lines.append("")
        lines.append(body)
        lines.append("")
    return "\n".join(lines), mapping


def main() -> None:
    items = load_corpus()
    r1 = [i for i, (meta, _) in items.items() if meta["run"] == 1]
    strong_r2 = [i for i, (meta, _) in items.items() if meta["tier"] == "strong" and meta["run"] == 2]
    anchor_types = {"standard", "deepdive", "compare", "anomaly", "trend", "quality"}
    a_ids = r1 + [i for i in strong_r2 if items[i][0]["type"] in anchor_types]
    b_ids = (
        [i for i, (meta, _) in items.items() if meta["tier"] == "weak" and meta["run"] == 1]
        + [i for i, (meta, _) in items.items() if meta["tier"] == "medium" and meta["run"] == 1]
        + [
            i
            for i, (meta, _) in items.items()
            if meta["tier"] == "strong" and meta["run"] == 1 and meta["type"] in {"standard", "deepdive", "compare", "anomaly"}
        ]
    )
    if len(a_ids) != 30 or len(b_ids) != 20:
        sys.exit(f"评审集大小不对：A={len(a_ids)}（应 30），B={len(b_ids)}（应 20）")
    if not set(b_ids) <= set(a_ids):
        sys.exit("B 的重叠集必须落在 A 内")

    OUT.mkdir(parents=True, exist_ok=True)
    for prefix, ids, title, packet, mapping in (
        ("P", a_ids, "# T-052 独立评审 A（30 份，匿名编号，顺序随机）", "review-packet-a.md", "review-map-a.tsv"),
        ("Q", b_ids, "# T-052 独立评审 B（20 份，匿名编号，顺序随机）", "review-packet-b.md", "review-map-b.tsv"),
    ):
        text, pairs = emit(ids, prefix, title, items)
        (OUT / packet).write_text(text, encoding="utf-8")
        rows = ["anon\tid"] + [f"{anon}\t{real}" for anon, real in pairs]
        (OUT / mapping).write_text("\n".join(rows) + "\n", encoding="utf-8")
        print(f"{packet}: {len(pairs)} 份；{mapping}: 已写")


if __name__ == "__main__":
    main()
