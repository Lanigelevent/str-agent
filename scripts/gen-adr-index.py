#!/usr/bin/env python3
"""
T-078（D21 D-2）：D3 ADR 索引生成器（零依赖）。

D3 已 40+ 条 ADR 无索引——本脚本把 `### AD-NNN · 日期 ｜ 标题` 区块解析成
「编号/日期/标题/状态」索引表，写入 D3 头部的标记块内：

    <!-- ADR-INDEX:BEGIN（由 scripts/gen-adr-index.py --write 生成；禁手改） -->
    ...
    <!-- ADR-INDEX:END -->

状态取每个 ADR 小节内第一行 `状态：`/`**状态**：`（取到第一个句号为止）；
缺失记「未标注」。单元格做防管道处理（| → ／、链接剥成文字）——表格外形稳定。

用法：
    python scripts/gen-adr-index.py --write   # 重新生成并写回 D3（唯一更新路径）
    python scripts/gen-adr-index.py --check   # 生成物与盘上标记块逐字节比对（漂移退出 1）

与 gen-report-libs.py 同纪律：生成器是唯一更新路径，手改索引会被 --check 判漂移；
check-docs.py 每次运行都会调 --check。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
D3 = ROOT / "docs" / "D3-decisions.md"

BEGIN = "<!-- ADR-INDEX:BEGIN（由 scripts/gen-adr-index.py --write 生成；禁手改，手改会被 --check 判漂移） -->"
END = "<!-- ADR-INDEX:END -->"

HEADER_RE = re.compile(r"^### (AD-\d+) · (.+)$")
STATUS_RE = re.compile(r"^[-*]?\s*\*{0,2}状态\*{0,2}\s*[：:]\s*(.+)$")


def parse_adrs(text):
    """解析全部 ADR：编号、日期、标题、状态（节内首个状态行，取到句号/分号为止）。"""
    adrs = []
    current = None
    for line in text.splitlines():
        m = HEADER_RE.match(line)
        if m:
            current = {"id": m.group(1), "rest": m.group(2).strip(), "status": "未标注"}
            adrs.append(current)
            continue
        if current is None:
            continue
        sm = STATUS_RE.match(line.strip())
        if sm is None and current["status"] == "未标注":
            # 行内形态：「……。状态：**生效中**。」（状态跟在上一字段尾部）
            sm = re.search(r"状态[：:]\s*(.+)$", line.strip())
        if sm and current["status"] == "未标注":
            status = sm.group(1).strip()
            status = re.sub(r"\*\*", "", status)  # 剥粗体
            status = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", status)  # 链接剥成文字
            status = status.split("。")[0].split("；")[0].strip()
            current["status"] = status or "未标注"
    return adrs


def cell(text):
    """表格单元格清洗：管道与换行是表格外形的破坏者。"""
    return str(text).replace("|", "／").replace("\n", " ").strip()


def render_index(adrs):
    lines = [
        BEGIN,
        "",
        "## ADR 索引（由脚本生成，禁手改）",
        "",
        "| 编号 | 日期 | 标题 | 状态 |",
        "|---|---|---|---|",
    ]
    for a in adrs:
        parts = a["rest"].split("｜", 1)
        if len(parts) > 1:
            date = parts[0].strip()
            title = parts[1].strip()
        else:
            date = ""
            title = a["rest"]
        lines.append("| {} | {} | {} | {} |".format(a["id"], cell(date), cell(title), cell(a["status"])))
    lines += ["", END]
    return "\n".join(lines)


def upsert(text, index):
    """把索引写回 D3：已有标记块就原位替换，否则插到第一个 ADR 之前。"""
    if BEGIN in text and END in text:
        pre = text.split(BEGIN, 1)[0]
        post = text.split(END, 1)[1]
        return pre + index + post
    m = re.search(r"^### AD-", text, flags=re.M)
    if not m:
        raise SystemExit("[FAIL] D3 里找不到任何 ADR 区块（### AD-xxx）")
    return text[: m.start()] + index + "\n\n---\n\n" + text[m.start():]


def extract_block(text):
    if BEGIN not in text or END not in text:
        return None
    return text.split(BEGIN, 1)[1].split(END, 1)[0]


def main():
    text = D3.read_text(encoding="utf-8")
    adrs = parse_adrs(text)
    if not adrs:
        print("[FAIL] 未解析到任何 ADR")
        sys.exit(1)
    index = render_index(adrs)

    if "--write" in sys.argv:
        updated = upsert(text, index)
        D3.write_text(updated, encoding="utf-8")
        print(f"[OK] ADR 索引已写入 {D3.name}：{len(adrs)} 条")
        sys.exit(0)

    # 缺省 = --check
    on_disk = extract_block(text)
    if on_disk is None:
        print("[FAIL] D3 缺少 ADR 索引标记块（先跑 --write）")
        sys.exit(1)
    # extract_block 拿到的是 BEGIN 与 END 之间的内容；重新拼出与 render 一致的形制比对
    desired_body = index.split(BEGIN, 1)[1].split(END, 1)[0]
    if on_disk != desired_body:
        print(f"[FAIL] D3 的 ADR 索引与生成器输出不一致（{len(adrs)} 条 ADR）——")
        print("       改法：python scripts/gen-adr-index.py --write（禁手改索引块）")
        sys.exit(1)
    print(f"[OK] ADR 索引一致：{len(adrs)} 条")
    sys.exit(0)


if __name__ == "__main__":
    main()
