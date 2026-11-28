# STR AGENT · 确定性溯源门禁数据分析 Agent

> 把「导入数据 → 清洗 → 算指标 → 编报告」交给一次工具调用——中文分析报告自动生成，
> **报告里每一个数字都被机器校验、可回溯到原始行**。技术上是一个纯 JVM agent 插件，
> 装配为无头 MCP 服务器（stdio / Streamable HTTP 双传输），即插即用进任何 MCP 客户端。

**English TL;DR** — a deterministic, provenance-gated data-analysis agent: a headless
MCP server where every number in a generated report is machine-verified against the
actual data, and every analysis step is replayable on demand.

## 为什么不同：门禁是代码，不是模型

多数 LLM 报告生成器靠 LLM 自评——而自评会编造。STR AGENT 走相反的路：

- **数字白名单门禁**（`verifyReport`）：报告里每个数字都被抽取、归一化（含全角数字 /
  万亿量级 / Unicode 减号）、对照**由数据本身派生的白名单**逐个校验——白名单之外的数字一律判「未溯源」。
- **算式复核**：报告中的算式按数据重算；`50.6%` 按 `0.506` 自然值口径核对。
- **断言级溯源**：定性断言（「谁增长最快」）的主体实体必须存在于证据语料，否则判「无证据断言」。
- **溯源清单**：每次工具调用留痕（参数哈希 + 结果哈希 + 数据集哈希），可当场**确定性重放复核**
  （`stragent_provenance`）。
- **变异门守护校验器**：校验器本身在 CI 里做变异测试（杀死率 ≥ 80%）——门禁有被证明的牙齿。

## 能力一览

| 领域 | 提供什么 |
|---|---|
| 53 条注册工具（默认形态 44 条） | 导入（CSV/xlsx/ods/docx/PDF/parquet/文本）· 画像 · 聚合 · 趋势 · 相关性 · 预测 · what-if · 回归 · 假设检验 · 图表 · 导出 |
| 双轨引擎 | tier-1 内存引擎（≤1 万行，报告路径恒走此档）+ tier-2 DuckDB（探索与大聚合），逐样本对拍 |
| 语义层 | 可执行指标定义（表达式/聚合/维度/时间粒度/单位/自检 DSL），写入时口径冲突检测 |
| MCP 原生 | stdio + Streamable HTTP 双传输、MCP 2025/2026 协议合规、OAuth 2.1 资源服务器、异步任务、elicitation、进度流式 |
| 隐私盾（可选档） | LLM 出站 PII 脱敏（确定性单请求映射 `[[P1]]` 占位符）——手机号/身份证/银行卡（带校验和）/邮箱 |
| 注入加固 | 逐格规则中和（R1–R8）+ 数据载体对抗语料 + 导入时扫描回执 |
| 诚实降级 | 语义审查可以失败——并且**明说**（`qualityNotes`），绝不静默冒充已审 |

## 快速开始

前置：JDK 17+。无需预装 Gradle（自带 Wrapper，SHA-256 钉定）。

```bash
./gradlew :host:installDist
STRAGENT_HOME=~/.str-agent host/build/install/str-agent-mcp/bin/str-agent-mcp

# 另开窗口取 token
host/build/install/str-agent-mcp/bin/str-agent-mcp token

# 或 stdio 传输（无 token：进程边界即信任边界）
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
  host/build/install/str-agent-mcp/bin/str-agent-mcp --transport stdio
```

把任意 MCP 客户端（Claude Desktop、Cline、opencode 或自研 harness）接上来：

```json
{
  "mcpServers": {
    "str-agent": {
      "command": "host/build/install/str-agent-mcp/bin/str-agent-mcp",
      "args": ["--transport", "stdio"]
    }
  }
}
```

然后 `stragent_import_table` 导入 CSV → `stragent_get_group_aggregate` 聚合 →
`stragent_generate_report` 生成报告（模型端点自备，经 `STRAGENT_API_KEY` 配置）。

## 架构

```
core/           分析/报告/校验逻辑——纯 Kotlin，零三方运行时依赖，语义唯一真值源
str-agent/      agent 引擎插件：编辑部编排、agent 循环、断点续跑、插件契约
data/           端口适配层：HTTP LLM 客户端、数据存储、MCP 桥、OAuth、PII 盾
engine-duckdb/  tier-2 列式引擎（DuckDB）
bench/          13 个评测套件 + 性能回归门 + 覆盖率基线
host/           参考宿主 + 无头 MCP 桥进程
docs/           精编设计文档（概览/架构/决策/工具面）
scripts/        工程门禁（lint/文档一致性/变异测试/secret 扫描）
```

依赖方向单向：`host → data → str-agent → core`；`bench` 与 `engine-duckdb` 旁路依赖 `core`。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/D1-overview.md](docs/D1-overview.md) | 定位、适用范围、角色、术语表 |
| [docs/D2-architecture.md](docs/D2-architecture.md) | 模块分层、报告管线六步、执行保证、诚实限制 |
| [docs/D3-decisions.md](docs/D3-decisions.md) | 系统为什么长成这样（ADR 摘要，含被接受的代价） |
| [docs/mcp-tool-surface.md](docs/mcp-tool-surface.md) | MCP 工具面运行时快照（默认 44 条） |
| [docs/QUICKSTART.md](docs/QUICKSTART.md) | 从 CSV 到已验证报告的可运行走查 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 工程门禁与「改一个工具的完整流程」 |

## 测试与质量门禁

- **1,500+ 测试**横跨六个模块；CI（ubuntu）运行 core / agent / data / engine 四模块测试门禁——全绿。
  参考宿主套件与 bench 性能门按内部 CI 硬件标定，跨平台移植在 tracked work。
- 13 个 bench 套件 + 性能回归门（min-of-5 测量纪律）
- 校验核心与安全关键路径的变异测试（CI 杀死率 ≥ 80%）
- 全历史 secret 扫描；BCV 公开 API 快照检查；文档一致性机器门禁

## 安全

信任模型与披露渠道见 [SECURITY.md](SECURITY.md)。除你配置的 LLM 端点（与可选 OTel 导出）外
工具不主动联网；远程 HTTP 绑定强制 OAuth 2.1（fail-closed）。

## 贡献

欢迎 PR——先读 [CONTRIBUTING.md](CONTRIBUTING.md)。本项目刻意携带一套不寻常的工程门禁
（变异测试、文档一致性、公开 API 快照检查），CONTRIBUTING 解释每条为什么存在。

## 许可

[Apache License 2.0](LICENSE)。
