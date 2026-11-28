package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.compute.ComputeKind
import com.stragent.core.compute.ComputeManifest
import com.stragent.core.compute.ComputeOutcome
import com.stragent.core.compute.ComputeProvider
import com.stragent.core.compute.ExpressionComputeProvider
import com.stragent.core.compute.ExpressionEvaluator
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.PrefsStore
import com.stragent.core.trace.ProvenanceManifest
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpSessionContext
import com.stragent.data.SessionRegistry
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 子进程 Python 计算提供方（阶段 2 后半段「真 Python 子集」第一落地，T-031）。
 *
 * 形态：`python -I -X utf8 <临时目录>/runner.py` 子进程（隔离模式：忽略 PYTHON* 环境变量、
 * 不加载用户 site-packages；runner 写临时文件而不是 `-c` 内联——Windows 进程参数拼装会吃掉
 * 参数里嵌入的双引号），stdin 传数据 JSON（columns/columnTypes/rows，行值保持
 * tier-1 的原始字符串），stdout 回**单行** JSON `{"result": ...}`。
 *
 * 诚实边界（对齐 T-026 的标注纪律；T-054 起加固）：
 *  - 这是**子进程隔离**，不是 seccomp/namespace 级的系统沙箱。T-054 在 Python 层加了
 *    四道纵深防御：**导入白名单**（标准库模式只允许纯计算模块）、**禁网络**（socket/ssl
 *    拒绝）、**受限 builtins**（open/exec/eval/compile 调用即拒绝）、**封进程入口**
 *    （os.system / popen / exec 系列 / spawn 系列 / fork）。**这些挡不住刻意逃逸**——真正的硬边界仍是：
 *    ①**人类授权闸**（与受控表达式同一把：compute.sandbox.enabled / STRAGENT_SANDBOX=1）；
 *    ②**数据不落盘、子进程工作目录是一次性临时目录、PYTHON* 环境变量一概不给**；
 *    ③**超时杀整个进程树**（ProcessHandle.descendants，不只杀根进程）；
 *    ④**确定性重放**——非确定性代码（random/time）会被溯源重放当场抓出（哈希不一致即判不可复核）。
 *  - **开启即等于允许执行任意代码（RCE）**：只应在信任调用方时开启；宿主启动时会打印告警。
 *  - `deps` 恒为空：不装第三方包（无 pandas），「真 Python 子集」= 标准库子集。
 *  - stderr 有 64KB 上限（超限截断），stdout 32MB 上限；用户 print 被 runner 改道 stderr，
 *    不会污染协议 stdout。
 *
 * 与受控表达式同一套清单协议（[ComputeManifest]）：code/deps/inputHash/outputHash/seed/env，
 * 结果哈希复用 [ExpressionComputeProvider.hashOfValue]——两档引擎的重放口径一致。
 */
