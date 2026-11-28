# STR AGENT · 确定性溯源门禁数据分析 Agent

![CI](https://github.com/Lanigelevent/str-agent/actions/workflows/ci.yml/badge.svg)
![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)
![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)
![MCP](https://img.shields.io/badge/MCP-2026--07--28-green.svg)
![Tests](https://img.shields.io/badge/tests-1500%2B-brightgreen.svg)

中文 | **[English](README.en.md)**

> 把「导入数据 → 清洗 → 算指标 → 生成中文行业报告」交给一次工具调用——
> **报告里每一个数字都被机器校验、可回溯到原始行**。技术上是一个纯 JVM agent 插件，
> 装配为无头 MCP 服务器（stdio / Streamable HTTP 双传输），即插即用进任何 MCP 客户端。

**English TL;DR** — a deterministic, provenance-gated data-analysis agent: a headless
MCP server where every number in a generated report is machine-verified against the
actual data, and every analysis step is replayable on demand.

## 目录

- [当前状态](#当前状态) · [为什么不同](#为什么不同门禁是代码不是模型) · [它解决的具体问题](#它解决的具体问题) · [能力一览](#能力一览)
- [我要用它（使用者）](#我要用它使用者) · [我要改代码（开发者）](#我要改代码开发者)
- [架构与模块职责](#架构与模块职责) · [关键设计决策（ADR）](#关键设计决策adr) · [配置与环境变量](#配置数据落点与环境变量)
- [数据与安全](#数据与安全) · [质量门禁与它的边界](#质量门禁与它的边界) · [已知限制](#已知限制)
- [CI 与交付现状](#ci签名与交付现状) · [排障速查](#排障速查) · [FAQ](#常见问题faq)
- [发版与版本口径](#发版与版本口径) · [文档地图](#文档地图) · [提问与反馈](#提问与反馈) · [贡献](#贡献) · [参考与致谢](#参考与致谢)

## 当前状态

各现值为时点快照，**漂移时以代码与 CI 为准**（公开仓不维护静态读数，数值由公开 CI 每次实跑重证）：

| 维度 | 现值 | 权威出处 |
|---|---|---|
| 版本号（唯一源） | `1.1.0` | 根 [build.gradle.kts](build.gradle.kts) 的 `version`（MCP `serverInfo.version` 读同值） |
| 工具面规模 | 注册 53 条、默认形态下发 44 条 | `./gradlew :host:run --args="--list-tools"` 现取；快照 [docs/mcp-tool-surface.md](docs/mcp-tool-surface.md) |
| 全量测试 | 1,500+ 例 / 六模块（公开 CI ubuntu 五模块全量） | CI build-gate 每次实跑；本地 `./gradlew test` |
| 覆盖率（LINE） | ≥ 87.77%（基线门禁强制） | [bench/baseline/coverage-baseline.json](bench/baseline/coverage-baseline.json)；`python scripts/coverage-gate.py` |
| 评测基座 | 13 套件（bench）+ Spider 2.0-Lite 外部基准 + DAB 子集（数据缺省显式 SKIP） | `./gradlew :bench:benchmark` |
| MCP 协议 | 2026-07-28 无状态核心 + Tasks 官方扩展 + OAuth 2.1 资源服务器 | [docs/mcp-tool-surface.md](docs/mcp-tool-surface.md) 头部 |
| 静态分析 | `lint.py` 0 Errors（存量警告预算制，新文件零警告） | `python scripts/lint.py` 退出码 |
| 许可 | Apache-2.0 | [LICENSE](LICENSE) |

## 为什么不同：门禁是代码，不是模型

多数 LLM 报告生成器靠 LLM 自评——而自评会编造。STR AGENT 走相反的路：

- **数字白名单门禁**（`verifyReport`）：报告里每个数字都被抽取、归一化（含全角数字 /
  万亿量级 / Unicode 减号）、对照**由数据本身派生的白名单**逐个校验——白名单之外的数字一律判「未溯源」。
- **算式复核**：报告中的算式按数据重算；`50.6%` 按 `0.506` 自然值口径核对。
- **断言级溯源**：定性断言（「谁增长最快」）的主体实体必须存在于证据语料，否则判「无证据断言」。
- **溯源清单**：每次工具调用留痕（参数哈希 + 结果哈希 + 数据集哈希 + **数据时点**），可当场**确定性重放复核**（`stragent_provenance`）。
- **变异门守护校验器**：校验器本身在 CI 里做变异测试（杀死率 ≥ 80%）——门禁有被证明的牙齿。

## 它解决的具体问题

业务里反复出错的三件事，本项目把每一件变成**可回滚 / 可核对 / 可重跑**的动作：

1. **「报告里的数字从哪来」**——手工编报告最难被审计。每个数字落进白名单，`verifyReport`
   按自然值口径（`50.6%` → `0.506`）逐个判红，`stragent_provenance` 可当场重放复核。
2. **「Excel 手工汇总容易抄错」**——导入即推断列类型并随回执返回理由；> 1 万行走确定性系统抽样，
   数字是样本口径并带 `sourceRow` 回链与文件指纹，可回源文件逐行核对。
3. **「同一份分析两次跑结果不一样」**——双轨数据层用同一套收尾与数值口径，`engine-parity`
   逐样本对拍；报告路径恒走 tier-1，tier-2 只服务探索与中间聚合。

一次典型使用（默认路径，全部在本地）：

1. 启动参考宿主（JDK 17）→ `tools/list` 列出当前形态工具面（默认 44 条）。
2. `stragent_import_table` 导入 CSV → 回执给 `datasetId`（内容指纹：同一份 CSV 重复导入返回同一个 id）。
3. `stragent_get_group_aggregate` 分组聚合 → `stragent_list_rows` 回看原始行 →
   `stragent_generate_report` 生成报告（模型端点自备，经 `STRAGENT_API_KEY` 配置）。

> 设计内分支（不是失败）：不配 LLM Key 时只有数据与确定性审计可用，报告链不产出——门禁工具照常工作。

## 能力一览

| 领域 | 提供什么 |
|---|---|
| 53 条注册工具（默认形态 44 条） | 导入（CSV/xlsx/ods/docx/PDF/parquet/文本）· 画像 · 聚合 · 趋势 · 相关性 · 预测 · what-if · 回归 · 假设检验 · 图表 · 导出 |
| 双轨引擎 | tier-1 内存引擎（≤1 万行，报告路径恒走此档）+ tier-2 DuckDB（探索与大聚合），逐样本对拍 |
| 语义层 | 可执行指标定义（表达式/聚合/维度/时间粒度/单位/自检 DSL），写入时口径冲突检测 |
| 报告门禁 | 确定性 `ReportGate`：硬问题 → LLM 修订（有界）→ 仍不清零则 `gateFailed=true` 不静默放行；全部报告出口统一过门禁 |
| MCP 原生 | stdio + Streamable HTTP 双传输、MCP 2026-07-28 合规、OAuth 2.1 资源服务器、异步任务（句柄落盘恢复）、elicitation、进度流式 |
| 隐私盾（可选档） | LLM 出站 PII 脱敏（确定性单请求映射 `[[P1]]` 占位符）——手机号/身份证/银行卡（带校验和）/邮箱 + **密钥与高熵 token 熵回退**（11 类前缀密钥集 + Shannon 熵判定，文件名/域名/UUID 不误伤） |
| 注入加固 | 逐格规则中和（R1–R8）+ 数据载体对抗语料 + 导入时扫描回执 + **db 回流面四入口注入扫描**（可见性非拦截） |
| 溯源时效 | 溯源清单带**数据时点标注**（datasetAsOf，调用时捕获）；导出报告自动带「报告元信息」节——时效标注非有效性判断 |
| 诚实降级 | 语义审查可以失败——并且**明说**（`qualityNotes`），绝不静默冒充已审 |

## 我要改代码（开发者）

```bash
# 全量测试 + 评测基座（门禁命令全清单见 CONTRIBUTING.md）
./gradlew test
./gradlew :bench:benchmark
python scripts/lint.py
python scripts/check-docs.py
./gradlew apiCheck
```

模块地图与依赖方向见下节「架构与模块职责」；「改一个工具的完整流程」（从工具签名到
门禁与负控设计）见 [CONTRIBUTING.md](CONTRIBUTING.md)。核心纪律三条：
**零三方运行时依赖**（纯 JDK，AD-004）、**校验器必须有负控**（证明它真的在比对）、
**诚实边界写进 KDoc 与文档**（不假装没有边界）。

## 架构与模块职责

```
core/           分析/报告/校验逻辑——纯 Kotlin，零三方运行时依赖，语义唯一真值源
str-agent/      agent 引擎插件：编辑部编排、agent 循环、断点续跑、插件契约
data/           端口适配层：HTTP LLM 客户端、数据存储、MCP 桥、OAuth、PII 盾
engine-duckdb/  tier-2 列式引擎（DuckDB）
bench/          13 个评测套件 + 外部基准（Spider/DAB）+ 性能回归门 + 覆盖率基线
host/           参考宿主 + 无头 MCP 桥进程 + 七格式导出器
docs/           精编设计文档（概览/架构/决策/工具面/快速开始）
scripts/        工程门禁（lint/文档一致性/变异测试/secret 扫描/导出管道）
```

依赖方向单向：`host → data → str-agent → core`；`bench` 与 `engine-duckdb` 旁路依赖 `core`。

## 关键设计决策（ADR）

完整决策文档见 [docs/D3-decisions.md](docs/D3-decisions.md)。几条最影响「这个仓为什么是
现在这个样子」的决策，每条能被一句话复述：

| ADR | 一句话结论 |
|---|---|
| AD-003 | 审计是代码（`verifyReport`），不用 LLM-as-judge——在「模型自评」与「确定性复核」之间选后者 |
| AD-004 | 表格解析零依赖：ZIP + JDK SAX，不引 POI——代价是自己维护解析面 |
| AD-007 | MCP 桥默认只绑 127.0.0.1 + Bearer——回环默认安全，代价是远程访问需显式配置 |
| AD-013 / AD-024 | 不做 NL-to-SQL；只执行调用方给出的单条 SELECT/WITH——只执行、不生成 |
| AD-019 | 双轨数据层：报告恒 tier-1、tier-2 独立成模块——规模与可信不互斥，代价是 engine-parity 对拍门禁 |
| AD-020 | 产品形态 = 纯 agent 插件（无自带 UI/APK）——宿主可插拔，代价是放弃自带分发渠道 |
| AD-025 | MCP 工具面唯一事实源是运行时注册表——文档只是快照、须注明日期 |
| AD-028 | 所有报告出口必须过确定性门禁（ReportGate）——全出口一致，代价是门禁常态化 |
| AD-037 | 协议升级走「声明版本 + 无状态核心 + 12 个月弃用窗口」 |
| AD-039 | 外部基准选可离线复算的子集；门禁一律「相对基线 + 显式 SKIP」——SKIP 也算证据 |

## 配置、数据落点与环境变量

**数据落点**（`STRAGENT_HOME`，默认 `~/.str-agent`）：

| 位置 | 装什么 | 能不能删 |
|---|---|---|
| `<home>/kv/` | 数据集（内容指纹键）/ 用户指标 / 语义层记忆 / 会话记忆（按数据指纹） | 删了 = 清空已持久化数据与指标 |
| `<home>/checkpoints/` | agent / 编辑部阶段断点（原子写出） | 删了 = 放弃未完成任务续跑 |
| `<home>/prefs.properties` | 偏好（含 MCP token） | 删了 = 重新生成 token（客户端需重配） |
| `<home>/api-key` 或环境变量 | LLM API Key | 只走环境变量或密钥文件，绝不入库 |
| `<home>/tasks.jsonl` | 任务句柄落盘（T-204） | 可删；重启后终态任务在 TTL 内仍可取 |

**常用环境变量**（默认值一律以代码为准）：

| 变量 | 取值 / 默认 | 什么时候要设它 |
|---|---|---|
| `STRAGENT_HOME` | 默认 `~/.str-agent` | 改数据 / 日志落点；Docker 用 `str-agent-data` 卷 |
| `STRAGENT_HTTP_BIND` | 默认 `127.0.0.1` | 局域网/容器可达时；**远程绑定必须同时配 `STRAGENT_OAUTH_ISSUER`，否则拒绝启动** |
| `STRAGENT_API_KEY` | 无默认 | 报告链调用你的 LLM 端点 |
| `STRAGENT_SANDBOX` | 默认关 | 开启 = 允许任意 Python 代码（**RCE**）；只在信任调用方时开 |
| `STRAGENT_OAUTH_ISSUER` | 默认关 | 远程绑定切 OAuth 2.1 资源服务器（仅 RS256） |
| `STRAGENT_PII_SHIELD` | 默认关 | 出站 PII 脱敏 + 密钥/高熵 token 熵回退 |
| `STRAGENT_DB_TOOLS` | 默认关 | 显式授权后 db_* 6 条工具下发 |
| `STRAGENT_TASKS_FILE` | 默认 `<home>/tasks.jsonl` | 任务句柄落盘路径覆盖 |
| `STRAGENT_BENCH_EXTERNAL_DIR` | 无默认 | 外部基准数据目录（缺数据显式 SKIP） |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | 无默认 | 配了才导出 traces/metrics/logs；未配 = 零成本、不联网 |

## 数据与安全

面向内网 / 离线场景，下面每条都是**能当场核**的事实。信任模型与披露渠道见 [SECURITY.md](SECURITY.md)：

| 面 | 事实 |
|---|---|
| **网络** | 工具不主动联网；唯一出站 = 你配置的 LLM 端点 / vision 接口 / OTel / 对象存储只读导入（可选）；对外工具面无网络型工具 |
| **本机鉴权** | HTTP 强制 Bearer（CSPRNG 128-bit，常量时间比较）；监听非回环必须显式配 bind **且配 OAuth issuer，否则拒绝启动**；远程绑定默认限流 10 req/s |
| **路径** | 写盘受白名单与 `STRAGENT_HOME` 约束；解压总量上限 256MB（超出按「疑似压缩炸弹」导入失败） |
| **SQL 只读** | 工具层校验仅允许单条 SELECT/WITH + 5 万行上限 + 5s 看门狗；**硬保证是数据库只读账号**，工具层拒绝只是纵深防御 |
| **Python 沙箱** | 默认关闭；`STRAGENT_SANDBOX=1` 开启后**等同 RCE 授权**（导入白名单/禁网络/受限 builtins/10s 杀进程树）。**不是 OS 级隔离** |
| **数据落盘** | 数据集 = 内容指纹；溯源清单含 SQL 的结构与哈希，**密码字段脱敏**；导出七格式 |
| **隐私与回流面** | 出站 LLM 流量过 PII 盾（opt-in）；db 回流面注入扫描恒输出可见；溯源与导出带数据时点标注 |
| **供应链** | 依赖校验元数据（逐构件 SHA-256）+ wrapper SHA-256 + Docker 镜像按 digest 钉定；secrets 全历史扫描在 CI |

## 质量门禁与它的边界

🔴 **「门禁全绿」不等于「交付面全验过」**。默认入口没有覆盖的：

| # | 没验的东西 | 单独怎么验 |
|---|---|---|
| 1 | Python 沙箱（默认关闭） | 显式 `STRAGENT_SANDBOX=1` 跑对应测试 |
| 2 | 真实模型报告质量（需 Key） | opt-in 真实端点评测（单厂商单批次口径） |
| 3 | 校验器变异测试（改 core/verify 后必跑） | `python scripts/mutation-verify.py`（杀死率 < 80% 退出 1） |
| 4 | 外部基准（缺数据时 CI 显式 SKIP，不等于通过） | `STRAGENT_BENCH_EXTERNAL_DIR=… ./gradlew :bench:externalBench` |
| 5 | Docker 镜像与容器冒烟 | `docker build -t str-agent-mcp:verify .` |
| 6 | macOS 平台 | 零覆盖（目标平台自测）；Windows/Linux 双 CI 全量 |

三条红线：手改生成文件 → `--check` 立红；改公开面不 `apiDump` → `apiCheck` 红；
密钥入库（含 prefs/报告/日志）→ `secret-scan.py` 红（全历史扫描）。

## 已知限制

| 限制 | 含义 |
|---|---|
| 无真实模型质量背书 | bench 是回归评分卡、不是 LLM 质量评测；真实抽样只有单一厂商、单一样本批次 |
| 数字门禁非语义门禁 | `verifyReport` 只判数字与算式溯源；「观点对不对」靠粗粒度启发式，复杂句式可能漏过 |
| 报告口径 = 样本口径 | > 1 万行的大文件报告基于确定性系统抽样；数字代表样本而非全量（`sourceRow` 回链可核） |
| i18n 不完整 | 模型面刻意 zh-first 中文；英文资源仅框架性键 |
| tier-2 只服务探索 | 报告恒 tier-1（≤ 1 万行或抽样进内存）；百万行大字段无法在线报告 |
| OAuth 2.1 无真实 IdP 互操作 | 远程绑定鉴权只测过 RS256 自建桩；OTel 只验证过 Jaeger（Tempo 未实测） |
| 任务句柄恢复是句柄级非执行级 | 重启后终态任务可取、非终态如实转 failed 不续跑；落盘 best-effort |
| macOS 零覆盖 | Windows/Linux 双 CI 全量绿；macOS 需目标平台自测 |

## CI、签名与交付现状

| 通道 | 跑什么 | 触发 |
|---|---|---|
| GitHub Actions（ubuntu） | 五模块全量测试 + bench + lint + docs 四步 | push / PR / 手动 |
| 内部 CI（自托管 Windows） | 同口径门禁 + 覆盖率基线门 + secret 全历史扫描 | push / PR |

供应链：wrapper SHA-256 钉定 + 依赖校验元数据（逐构件 SHA-256）+ Docker 镜像按 manifest digest 钉定。

## 排障速查

| 症状 | 第一步 |
|---|---|
| 导入成功、后续聚合报「维度列不存在」 | 请求体不是 UTF-8（GBK 字节会静默把列名存成乱码） |
| 工具列表比文档少几条 | 目录裁剪是设计（T-155/T-190）：沙箱/视觉/db 按授权下发，`--list-tools` 现取为准 |
| 远程绑定启动直接报错退出 | 未配 `STRAGENT_OAUTH_ISSUER`（fail-closed 是设计不是故障） |
| 报告生成返回 `gateFailed: true` | 数字门禁没清零——看 `qualityNotes` 与 `stragent_provenance` 重放定位 |
| 回执里出现 `[[P1]]` 占位符 | PII 盾开着而模型改写了占位符——还原失败留痕，关盾或让模型原样引用 |
| 大文件导入后数字对不上源文件 | > 1 万行走确定性系统抽样——回执带 `sourceRow` 回链与文件指纹，按口径核对 |
| gradle 构建依赖解析失败 | 检查网络与镜像可用性（公开仓用 Maven Central） |

## 常见问题（FAQ）

**Q：报告里的数字真的可信吗？**
A：门禁保证的是「每个数字都来自数据或可复算的算式」，不保证语义正确——观点与结论的
公允性靠粗粒度审查，边界见「已知限制」。

**Q：为什么不内置 SQL 生成（NL2SQL）？**
A：设计决策（AD-013/024）：只执行调用方给出的单条只读 SELECT，不替用户写 SQL——
「只执行、不生成」换可审计性。

**Q：一定要配 LLM Key 才能用吗？**
A：数据导入、画像、聚合、导出、溯源重放全程可用（确定性工具面）；只有报告生成链需要你的端点。

**Q：数据会离开我的机器吗？**
A：默认不出本机；唯一出站是你配置的 LLM/OTel 端点。PII 盾开启时出站文本先脱敏（占位符随请求存亡）。

**Q：能接入 Claude Desktop / Cline 等客户端吗？**
A：能——标准 MCP 服务器（stdio 或 Streamable HTTP）。配置示例见「我要用它」路线 B。

## 发版与版本口径

- 版本号唯一源 = 根 `build.gradle.kts` 的 `version`；MCP `serverInfo.version` 读同值。
- 公开 API 面受 BCV 守护（`apiCheck`）；「公开面增长是有意识动作」。
- 语义化版本；破坏性变更走 MCP 12 个月弃用窗口（当前协议至 2027-07 兼容）。

## 文档地图

| 文档 | 内容 |
|---|---|
| [docs/D1-overview.md](docs/D1-overview.md) | 定位、适用范围、角色、术语表 |
| [docs/D2-architecture.md](docs/D2-architecture.md) | 模块分层、报告管线六步、执行保证、诚实限制 |
| [docs/D3-decisions.md](docs/D3-decisions.md) | 系统为什么长成这样（ADR 摘要，含被接受的代价） |
| [docs/mcp-tool-surface.md](docs/mcp-tool-surface.md) | MCP 工具面运行时快照（默认 44 条） |
| [docs/QUICKSTART.md](docs/QUICKSTART.md) | 从 CSV 到已验证报告的可运行走查 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 工程门禁与「改一个工具的完整流程」 |
| [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | 社区行为准则（Contributor Covenant 2.1） |
| [SECURITY.md](SECURITY.md) | 信任模型与安全披露渠道 |
| [FIXTURES.md](FIXTURES.md) | 测试数据集来源与许可分类（随收回承诺发布的口径） |
| [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) | PR 门禁清单与定位过滤器自查 |

## 提问与反馈

使用问题先查 [QUICKSTART](docs/QUICKSTART.md) 与上方文档表；提交 issue 请用模板
（**Bug 报告** / **能力提案**）。能力提案模板内置定位过滤器自查——涉及自带 UI、NL2SQL、
SaaS 托管等已声明非目标的提案会被关闭并指向决策文档。

## 贡献

欢迎 PR——先读 [CONTRIBUTING.md](CONTRIBUTING.md)。本项目刻意携带一套不寻常的工程门禁
（变异测试、文档一致性、公开 API 快照检查），CONTRIBUTING 解释每条为什么存在。
PR 请走 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md)（内置门禁清单与定位过滤器自查）；
社区行为准则见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)（Contributor Covenant 2.1）。

## 许可

[Apache License 2.0](LICENSE)。

## 参考与致谢

- [MCP（Model Context Protocol）](https://modelcontextprotocol.io)——工具面协议基座；自研桥逐字段对齐官方 2026-07-28 规范。
- [DuckDB](https://duckdb.org)——tier-2 列式引擎（JDBC）。
- [Apache PDFBox](https://pdfbox.apache.org) / Batik——PDF 渲染与导出链上游。
- [Spider 2.0-Lite](https://spider2-sql.github.io) 与 DAB——外部评测基准（数据缺省显式 SKIP）。
- 测试数据集来源与许可分类见 [FIXTURES.md](FIXTURES.md)。
