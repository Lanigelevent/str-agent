# 安全策略

## 信任模型

STR AGENT 是**本地优先**的工具。默认状态下：

- MCP 桥绑 `127.0.0.1` + Bearer 鉴权（CSPRNG 128-bit token、常量时间比较）；远程绑定
  **fail-closed**——未配 OAuth 2.1 issuer 拒绝启动。
- 除你配置的 LLM 端点（与可选 OTel 导出）外，工具**不主动联网**；网络型工具
  （`web_search`/`web_fetch`）不进对外工具面。
- 可选 Python 沙箱**默认关闭**；开启等同授予代码执行（RCE），需显式授权。
- 写盘受路径白名单与 `STRAGENT_HOME` 约束；SQL 仅 SELECT/WITH，带行数上限、看门狗，
  硬保证是只读数据库账号。

精确工具面与安全注记见 `docs/mcp-tool-surface.md`。

## 报告漏洞

在本仓开 [GitHub Security Advisory](https://github.com/advisories)，或直接联系维护者。
请附复现步骤；目标 7 天内确认。