class SubprocessPythonProvider(
    /** 显式指定解释器（测试注入用）；缺省按 python → py -3 顺序探测 */
    private val pythonExe: String? = null,
    /** 单次执行超时（毫秒），超时强杀 */
    private val timeoutMs: Long = 10_000,
    /** stdout 上限（字节），超限判失败——防止巨型结果把宿主堆打爆 */
    private val maxOutputBytes: Int = 32 * 1024 * 1024,
    /** venv path (D11 v3 S2) */
    private val venvPath: String? = null,
    /** T-150：子进程内存硬限（MB）。0/负数 = 不设限（保留既有形态，默认 512 显式设限） */
    private val maxMemMb: Int = 512,
) : ComputeProvider {

    override val id: String = ID
    override val kind: ComputeKind = ComputeKind.SANDBOX
    override val sandboxLevel: com.stragent.core.compute.SandboxLevel
        get() = if (venvResolved) com.stragent.core.compute.SandboxLevel.WHITELIST else com.stragent.core.compute.SandboxLevel.STANDARD

    @Volatile private var probed: Pair<List<String>, String>? = null

    @Volatile private var venvResolved: Boolean = false

    /** 探测解释器并缓存（候选顺序：显式指定 → python → py -3），返回 (命令前缀, 版本号) 或 null */
    private fun probe(): Pair<List<String>, String>? {
        probed?.let { return it }
        val venvPython = venvPath?.let { vp ->
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val exe = java.io.File(vp, if (isWin) "Scripts/python.exe" else "bin/python")
            if (exe.exists()) listOf(exe.absolutePath) else null
        }
        venvResolved = venvPython != null
        val candidates = pythonExe?.let { listOf(listOf(it)) } ?: CANDIDATES
        for (prefix in candidates) {
            try {
                val proc = ProcessBuilder(prefix + listOf("-I", "-X", "utf8", "-c", VERSION_PROBE))
                    .redirectErrorStream(false)
                    .start()
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                val out = if (finished) proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim() else ""
                if (!finished) proc.destroyForcibly()
                if (finished && proc.exitValue() == 0 && out.isNotEmpty() && out.all { it.isDigit() || it == '.' }) {
                    val found = prefix to out
                    probed = found
                    return found
                }
            } catch (_: Exception) {
                // 候选不可用（不存在/启动失败/超时）→ 试下一个
            }
        }
        return null
    }

    override fun isAvailable(): Boolean = probe() != null

    override fun execute(code: String, dataset: Dataset): ComputeOutcome {
        val (cmdPrefix, version) = probe()
            ?: throw ExpressionEvaluator.ComputeException(
                "Python 解释器不可用：未找到可用的 python（试过 ${
                    pythonExe?.let { "指定路径 $it" } ?: CANDIDATES.joinToString("、") { it.joinToString(" ") }
                }）。请安装 Python 3 或由宿主显式配置解释器路径",
            )
        if (code.isBlank()) throw ExpressionEvaluator.ComputeException("code 不能为空")

        val deps = if (venvResolved) listOf("pandas", "numpy") else emptyList()
        val payload = buildJsonObject {
            put("code", code)
            put("columns", stringsToJsonArray(dataset.columns))
            put("columnTypes", stringsToJsonArray(dataset.columnTypes.map { it.name }))
            put("rows", JsonArray(dataset.rows.map { row -> stringsToJsonArray(row) }))
            if (deps.isNotEmpty()) put("deps", JsonArray(deps.map { JsonPrimitive(it) }))
            put("maxMemMb", maxMemMb)
        }

        val dir = Files.createTempDirectory("stragent-py")
        try {
            // runner 走临时文件而不是 -c 参数：Windows 的进程参数拼装会吃掉
            // 参数里嵌入的双引号（req["columns"] → req[columns] 的前车之鉴），
            // 脚本路径作为参数则没有引号问题
            val runnerText = Thread.currentThread().contextClassLoader
                ?.getResourceAsStream("sandbox/safe_runner.py")
                ?.bufferedReader()?.readText()
                ?: RUNNER
            val runnerFile = dir.resolve("runner.py")
            Files.write(runnerFile, runnerText.toByteArray(Charsets.UTF_8))
            val builder = ProcessBuilder(cmdPrefix + listOf("-I", "-X", "utf8", runnerFile.toAbsolutePath().toString()))
                .directory(dir.toFile())
                .redirectErrorStream(false)
            // PYTHON* 一律不给（-I 本就忽略，双保险）；必须在 start() 前改环境
            with(builder.environment()) { keys.toList().filter { it.startsWith("PYTHON") }.forEach { remove(it) } }
            val proc = builder.start()

            val stdout = OutputCapture(maxOutputBytes)
            // T-054：stderr 有上限——用户代码可以无限 print，收集端不能在宿主堆里无界增长
            val stderr = OutputCapture(STDERR_CAP_BYTES)
            val outThread = Thread { stdout.drain(proc.inputStream) }.apply { isDaemon = true; start() }
            val errThread = Thread { stderr.drain(proc.errorStream) }.apply { isDaemon = true; start() }

            proc.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                // 只杀根进程会留下用户代码再起的子进程；先收集后代再整棵树强杀
                killProcessTree(proc)
                throw ExpressionEvaluator.ComputeException(
                    "Python 执行超时（>${timeoutMs}ms）已强制终止（含子进程）；请缩减计算量或由宿主调大超时",
                )
            }
            outThread.join(2_000)
            errThread.join(2_000)

            if (proc.exitValue() != 0) {
                val errText = stderr.text().take(600).ifBlank { "(无 stderr)" }
                val truncatedNote = if (stderr.overflow) "（stderr 超过 ${STDERR_CAP_BYTES / 1024}KB 已截断）" else ""
                throw ExpressionEvaluator.ComputeException(
                    "Python 执行失败（退出码 ${proc.exitValue()}）：$errText$truncatedNote",
                )
            }
            if (stdout.overflow) {
                throw ExpressionEvaluator.ComputeException(
                    "Python 结果过大（超过 ${maxOutputBytes / 1024 / 1024}MB 上限）：请让 result 保持为聚合结果，不要回传整份原始数据",
                )
            }
            val result = (Json.parseToJsonElement(stdout.text()).jsonObject)["result"]
                ?: throw ExpressionEvaluator.ComputeException(
                    "Python 代码没有产出 result：请把最终结果赋值给变量 result（只回传聚合结果，不回传整份数据）",
                )

            val manifest = ComputeManifest(
                code = code.trim(),
                deps = deps,
                inputHash = ProvenanceManifest.hashOfDataset(dataset),
                outputHash = ExpressionComputeProvider.hashOfValue(result),
                env = "cpython-$version-subprocess",
                engineId = ID,
                outputSchema = buildJsonObject { put("type", jsonTypeName(result)) },
            )
            return ComputeOutcome(value = result, manifest = manifest)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    companion object {
        const val ID = "python-sandbox"

        /** stderr 收集上限：用户代码可以无限 print，收集端必须有界 */
        const val STDERR_CAP_BYTES = 64 * 1024

        /**
         * 解释器候选（按序探测，取第一个可用的）。
         *
         * **必须含 `python3`**：Linux 发行版（Debian/Ubuntu/RHEL 系）装 Python 3 时
         * 通常只提供 `python3` 这个名字，`python` 常常**不存在**（PEP 394 起 `python`
         * 不再指向 Python 3）。只探 `python` 会让 `stragent_compute_python` 在 Linux 上
         * 误报「Python 解释器不可用」——实测 Linux 容器（Ubuntu 22.04）即如此。
         * `py -3` 是 Windows 的 Python Launcher 兜底。
         */
        private val CANDIDATES = listOf(
            listOf("python"),
            listOf("python3"),
            listOf("py", "-3"),
        )

        private val VERSION_PROBE = "import platform;print(platform.python_version())"

        /** 执行协议（资源缺失时的兜底）：stdin 数据进 → 受限 builtins + 导入白名单 → exec → result 出。
         *  与 data/src/main/resources/sandbox/safe_runner.py 同口径（无 pandas 分支）。 */
        private val RUNNER = """
            import builtins,json,os,sys
            ALLOWED={"math","cmath","statistics","decimal","fractions","random","json","re","datetime","collections","itertools","functools","operator","numbers","copy","enum","dataclasses","typing","abc","contextlib","warnings","textwrap","string","unicodedata","heapq","bisect","array","csv","hashlib","base64","struct","time"}
            class V(Exception): pass
            def deny(*a,**k): raise V("沙箱禁止该操作（文件/进程/网络/系统接口均不可用）")
            def dbn(n):
                def d(*a,**k): raise V("沙箱禁止使用 %s()（该内建已禁用）" % n)
                return d
            req=json.loads(sys.stdin.buffer.read().decode("utf-8"))
            import importlib
            for r in sorted(ALLOWED):
                try: importlib.import_module(r)
                except Exception: pass
            for n in ("system","popen","fork","execv","execve","execvp","spawnv","spawnl","startfile","kill"):
                if hasattr(os,n): setattr(os,n,deny)
            import socket,io
            socket.socket=deny; socket.create_connection=deny; io.open=deny; os.open=deny; os.fdopen=deny
            ri=builtins.__import__
            def guard(name,globals=None,locals=None,fromlist=(),level=0):
                if level!=0: raise V("沙箱不允许相对导入")
                root=name.split(".")[0]
                if root not in ALLOWED: raise V("沙箱不允许导入 %s" % root)
                return ri(name,globals,locals,fromlist,level)
            safe={n:getattr(builtins,n) for n in dir(builtins) if not n.startswith("_")}
            for n in ("open","exec","eval","compile","input","breakpoint","help","exit","quit"): safe[n]=dbn(n)
            safe["__build_class__"]=builtins.__build_class__; safe["__import__"]=guard
            ns={"__name__":"__stragent__","__builtins__":safe,"columns":req["columns"],"columnTypes":req["columnTypes"],"rows":req["rows"]}
            real=sys.stdout; sys.stdout=sys.stderr
            try: exec(compile(req["code"],"<stragent-compute>","exec"),ns)
            except V as e:
                sys.stderr.write("沙箱拒绝：%s" % e); sys.exit(6)
            finally: sys.stdout=real
            if "result" not in ns:
                sys.stderr.write("代码执行完毕但没有产出 result：请把最终结果赋值给变量 result"); sys.exit(3)
            try: out=json.dumps({"result":ns["result"]},ensure_ascii=False,allow_nan=False)
            except (TypeError,ValueError) as e:
                sys.stderr.write("result 无法序列化为 JSON（只允许 JSON 可表达的值，NaN/Infinity 不允许）：%s"%e); sys.exit(4)
            real.write(out)
        """.trimIndent()

        /** 强杀整棵进程树：先收集后代再一起杀（根先死会把子进程挂走，就找不到了） */
        private fun killProcessTree(proc: Process) {
            val handle = runCatching { proc.toHandle() }.getOrNull()
            if (handle == null) {
                runCatching { proc.destroyForcibly() }
                return
            }
            val descendants = runCatching {
                handle.descendants().collect(java.util.stream.Collectors.toList())
            }.getOrDefault(emptyList())
            descendants.forEach { child -> runCatching { child.destroyForcibly() } }
            runCatching { handle.destroyForcibly() }
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        }

        private fun jsonTypeName(v: JsonElement): String = when {
            v is JsonNull -> "null"
            v is JsonPrimitive && v.isString -> "string"
            v is JsonPrimitive && v.booleanOrNull != null -> "boolean"
            v is JsonPrimitive -> "number"
            v is JsonObject -> "object"
            else -> "array"
        }
    }
}

