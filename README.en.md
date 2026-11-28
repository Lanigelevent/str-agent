# STR AGENT · Deterministic Provenance-Gated Data Analysis Agent

![CI](https://github.com/Lanigelevent/str-agent/actions/workflows/ci.yml/badge.svg)
![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)
![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)
![MCP](https://img.shields.io/badge/MCP-2026--07--28-green.svg)
![Tests](https://img.shields.io/badge/tests-1500%2B-brightgreen.svg)

**[中文文档](README.md)** | English

> Hand off "import data -> compute metrics -> generate a Chinese industry report" to a single
> tool call — **every number in the generated report is machine-verified against the actual data
> and traceable back to source rows**. Technically a pure-JVM agent plugin assembled as a headless
> MCP server (stdio / Streamable HTTP), pluggable into any MCP client.

**TL;DR** — a deterministic, provenance-gated data-analysis agent: every number in a generated
report is machine-verified against the data, and every analysis step is replayable on demand.

## Table of contents

- [Current status](#current-status) · [Why it is different](#why-it-is-different-gates-are-code-not-models) · [What problems it solves](#what-problems-it-solves) · [Capabilities](#capabilities)
- [I want to use it](#i-want-to-use-it-user) · [ README](#i-want-to-change-code-developer)
- [Architecture](#architecture-and-module-responsibilities) · [Key design decisions](#key-design-decisions-adr) · [Configuration](#configuration-data-locations-and-environment-variables)
- [Data & security](#data--security) · [Quality gates](#quality-gates-and-their-boundaries) · [Known limitations](#known-limitations)
- [CI & delivery](#ci-and-delivery-status) · [Troubleshooting](#troubleshooting) · [FAQ](#faq)
- [Release](#release-and-versioning) · [Docs](#documentation-map) · [Questions](#questions-and-feedback) · [Contributing](#contributing) · [Acknowledgements](#references-and-acknowledgements)

## Current status

Values are point-in-time snapshots; **code and CI win when they drift** (public numbers are
re-proven by CI on every run, not static claims):

| Dimension | Value | Authoritative source |
|---|---|---|
| Version (single source) | `1.1.0` | `version` in root [build.gradle.kts](build.gradle.kts) (MCP `serverInfo.version` reads the same) |
| Tool surface | 53 registered / 44 default | `--list-tools` at runtime; snapshot in [docs/mcp-tool-surface.md](docs/mcp-tool-surface.md) |
| Tests | 1,500+ across six modules (public CI: five modules on ubuntu) | CI build-gate every run; local `./gradlew test` |
| Line coverage | >= 87.77% (baseline gate enforced) | [bench/baseline/coverage-baseline.json](bench/baseline/coverage-baseline.json); `python scripts/coverage-gate.py` |
| Evaluation | 13 bench suites + Spider 2.0-Lite & DAB external benches (explicit SKIP without data) | `./gradlew :bench:benchmark` |
| MCP protocol | 2026-07-28 stateless core + official Tasks extension + OAuth 2.1 resource server | [docs/mcp-tool-surface.md](docs/mcp-tool-surface.md) header |
| Static analysis | `lint.py` 0 errors (budgeted legacy warnings; zero for new files) | `python scripts/lint.py` exit code |
| License | Apache-2.0 | [LICENSE](LICENSE) |

## Why it is different: gates are code, not models

Most LLM report generators rely on LLM self-review — which can fabricate. STR AGENT takes the
opposite path:

- **Numeric whitelist gate** (`verifyReport`): every number in a report is extracted, normalized
  (full-width digits, magnitude units, Unicode minus), and checked against a whitelist **derived
  from the data itself** — anything outside is flagged as unprovenanced.
- **Formula recheck**: arithmetic in the report is recomputed from data; `50.6%` is checked as `0.506`.
- **Claim-level tracing**: subjects of qualitative claims must exist in the evidence corpus,
  otherwise flagged as unsupported.
- **Provenance manifest**: every tool call is recorded (args hash + result hash + dataset hash +
  **data as-of time**) and can be **deterministically replayed** (`stragent_provenance`).
- **Mutation gate guards the guard**: the verifier itself is mutation-tested in CI (kill rate >= 80%).

## What problems it solves

Three recurring business failures, each turned into a **rollback-able / checkable / replay-able** action:

1. **"Where did this number come from?"** — every number lands in the whitelist;
   `stragent_provenance` replays the whole chain on demand.
2. **"Manual spreadsheet rollups copy errors"** — import infers column types and returns the
   reasoning; files over 10k rows use deterministic systematic sampling with `sourceRow` back-links.
3. **"Same analysis, different results"** — dual-engine data layer shares one normalization;
   `engine-parity` cross-checks per sample; reports always run on tier-1.

A typical run (local-only by default):

1. Start the reference host (JDK 17) -> `tools/list` (44 tools in default shape).
2. `stragent_import_table` a CSV -> get a content-fingerprint `datasetId`.
3. `stragent_get_group_aggregate` -> `stragent_list_rows` -> `stragent_generate_report`
   (bring your own LLM endpoint via `STRAGENT_API_KEY`).

> By-design branch: without an LLM key, data and deterministic audit tools still work; only the
> report chain is unavailable. The gates keep working.

## Capabilities

| Area | What you get |
|---|---|
| 53 registered tools (44 default) | Import (CSV/xlsx/ods/docx/PDF/parquet/text) · profile · aggregate · trend · correlation · forecast · what-if · regression · hypothesis test · charts · export |
| Dual engine | tier-1 in-memory (<= 10k rows; reports always here) + tier-2 DuckDB, cross-checked per sample |
| Semantic layer | Executable metric definitions with write-time conflict detection |
| Report gate | Deterministic `ReportGate`: hard issues -> bounded LLM revision -> still failing => `gateFailed=true`, never silently passes |
| MCP native | stdio + Streamable HTTP, MCP 2026-07-28 compliance, OAuth 2.1 resource server, async tasks (handles survive restart), elicitation, progress streaming |
| Privacy shield (opt-in) | Outbound PII masking (deterministic per-request `[[P1]]` placeholders) + **secrets & high-entropy token fallback** (11 curated prefixes + Shannon entropy; filenames/domains/UUIDs untouched) |
| Injection hardening | Per-cell rule neutralization (R1-R8) + adversarial corpus + import-time scan receipts + **injection scan on all four db return paths** (visibility, not blocking) |
| Provenance freshness | Manifest carries **dataset as-of time**; exports embed an auto "report metadata" section — annotation, not validity judgment |
| Honest degradation | Semantic review may fail — and **says so** (`qualityNotes`), never silently claims audited |

## I want to use it (user)

**Prerequisites**: JDK 17+ with `JAVA_HOME`; no Gradle install needed (wrapper pinned by SHA-256).

### Route A · HTTP long-running server (recommended)

```bash
./gradlew :host:installDist
STRAGENT_HOME=~/.str-agent host/build/install/str-agent-mcp/bin/str-agent-mcp

# separate terminal: fetch token (32 hex chars)
host/build/install/str-agent-mcp/bin/str-agent-mcp token
```

```bash
curl -s -H "Authorization: Bearer <token>" -H "Content-Type: application/json; charset=utf-8" \
  --data-binary '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  http://127.0.0.1:7787/mcp
```

Expected: `result.tools` = current shape count (44 by default; more when sandbox/vision/db are
authorized — always re-check with `--list-tools`). Full walkthrough: [docs/QUICKSTART.md](docs/QUICKSTART.md).

### Route B · stdio (client-spawned, no token)

Point your MCP client at
`host/build/install/str-agent-mcp/bin/str-agent-mcp --transport stdio` — process boundary is the
trust boundary:

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

Smoke test:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
  host/build/install/str-agent-mcp/bin/str-agent-mcp --transport stdio
```

Expected: exactly one JSON-RPC line on stdout; banner goes to stderr.

### Route C · Container (HTTP + OAuth 2.1)

```bash
STRAGENT_OAUTH_ISSUER=https://idp.example.com docker compose up -d --build
```

Without `STRAGENT_OAUTH_ISSUER` compose fails fast before starting (fail-closed); with OAuth
enabled, static Bearer tokens stop working. Data volume: `str-agent-data`.

### Provenance replay & export (optional)

- `stragent_provenance`: fetch the provenance manifest and **replay it on the spot**.
- `stragent_export_report`: xlsx / docx / pdf / pptx / html / landing; with provenance present,
  exports embed a "report metadata" section (dataset + short hash + data as-of).

## I want to change code (developer)

```bash
# Full gate (complete list in CONTRIBUTING.md)
./gradlew test
./gradlew :bench:benchmark
python scripts/lint.py
python scripts/check-docs.py
./gradlew apiCheck
```

Module map and dependency direction in "Architecture" below; the full "how to change one tool"
workflow lives in [CONTRIBUTING.md](CONTRIBUTING.md). Three core disciplines:
**zero third-party runtime dependencies** (pure JDK, AD-004), **every verifier needs a negative
control** (prove it really compares), **honest boundaries in KDoc and docs**.

## Architecture and module responsibilities

```
core/           analysis/report/verification — pure Kotlin, zero third-party runtime deps, single source of semantic truth
str-agent/      agent engine plugin: editorial orchestration, agent loop, resumable checkpoints, plugin contracts
data/           port adapter layer: HTTP LLM client, storage, MCP bridge, OAuth, PII shield
engine-duckdb/  tier-2 columnar engine (DuckDB)
bench/          13 eval suites + external benches (Spider/DAB) + perf regression gate + coverage baseline
host/           reference host + headless MCP bridge + seven export formats
docs/           curated design docs (overview/architecture/decisions/tool surface/quickstart)
scripts/        engineering gates (lint/doc consistency/mutation/secret scan/export pipeline)
```

Dependency direction one-way: `host -> data -> str-agent -> core`; `bench` and `engine-duckdb` depend on `core` sideways.

## Key design decisions (ADR)

Full decision log in [docs/D3-decisions.md](docs/D3-decisions.md). The decisions that most shape
this repo, each restatable in one sentence:

| ADR | One-sentence conclusion |
|---|---|
| AD-003 | Audit is code (`verifyReport`), not LLM-as-judge — chose deterministic review over model self-review |
| AD-004 | Zero-dependency table parsing: ZIP + JDK SAX, no POI — the cost is maintaining the parsing surface |
| AD-007 | MCP bridge binds 127.0.0.1 + Bearer by default — loopback-safe default; remote needs explicit config |
| AD-013 / AD-024 | No NL-to-SQL; only execute caller-provided single SELECT/WITH — execute, don't generate |
| AD-019 | Dual-track data layer: reports always tier-1, tier-2 a separate module — scale and trust are not mutually exclusive |
| AD-020 | Product shape = pure agent plugin (no bundled UI/APK) — hosts stay pluggable |
| AD-025 | The runtime registry is the single source for the tool surface — docs are snapshots, must carry dates |
| AD-028 | Every report exit must pass the deterministic gate — consistency across all exits |
| AD-037 | Protocol upgrades: declared version + stateless core + 12-month deprecation window |
| AD-039 | External benches choose offline-replayable subsets; gates are "relative baseline + explicit SKIP" — a SKIP is evidence too |

## Configuration, data locations and environment variables

**Data locations** (`STRAGENT_HOME`, default `~/.str-agent`):

| Location | Contents | Safe to delete? |
|---|---|---|
| `<home>/kv/` | datasets (content-fingerprint keyed), user metrics, semantic memory | deleting empties persisted data |
| `<home>/checkpoints/` | agent / editorial phase checkpoints | deleting abandons in-flight resumes |
| `<home>/prefs.properties` | preferences (incl. MCP token) | deleting regenerates token |
| `<home>/api-key` or env | LLM API key | env or key file only, never in repo/prefs |
| `<home>/tasks.jsonl` | task-handle persistence | safe; terminal tasks still resolvable within TTL |

**Common environment variables** (defaults live in code):

| Variable | Default | When to set |
|---|---|---|
| `STRAGENT_HOME` | `~/.str-agent` | data/log location; Docker uses the `str-agent-data` volume |
| `STRAGENT_HTTP_BIND` | `127.0.0.1` | LAN/container reach; **remote bind requires `STRAGENT_OAUTH_ISSUER` or startup refuses** |
| `STRAGENT_API_KEY` | none | report chain calls your LLM endpoint |
| `STRAGENT_SANDBOX` | off | on = arbitrary Python (**RCE**); only with trusted callers |
| `STRAGENT_OAUTH_ISSUER` | off | remote bind switches to OAuth 2.1 resource server (RS256) |
| `STRAGENT_PII_SHIELD` | off | outbound PII masking + secrets/entropy fallback |
| `STRAGENT_DB_TOOLS` | off | explicit authorization for the 6 db_* tools |
| `STRAGENT_TASKS_FILE` | `<home>/tasks.jsonl` | task-handle persistence path override |
| `STRAGENT_BENCH_EXTERNAL_DIR` | none | external bench data dir (explicit SKIP without data) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | none | traces/metrics/logs export; unset = zero cost, no network |

## Data & security

Every row below is a **checkable fact** for intranet/offline use. Trust model and disclosure:
[SECURITY.md](SECURITY.md):

| Surface | Fact |
|---|---|
| **Network** | tools make no outbound calls; the only egress = your LLM endpoint / vision / OTel / optional object-storage import |
| **Local auth** | HTTP requires Bearer (CSPRNG 128-bit, constant-time compare); non-loopback bind requires explicit bind **and OAuth issuer or startup refuses**; remote bind rate-limited 10 req/s |
| **Paths** | writes gated by whitelist + `STRAGENT_HOME`; decompression capped 256MB (zip-bomb import failure) |
| **SQL read-only** | single SELECT/WITH only + 50k row cap + 5s watchdog; **hard guarantee is a read-only DB account**, tool checks are defense-in-depth |
| **Python sandbox** | default off; `STRAGENT_SANDBOX=1` = **RCE authorization** (import whitelist/no network/restricted builtins/10s process-tree kill). **Not OS-level isolation** |
| **Data at rest** | datasets = content fingerprints; provenance stores SQL structure+hash, **password fields masked** |
| **Privacy & return paths** | outbound LLM traffic through PII shield (opt-in); db return-path injection scan always on (visibility); provenance/export carry data as-of |
| **Supply chain** | per-artifact SHA-256 verification metadata + wrapper SHA-256 + Docker images pinned by digest; full-history secret scan in CI |

## Quality gates and their boundaries

🔴 **"Gates green" is not "delivery fully verified"**:

| # | Not covered by default gates | How to verify separately |
|---|---|---|
| 1 | Python sandbox (default off) | explicit `STRAGENT_SANDBOX=1` + corresponding tests |
| 2 | Real-model report quality (needs key) | opt-in real-endpoint evaluation (single-vendor, single-batch) |
| 3 | Verifier mutation test (required after core/verify changes) | `python scripts/mutation-verify.py` (exit 1 below 80% kill rate) |
| 4 | External benches (SKIP ≠ pass) | `STRAGENT_BENCH_EXTERNAL_DIR=… ./gradlew :bench:externalBench` |
| 5 | Docker image & container smoke | `docker build -t str-agent-mcp:verify .` |
| 6 | macOS | zero coverage (self-test on target platform); Windows/Linux dual CI fully green |

Three red lines: hand-editing generated files -> `--check` goes red; touching the public API
without `apiDump` -> `apiCheck` red; secrets in the repo (incl. prefs/reports/logs) ->
`secret-scan.py` red (full-history scan).

## Known limitations

| Limitation | Meaning |
|---|---|
| No real-model quality endorsement | bench is a regression scorecard, not an LLM quality evaluation; real sampling is single-vendor, single-batch |
| Numeric gate is not a semantic gate | `verifyReport` checks numbers and formulas; fairness/opinion rely on coarse heuristics and can miss complex phrasing |
| Report scope = sample scope | > 10k-row files report on deterministic systematic sampling; numbers are sample-scoped (`sourceRow` back-links) |
| i18n incomplete | model-facing surface is deliberately zh-first; English resources are framework keys only |
| tier-2 is exploration-only | reports always tier-1 (<= 10k rows or sampled into memory); million-row columns cannot be reported online |
| OAuth 2.1 without real-IdP interop | remote auth tested only against an RS256 stub; OTel verified only on Jaeger |
| Task-handle recovery is handle-level | terminal tasks survive restart; non-terminal honestly become `failed` (no fake resume); persistence is best-effort |
| macOS zero coverage | Windows/Linux dual CI fully green; macOS needs self-testing |

## CI and delivery status

| Channel | What runs | Trigger |
|---|---|---|
| GitHub Actions (ubuntu) | five-module tests + bench + lint + docs | push / PR / manual |
| Internal CI (self-hosted Windows) | same gates + coverage baseline + full-history secret scan | push / PR |

Supply chain: wrapper SHA-256 pinned + per-artifact verification metadata + Docker images pinned by manifest digest.

## Troubleshooting

| Symptom | First step |
|---|---|
| Import OK, later aggregate says "dimension column not found" | request body was not UTF-8 (GBK bytes silently corrupt column names) |
| Fewer tools than documented | catalog pruning is by design (sandbox/vision/db are authorization-gated; `--list-tools` is truth) |
| Remote bind fails at startup | `STRAGENT_OAUTH_ISSUER` missing (fail-closed is by design) |
| Report returns `gateFailed: true` | numeric gate not cleared — check `qualityNotes` and replay via `stragent_provenance` |
| `[[P1]]` placeholders appear in receipts | PII shield on and the model rewrote a placeholder — restore failed and left a mark |
| Numbers do not match the source file | > 10k rows use systematic sampling — follow `sourceRow` back-links |
| Gradle dependency resolution fails | check network/mirror availability (public repo uses Maven Central) |

## FAQ

**Q: Are the numbers really trustworthy?**
A: The gate guarantees "every number comes from the data or a recomputable formula" — not
semantic correctness; fairness of conclusions relies on coarse heuristics (see limitations).

**Q: Why no built-in NL2SQL?**
A: A design decision (AD-013/024): execute caller-provided single read-only SELECTs only —
"execute, don't generate" buys auditability.

**Q: Do I need an LLM key?**
A: No — import, profile, aggregate, export and provenance replay all work deterministically;
only the report chain needs your endpoint.

**Q: Does my data leave my machine?**
A: Not by default; the only egress is endpoints you configure. With the PII shield on, outbound
text is masked first (placeholders live only for the request).

**Q: Claude Desktop / Cline compatible?**
A: Yes — a standard MCP server (stdio or Streamable HTTP). Config example under "Route B".

## Release and versioning

- Version single source = `version` in root `build.gradle.kts`; MCP `serverInfo.version` reads it.
- Public API guarded by BCV (`apiCheck`); "public-surface growth is a deliberate act".
- SemVer; breaking changes ride the 12-month MCP deprecation window (current protocol compatible until 2027-07).

## Documentation map

| Doc | Contents |
|---|---|
| [docs/D1-overview.md](docs/D1-overview.md) | positioning, scope, roles, glossary |
| [docs/D2-architecture.md](docs/D2-architecture.md) | module layering, six-step report pipeline, execution guarantees, honest limits |
| [docs/D3-decisions.md](docs/D3-decisions.md) | why the system looks like this (ADR digests with accepted costs) |
| [docs/mcp-tool-surface.md](docs/mcp-tool-surface.md) | runtime tool-surface snapshot (44 default) |
| [docs/QUICKSTART.md](docs/QUICKSTART.md) | runnable CSV-to-verified-report walkthrough |
| [CONTRIBUTING.md](CONTRIBUTING.md) | engineering gates and "how to change one tool" |
| [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | community code of conduct (Contributor Covenant 2.1) |
| [SECURITY.md](SECURITY.md) | trust model and disclosure |
| [FIXTURES.md](FIXTURES.md) | test dataset sources and licensing |
| [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) | PR gate checklist + positioning self-check |

## Questions and feedback

Start with [QUICKSTART](docs/QUICKSTART.md) and the doc table; file issues via templates
(**Bug report** / **Feature proposal** — the proposal template embeds a positioning self-check;
proposals touching declared non-goals get closed with a pointer to the decision log).

## Contributing

PRs welcome — read [CONTRIBUTING.md](CONTRIBUTING.md) first. This repo deliberately carries an
unusual set of engineering gates (mutation testing, doc consistency, public-API snapshot checks);
CONTRIBUTING explains why each exists. Use the
[PR template](.github/PULL_REQUEST_TEMPLATE.md); conduct per
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) (Contributor Covenant 2.1).

## License

[Apache License 2.0](LICENSE).

## References and acknowledgements

- [MCP (Model Context Protocol)](https://modelcontextprotocol.io) — tool-surface protocol base; the in-house bridge aligns field-by-field with the official 2026-07-28 spec.
- [DuckDB](https://duckdb.org) — tier-2 columnar engine (JDBC).
- [Apache PDFBox](https://pdfbox.apache.org) / Batik — PDF rendering and export upstream.
- [Spider 2.0-Lite](https://spider2-sql.github.io) and DAB — external evaluation benches (explicit SKIP without data).
- Test dataset sources and licensing: [FIXTURES.md](FIXTURES.md).
