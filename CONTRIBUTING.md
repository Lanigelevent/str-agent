# 贡献指南

感谢关注 STR AGENT。开 PR 之前请先读
[docs/mcp-tool-surface.md](docs/mcp-tool-surface.md)（工具面）与
[docs/QUICKSTART.md](docs/QUICKSTART.md)（可运行走查）。

## 工程门禁

每个 PR 必须过本仓自己的门禁——它们正是这个项目的核心价值：

```bash
./gradlew cleanTest :core:test :str-agent:test :data:test :engine-duckdb:test :host:test
./gradlew :bench:benchmark
python scripts/lint.py          # 静态分析 + 警告预算棘轮（新代码零警告）
python scripts/check-docs.py    # 文档一致性机器门禁（标题/链接/锚点）
./gradlew apiCheck              # 公开 API 二进制兼容快照
python scripts/secret-scan.py   # 全历史 secret 扫描（本地跑；不在公开 CI）
```

**负控纪律**：加门禁或修缺陷时，必须附带一个「证明门禁会红」的测试——
把受守护的行为改坏，测试必须当场变红。

## 提交规范

`feat|fix|test|docs|bench: 一句话摘要`——写清改了什么、为什么、怎么验证。
`git add` 点名具体路径；避免 `git add -A`。

## 许可

提交即表示同意你的贡献按 Apache License 2.0 授权。
