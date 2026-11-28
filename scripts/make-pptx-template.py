#!/usr/bin/env python3
"""PPTX 模板底座生成器（T-169）。

为什么需要底座（设计定稿见 D7 T-169 / D4 P-221）：
    PresentationML 的最小包不是「手写几个 XML」那么简单——slideMaster / slideLayout /
    theme 三层关系、layout 与 master 的 rels 链、theme 的配色与字体方案，任何一处
    不对，PowerPoint / WPS 打开就是「需要修复」。本会话此前三次手写 PPTX 全部踩坑，
    因此改为**底座 + 补丁**：底座由本脚本用 python-pptx 生成并入库，Kotlin 端只做
    「复制底座 + 三处补丁 + 追加幻灯片部件」，不去手写 master/layout/theme。

生成物（禁手改，--check 会逐字节对拍）：
    host/src/main/resources/pptx/template.pptx

用法：
    python scripts/make-pptx-template.py            # 校验生成物与脚本输出一致（CI / 本地门禁）
    python scripts/make-pptx-template.py --write     # 重新生成（改模板参数后唯一正确入口）
    python scripts/make-pptx-template.py --root DIR  # 在指定仓库根上运行

退出码：0 = 逐字节一致 / 1 = 不一致（门禁红）/ 2 = 环境缺 python-pptx（**显式跳过**，
        不伪装成「通过」——CI 见到 2 打 warning 而不是绿）

纪律：
  - 生成物**逐字节确定**：ZIP 条目时间固定、条目顺序固定、压缩参数固定——
    换机器重跑脚本不会产生 diff（否则模板对拍门禁会变成噪声源）。
  - 生成物里**不放任何幻灯片**：幻灯片由 Kotlin 端追加（python-pptx 生成的幻灯片会
    带一堆 placeholder 继承关系，Kotlin 端反而要拆）。
  - 依赖版本写死在这里（模板字节随 python-pptx 版本变化，换版本必须重新 --write 并
    复核 diff）。
"""

from __future__ import annotations

import argparse
import io
import sys
import zipfile
from pathlib import Path

# 生成物相对仓库根的路径
TARGET = Path("host/src/main/resources/pptx/template.pptx")

# 模板参数（改动这里必须 --write 重生成）
SLIDE_WIDTH_EMU = 12192000   # 16:9（13.333in）
SLIDE_HEIGHT_EMU = 6858000   # 16:9（7.5in）

# ZIP 条目时间固定（2000-01-01 00:00:00）——保证生成物逐字节确定
FIXED_DATE_TIME = (2000, 1, 1, 0, 0, 0)

# 退出码约定：0 = 一致 / 1 = 不一致（门禁红）/ 2 = 环境缺依赖（显式跳过，不是「通过」）
EXIT_OK = 0
EXIT_MISMATCH = 1
EXIT_SKIPPED = 2


class DependencyMissing(Exception):
    """环境缺 python-pptx——对拍无法执行，显式跳过而不是伪装成通过。"""


class _Parser(argparse.ArgumentParser):
    """参数错误按「不一致」（退出码 1）处理——退出码 2 只表示「环境缺依赖，显式跳过」。

    argparse 默认的参数错误退出码是 2，与「跳过」撞码：CI 里一次拼错的参数会被误读成
    「环境缺依赖，跳过」。这里把参数错误改判为 1（红），让 2 的语义唯一。
    """

    def error(self, message):  # pragma: no cover - 仅在参数写错时触发
        self.print_usage(sys.stderr)
        print("[FAIL] 参数错误：%s" % message, file=sys.stderr)
        raise SystemExit(EXIT_MISMATCH)


def build_template_bytes() -> bytes:
    """生成模板底座字节（与仓库内生成物必须逐字节一致）。"""
    try:
        from pptx import Presentation
        from pptx.util import Emu
    except ImportError as exc:
        raise DependencyMissing(
            "[SKIP] 需要 python-pptx 才能重算模板底座（pip install python-pptx==1.0.2）：%s" % exc
        )

    prs = Presentation()
    prs.slide_width = Emu(SLIDE_WIDTH_EMU)
    prs.slide_height = Emu(SLIDE_HEIGHT_EMU)
    # 不加任何幻灯片——幻灯片由 Kotlin 端追加

    buffer = io.BytesIO()
    prs.save(buffer)
    raw = buffer.getvalue()

    # 重写一遍 ZIP：固定条目时间 + 固定压缩参数 + 修正 app.xml 里的
    # 「On-screen Show (4:3)」与幻灯片计数（python-pptx 不会同步这两处元数据）。
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(raw), "r") as src:
        with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                data = src.read(info.filename)
                if info.filename == "docProps/app.xml":
                    # python-pptx 不同步「屏幕演示 (4:3)」这一行元数据；改宽高比后要改过来
                    data = data.replace(
                        b"On-screen Show (4:3)", b"On-screen Show (16:9)"
                    )
                fixed = zipfile.ZipInfo(info.filename, date_time=FIXED_DATE_TIME)
                fixed.compress_type = zipfile.ZIP_DEFLATED
                fixed.external_attr = info.external_attr
                dst.writestr(fixed, data)
    return out.getvalue()


def main() -> int:
    parser = _Parser(description="生成 / 校验 PPTX 模板底座（T-169）")
    parser.add_argument("--write", action="store_true", help="重新生成并覆盖生成物")
    parser.add_argument("--check", action="store_true", help="校验生成物与脚本输出一致（默认行为）")
    parser.add_argument("--root", default=None, help="仓库根目录（默认取脚本上级目录）")
    args = parser.parse_args()

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    target = root / TARGET

    try:
        expected = build_template_bytes()
    except DependencyMissing as exc:
        print(str(exc))
        return EXIT_SKIPPED

    if args.write:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(expected)
        print("[OK] 已生成 %s（%d 字节）" % (TARGET.as_posix(), len(expected)))
        return EXIT_OK

    if not target.is_file():
        print("[FAIL] 生成物缺失：%s（先跑 --write）" % TARGET.as_posix())
        return EXIT_MISMATCH
    actual = target.read_bytes()
    if actual != expected:
        print(
            "[FAIL] 生成物与脚本输出不一致：%s\n"
            "       入库版本 %d 字节 / 期望 %d 字节\n"
            "       修法：python scripts/make-pptx-template.py --write（改模板参数的唯一入口）"
            % (TARGET.as_posix(), len(actual), len(expected))
        )
        return EXIT_MISMATCH
    print("[OK] 模板底座逐字节一致：%s（%d 字节）" % (TARGET.as_posix(), len(actual)))
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
