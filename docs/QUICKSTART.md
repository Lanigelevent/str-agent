# QUICKSTART · 5 分钟上手

> 目标：从零到第一次 MCP 调用拿到结果。两条路径任选：**本地 jar**（最轻）或 **Docker**（隔离）。
> 全部配置项的权威清单见 the operations guide；本文只覆盖最小集合。

---

## 路径 A · 本地 jar（约 3 分钟）

**前置**：JDK 17+（`java -version` 确认）。其余零依赖。

```bash
# 1. 构建（源码根目录；或跳过构建直接用发布的 str-agent-mcp-all.jar）
./gradlew :host:fatJar
# 产物：host/build/libs/host-<版本>-all.jar

# 2. 配置模型（D25 审查勘误：三样配置各走各的真实通道——文档此前给了两个不存在的 env）
export STRAGENT_API_KEY="sk-..."            # 唯一有 env 通道的项（env 优先、不落盘；缺省读 prefs）
# 模型名与服务商**没有 env 通道**——写进 prefs（$STRAGENT_HOME/prefs.properties，缺省 ~/.str-agent/）：
#   stepfun.vendor=stepfun                  # 服务商（可选值见 README 模型表；缺省 stepfun）
#   stepfun.customModel=your-model-id       # 自定义模型名（走 OpenAI 兼容协议）
# base URL 由服务商注册表决定；自建网关 = 选支持自定义端点的 vendor 或改 FilePrefs

# 3a. stdio 传输（挂进 Claude Desktop / 任意 MCP 宿主）
java -jar host/build/libs/host-*-all.jar --transport stdio

# 3b. HTTP 传输（默认 127.0.0.1:7787，Bearer 鉴权）
java -jar host/build/libs/host-*-all.jar
# token 落在 prefs 的 mcp.token 键——读取命令：
java -jar host/build/libs/host-*-all.jar token
```

## 路径 B · Docker（远程部署形态，约 2 分钟）

```bash
# T-146 起（BREAKING）：容器经端口映射访问 = 远程暴露，必须显式声明身份源——
# compose 未设 STRAGENT_OAUTH_ISSUER 时 `up` 在启动前直接报错退出（fail-closed，D6 §2.2.1）。
STRAGENT_API_KEY="sk-..." STRAGENT_OAUTH_ISSUER=<IdP 元数据 URL> docker compose up -d --build
# token 从该 issuer（IdP）签发 JWT——启用 OAuth 后静态 Bearer 失效，`token` 子命令不再被接受。
```

## 第一次调用

```bash
curl -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://127.0.0.1:7787/mcp        # 53 个工具的实时目录
```

典型链路：`stragent_import_table`（或 `stragent_import_text`，docx/pdf 支持）→
`stragent_generate_report`（出报告，零编造门禁在环）→ `stragent_ask_followup`（追问，段锚可解引用）。

## 最小 env 矩阵

| 变量 | 必填 | 说明 |
|---|---|---|
| `STRAGENT_API_KEY` | 建议 | 模型 Key（env 直配不落盘；也可 `token` 命令写入 prefs） |
| `STRAGENT_HOME` | 否 | 数据/偏好目录（Docker 内固定 /data；本地缺省 `~/.str-agent`） |
| `STRAGENT_HTTP_BIND` | 否 | 默认 127.0.0.1（容器部署必须 0.0.0.0，且必须配 `STRAGENT_OAUTH_ISSUER`——T-146 起未配拒绝启动） |
| 检索增强族 | 否 | `STRAGENT_EMBEDDING_*` / `STRAGENT_RERANK_*` / `STRAGENT_TEXT_QUERY_DECOMP` / `STRAGENT_TEXT_CONTEXTUAL` / `STRAGENT_TEXT_SUMMARY_TREE` / `STRAGENT_OCR`——全默认关，按需开启（语义见 D6） |

## 可信性机制速览

回答中的每个数字都过**零编造门禁**（白名单回链）；引用锚「段 N」可用 `stragent_read_segments`
解引用核对原文。评测基座 13 套件 991 断言随仓库交付（`./gradlew :bench:benchmark` 可复跑）。
