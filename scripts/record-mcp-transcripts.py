#!/usr/bin/env python3
"""T-189：重录 MCP 协议互操作快照夹具（fixtures/mcp-transcripts/）。

用法：python scripts/record-mcp-transcripts.py
流程：设置 STRAGENT_RECORD_MCP_TRANSCRIPTS=1 后跑 :data:test 的 McpSnapshotTest
（同一套驱动同时承担录制与回放，保证两侧同源）。
重录后必须 `git diff fixtures/mcp-transcripts` 逐行 review 再提交——金标纪律。
"""

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    os.environ["STRAGENT_RECORD_MCP_TRANSCRIPTS"] = "1"
    cmd = [
        str(ROOT / "gradlew.bat"),
        "cleanTest",
        ":data:test",
        "--tests", "com.stragent.data.McpSnapshotTest",
        "--no-daemon",
        "-Dorg.gradle.jvmargs=-Xmx1g",
    ]
    print("重录协议快照夹具（录制模式 = STRAGENT_RECORD_MCP_TRANSCRIPTS=1）...")
    code = subprocess.call(cmd, cwd=str(ROOT))
    if code != 0:
        print("重录失败（Gradle 退出码 %d）" % code)
        return code
    print("重录完成——请 `git diff fixtures/mcp-transcripts` 逐行 review 后再提交。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
