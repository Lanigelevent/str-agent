---
description: 用 Str Agent 对指定 CSV 生成可溯源的中文数据分析报告（导入 → 画像 → 报告 → 可继续追问）
---

# /str-agent-report

参数格式：`$ARGUMENTS` = `<csv 文件路径> [分析目标]`

（路径相对当前仓库或绝对路径；分析目标可空，空则按默认口径出报告。）

执行步骤：

1. **读文件**：把 `$ARGUMENTS` 里的 CSV 路径读成文本（UTF-8）。若路径不存在，先用目录/检索工具确认候选文件再问用户。
2. **导入**：调用本插件 `str-agent` MCP 服务器的 `stragent_import_table`
   参数 `csv=<文件全文>`、`name=<文件名>` → 拿到 `datasetId`。
3. **摸底**：调用 `stragent_get_column_profile(datasetId=<id>)` 逐列确认维度列与数值列。
4. **出报告**：调用 `stragent_generate_report(datasetId=<id>)`，把返回的 `report` 原文交付给用户
   （章节结构与数字溯源由插件保证，**不要改写其中的数字**）。
5. **可追问**：用户接着问细节时，调用 `stragent_ask_followup(datasetId=<id>, question=<用户原话>)`；
   要落到具体行则用 `stragent_list_rows` 补证。

注意事项：

- 报告里的每个数字都来自插件工具输出；用户追问时若报告里没有该数字，先用
  `stragent_get_group_aggregate` / `stragent_list_rows` 取数，再回答。
- 数据集是会话内存：若报「数据集不存在」，重新执行第 2 步导入。
- 超过 10,000 行时先与用户确认口径（分组聚合优先，别硬跑整表报告）。
