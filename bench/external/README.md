# 外部 SQL 基准：Spider 2.0-Lite SQLite 子集

T-057 引入的**外部参照系**：用公开基准检验「给定 SQL → 结果正确」的能力，防止评测基座
只在自家的 8 个套件里自证。数据集与题目来自
[Spider 2.0-Lite](https://github.com/xlang-ai/Spider2)（MIT License，见 `LICENSE-Spider2.txt`）。

## 口径

- **只评 SQL 执行正确性**：每题给一条已写好的 SQL，在官方 SQLite 快照上执行，与官方 gold
  结果比较。不经过 Agent / LLM，因此任何模型替换都不影响本套件。
- **50 题 / 20 个数据库**：从 Spider 2.0-Lite 的 `local*` SQLite 题中选取当前快照上 gold
  可复算的 50 题；题目、SQL、gold 全部入库。
- **计分 49 题**：`local331` 的三条官方 gold 互相矛盾（`55/2/2`、`55/1/1`、`52/3/2`），
  且与 `log.sqlite` 的任何自洽解释都对不上；保留在 manifest 中并写 `knownFailure` 原因，
  **不计入分母**，不伪造通过。
- **比较器**：逐条移植官方 `evaluate.py` 的 `compare_pandas_table` / `compare_multi`
  （0-based `condition_cols`、按列向量匹配、空值归一 0、浮点 `rel_tol=1e-9/abs_tol=1e-2`、
  整数精确、`ignore_order`、多条 gold 任一命中）。金标 CSV 用独立的最小读入器，
  **不 trim** 文本字段（pandas 同口径）。
- **基线**：`baseline/external-baseline.json`；通过率相对下降 **>5%** 即失败（非零退出），
  题集数量或已知失败集合变化会要求显式重建基线，不会静默换分母。

## 准备数据库（约 950MB，不入库）

```bash
# 已有官方 sqlite.zip（推荐；离线可复现）
python scripts/fetch-external-bench.py --zip D:/downloads/sqlite.zip

# 直接下载官方包（Google Drive，受限网络可能不可达）
python scripts/fetch-external-bench.py

# 只校验现有数据
python scripts/fetch-external-bench.py --check-only
```

脚本会校验整包 SHA256、只解出本子集用到的 20 个库，并逐库校验 SHA256 与字节数
（清单：`db-checksums.json`）。默认目录 `bench/external/data`，也可用
`STRAGENT_BENCH_EXTERNAL_DIR` 指向别处。

官方包 SHA256：`d97fc0f3f6bc3f1a83548ad2b32c646bfc67433c23a0c499d3450796196b2776`。
换包/换版本必须同步更新校验清单并重建基线，不能在运行时静默接受。

## 运行

```bash
# 只跑外部基准
STRAGENT_BENCH_EXTERNAL_DIR=bench/external/data ./gradlew --offline :bench:externalBench

# 全量评测（含外部基准；数据缺省时显示 SKIP，不阻断）
./gradlew --offline :bench:benchmark
```

- 数据不在：套件显式 **SKIP**（`[SKIP]`，备注写清缺哪些库），退出码 0。
  这是「没跑」，不是「通过」；报告里不会出现通过数。
- 数据在但大小与 `db-checksums.json` 不符：**FAIL**（坏数据不能伪装成 SKIP）。

## 基线维护

```bash
STRAGENT_BENCH_EXTERNAL_DIR=bench/external/data ./gradlew --offline :bench:externalBench -Pbench.updateBaseline
```

只有「口径一致且未退化」时才允许覆盖基线（退化时 `--update-baseline` 只给警告、不写盘）。
基线文件必须入库，首跑缺失时 `externalBench` 会直接失败（防止「没基线就放行」）。

## 目录

```
bench/external/
├── manifest.json              # 50 题：id/db/question/gold/condition_cols/ignore_order/knownFailure
├── sql/<id>.sql               # 每题一条 SQL（49 题可复现，local331 为 best-effort）
├── gold/exec_result/*.csv     # 50 题引用的官方 gold（80 个文件，约 22KiB）
├── baseline/external-baseline.json
├── db-checksums.json          # 20 个数据库的 SHA256 与字节数
└── LICENSE-Spider2.txt       # 上游 MIT 许可与归属
```