/** 有界流收集：超上限后继续排空（防子进程写阻塞）但标记 overflow。stderr 与 stdout 共用。 */
internal class OutputCapture(private val cap: Int) {
    private val buf = java.io.ByteArrayOutputStream()
    @Volatile var overflow = false
        private set

    fun drain(stream: InputStream) {
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = try { stream.read(chunk) } catch (_: Exception) { break }
            if (n < 0) break
            // 按剩余额度**部分写入**：单块超过余量时保留能留的部分，
            // 而不是整块丢弃（否则首块就超限时 cap 内一个字节都留不下，错误信息全空）
            val remaining = cap - total
            if (remaining > 0) buf.write(chunk, 0, minOf(n, remaining))
            if (n > remaining) overflow = true
            total += n
        }
    }

    fun text(): String = buf.toString("UTF-8")
}

private fun stringsToJsonArray(values: List<String>) = JsonArray(values.map { JsonPrimitive(it) })

/**
 * 真 Python 计算工具 —— `stragent_compute_python`（T-031）。
 *
 * 与 `stragent_compute`（受控表达式）并排的第二条 sandbox 入口：标准库子集、
 * 结果赋给 `result` 变量、行值以字符串到达（按 columnTypes 自行转换）。
 * **同一把授权闸**（compute.sandbox.enabled / STRAGENT_SANDBOX=1），同一套清单与重放纪律。
 */
fun pythonComputeTool(
    datasets: AbilityDatasets,
    prefs: PrefsStore,
    registry: SessionRegistry? = null,
    pythonExe: String? = null,
    timeoutMs: Long = 10_000,
    venvPath: String? = null,
    maxMemMb: Int = 512,
): AgentTool = object : AgentTool {
    private val provider = SubprocessPythonProvider(
        pythonExe = pythonExe, timeoutMs = timeoutMs, venvPath = venvPath, maxMemMb = maxMemMb,
    )

    override val name = "stragent_compute_python"
    override val description =
        "对当前数据集执行 **Python 标准库子集**（默认关闭，需宿主授权）：代码把最终结果赋给 `result` 变量，" +
            "可用变量 columns / columnTypes / rows（行值是字符串，如 float(r[1]) 自行转换），例如 " +
            "result = sum(float(r[0]) for r in rows)。" +
            "不装第三方包（无 pandas）；执行在隔离子进程（一次性临时目录、超时强杀进程树、内存硬限默认 512MB、stdout 32MB / stderr 64KB 上限）。" +
            "**安全边界**：开启即等于允许执行任意 Python 代码（RCE）——只应在信任调用方时开启；" +
            "沙箱仅有纵深防御（导入白名单、禁网络 socket/ssl、受限 builtins、封进程入口），不是系统级隔离。" +
            "每次执行产出计算清单并可重放复核——非确定性代码会被重放校验抓出。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "code",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Python 代码（标准库子集），把最终结果赋给 result 变量")
                    },
                )
                put(
                    "datasetId",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "数据集编号（缺省取会话当前数据集）")
                    },
                )
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("code"))))
    }

    override val cacheable = false

    // 执行任意用户代码：会读写文件/环境，不能标只读，也不是封闭世界
    override val readOnly = false
    override val destructive = true
    override val openWorld = true

    override fun invoke(args: JsonObject): JsonObject {
        if (!computeSandboxAuthorized(prefs)) {
            throw IllegalStateException(
                "受控计算默认关闭：请由宿主显式开启（设置 compute.sandbox.enabled=true，或环境变量 STRAGENT_SANDBOX=1）后重试",
            )
        }
        val code = args.string("code") ?: throw IllegalArgumentException("code 不能为空")
        val datasetId = resolveDatasetId(args, datasets, registry)
        val dataset = datasets.byId(datasetId)
            ?: throw IllegalArgumentException("数据集不存在：$datasetId（请先用 stragent_import_table 导入）")

        val outcome = provider.execute(code, dataset)
        return buildJsonObject {
            put("kind", ComputeKind.SANDBOX.id)
            put("engineId", outcome.manifest.engineId)
            put("value", outcome.value)
            // 确定性形态（不含时间戳）：重放哈希才可比对
            put("manifest", outcome.manifest.toStableJson())
        }
    }
}

/** 会话感知的数据集解析：显式 datasetId → 会话指针（具名严格隔离）→ 全局最近导入 */
internal fun resolveDatasetId(args: JsonObject, datasets: AbilityDatasets, registry: SessionRegistry?): String {
    args.string("datasetId")?.let { return it }
    return registry?.currentId(McpSessionContext.current())
        ?: datasets.currentId()
        ?: throw IllegalArgumentException("尚无数据集：请先调用 stragent_import_table")
}
