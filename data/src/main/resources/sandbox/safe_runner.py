"""
str-agent 安全 Python runner —— D11 v3 S2 / T-054 沙箱加固。

协议（与旧版兼容）：
  stdin : JSON {code, columns, columnTypes, rows, deps?}
  stdout: JSON {"result": ...}（用户 print 一律改道 stderr，协议 stdout 只留结果）
  stderr: 错误/告警（宿主侧有 64KB 上限，超出即截断）
  退出码: 0=成功, 3=无 result, 4=序列化失败

加固（**纵深防御，不是系统级沙箱**）：
  ① 受限 builtins：open/exec/eval/compile/input 等一律替换为「调用即拒绝」；
  ② 导入白名单（标准库模式）：只允许纯计算/文本/序列化模块，os/sys/subprocess/
     socket/ctypes/importlib 等一概拒绝；
  ③ venv 模式（pandas/numpy/matplotlib 已由宿主显式提供）：库导入完成后再装
     拒绝名单，并封掉 os.system/popen/exec*/spawn*/fork 与 socket 入口；
  ④ 文件接口在标准库模式下也一并封掉 io.open/os.open（venv 模式放开——宿主
     既然给了 pandas，文件型工作流就是被授权的，如实记录在文档里）。

诚实边界：
  - Python 层的封禁挡不住 CPython 自身的逃逸技巧（例如经已加载模块的属性拿到
    os 引用）；真正的硬边界仍是「宿主授权 + 子进程隔离 + 一次性目录 +
    超时杀进程组 + 重放校验」。本文件只负责把「随手就能越界」变成「必须刻意越界」。
  - pandas/numpy/matplotlib 需预装在 venv 中；未安装时退化为标准库子集。
"""
import builtins
import json
import os
import sys

# 标准库模式允许的导入根模块：纯计算/文本/序列化，不含文件、进程、网络、系统接口
ALLOWED_STDLIB = {
    "math", "cmath", "statistics", "decimal", "fractions", "random",
    "json", "re", "datetime", "collections", "itertools", "functools",
    "operator", "numbers", "copy", "enum", "dataclasses", "typing", "abc",
    "contextlib", "warnings", "textwrap", "string", "unicodedata",
    "heapq", "bisect", "array", "csv", "hashlib", "base64", "struct", "time",
}

# venv 模式的拒绝名单：进程/网络/系统接口（库已导入后才安装）
DENIED_WITH_DEPS = {
    "os", "sys", "builtins", "subprocess", "socket", "_socket", "ssl", "_ssl",
    "ctypes", "_ctypes", "multiprocessing", "_multiprocessing", "_posixsubprocess",
    "_winapi", "winreg", "msvcrt", "pty", "resource", "signal", "_signal",
    "fcntl", "mmap", "webbrowser", "http", "urllib", "ftplib", "smtplib",
    "telnetlib", "xmlrpc", "requests", "asyncio", "_asyncio", "select",
    "selectors", "shutil", "tempfile", "pickle", "_pickle", "marshal",
    "importlib", "runpy", "code", "codeop", "sqlite3", "_sqlite3", "dbm",
    "shelve", "glob", "pathlib", "logging",
}

# 进程/SQL 之外的逃逸入口：统一封掉（标准库模式连文件接口一起封）
PROCESS_CALLS = (
    "system", "popen", "fork", "forkpty", "execv", "execve", "execvp", "execvpe",
    "execl", "execle", "execlp", "execlpe", "spawnv", "spawnve", "spawnvp",
    "spawnvpe", "spawnl", "spawnle", "spawnlp", "spawnlpe", "startfile",
    "kill", "killpg",
)


class SandboxViolation(Exception):
    """用户代码触发了沙箱禁止的操作"""


def _deny(*args, **kwargs):
    raise SandboxViolation("沙箱禁止该操作（文件/进程/网络/系统接口均不可用）")


def _deny_builtin(name):
    def denied(*args, **kwargs):
        raise SandboxViolation("沙箱禁止使用 %s()（该内建已禁用）" % name)

    return denied


def _install_runtime_guard(denied_only):
    """封掉进程与网络入口；标准库模式连文件接口一起封"""
    for name in PROCESS_CALLS:
        if hasattr(os, name):
            setattr(os, name, _deny)
    try:
        import socket as _socket

        _socket.socket = _deny
        _socket.create_connection = _deny
    except Exception:
        pass
    if not denied_only:
        import io as _io

        _io.open = _deny
        os.open = _deny
        os.fdopen = _deny


def _restricted_builtins(denied_only):
    """受限 builtins：常用安全内建保留，危险内建替换为拒绝桩，__import__ 走白名单"""
    guard = _make_guarded_import(denied_only)
    safe = {name: getattr(builtins, name) for name in dir(builtins) if not name.startswith("_")}
    for name in ("open", "exec", "eval", "compile", "input", "breakpoint", "help", "exit", "quit"):
        safe[name] = _deny_builtin(name)
    safe["__build_class__"] = builtins.__build_class__
    safe["__import__"] = guard
    safe["__name__"] = "builtins"
    return safe


def _make_guarded_import(denied_only):
    real_import = builtins.__import__

    def guarded(name, globals=None, locals=None, fromlist=(), level=0):
        if level != 0:
            raise SandboxViolation("沙箱不允许相对导入")
        root = name.split(".")[0]
        if denied_only:
            if root in DENIED_WITH_DEPS:
                raise SandboxViolation("沙箱禁止导入 %s（进程/网络/系统接口）" % root)
        elif root not in ALLOWED_STDLIB:
            raise SandboxViolation(
                "沙箱不允许导入 %s；可用的标准库模块：%s"
                % (root, "、".join(sorted(ALLOWED_STDLIB)))
            )
        return real_import(name, globals, locals, fromlist, level)

    return guarded


def _preload_allowed():
    """标准库模式先把白名单模块全部加载：装好文件封禁后不再触发懒加载"""
    import importlib

    for root in sorted(ALLOWED_STDLIB):
        try:
            importlib.import_module(root)
        except Exception:
            pass


def _import_deps(deps, ns):
    """按 deps 预导入数据科学库（venv 模式），并构建 df"""
    if "pandas" in deps or "pd" in deps:
        try:
            import pandas as _pd

            ns["pd"] = _pd
            df = _pd.DataFrame(ns["rows"], columns=ns["columns"])
            for i, ct in enumerate(ns["columnTypes"]):
                if ct == "NUMBER" and i < len(ns["columns"]):
                    col = ns["columns"][i]
                    df[col] = _pd.to_numeric(df[col], errors="coerce")
            ns["df"] = df
        except ImportError:
            sys.stderr.write("警告：pandas 未安装，df 变量不可用。请运行 scripts/create-venv.ps1 创建 venv。\n")

    if "numpy" in deps or "np" in deps:
        try:
            import numpy as _np

            ns["np"] = _np
        except ImportError:
            sys.stderr.write("警告：numpy 未安装，np 变量不可用。\n")

    if "matplotlib" in deps or "plt" in deps:
        try:
            import matplotlib as _mpl

            _mpl.use("Agg")  # 非交互后端
            import matplotlib.pyplot as _plt

            ns["plt"] = _plt
            ns["matplotlib"] = _mpl
        except ImportError:
            sys.stderr.write("警告：matplotlib 未安装，plt 变量不可用。\n")


def _install_memory_limit(max_mb):
    """T-150：子进程内存硬限（进程启动即装，先于任何用户代码）。

    POSIX：resource.setrlimit(RLIMIT_AS)——超限分配直接 MemoryError；
    Windows：stdlib ctypes 装进程 Job Object（JobMemoryLimit），超限分配失败 → MemoryError。
    装限失败不阻断执行（老系统/嵌套作业权限），只写一行 stderr 说明——
    这不是安全边界的一部分，纵深防御仍靠导入白名单/禁网络/受限 builtins。
    """
    limit = int(max_mb) * 1024 * 1024
    try:
        if sys.platform == "win32":
            import ctypes
            from ctypes import wintypes
            k32 = ctypes.windll.kernel32

            class JOBOBJECT_BASIC_LIMIT_INFORMATION(ctypes.Structure):
                _fields_ = [
                    ("PerProcessUserTimeLimit", ctypes.c_int64),
                    ("PerJobUserTimeLimit", ctypes.c_int64),
                    ("LimitFlags", wintypes.DWORD),
                    ("MinimumWorkingSetSize", ctypes.c_size_t),
                    ("MaximumWorkingSetSize", ctypes.c_size_t),
                    ("ActiveProcessLimit", wintypes.DWORD),
                    ("AffinityMask", ctypes.c_size_t),
                    ("PriorityClass", wintypes.DWORD),
                    ("SchedulingClass", wintypes.DWORD),
                ]

            class IO_COUNTERS(ctypes.Structure):
                _fields_ = [("ReadOperationCount", ctypes.c_uint64), ("WriteOperationCount", ctypes.c_uint64),
                            ("OtherOperationCount", ctypes.c_uint64), ("ReadTransferCount", ctypes.c_uint64),
                            ("WriteTransferCount", ctypes.c_uint64), ("OtherTransferCount", ctypes.c_uint64)]

            class JOBOBJECT_EXTENDED_LIMIT_INFORMATION(ctypes.Structure):
                _fields_ = [
                    ("BasicLimitInformation", JOBOBJECT_BASIC_LIMIT_INFORMATION),
                    ("IoInfo", IO_COUNTERS),
                    ("ProcessMemoryLimit", ctypes.c_size_t),
    ("JobMemoryLimit", ctypes.c_size_t),
    ("PeakProcessMemoryUsed", ctypes.c_size_t),
    ("PeakJobMemoryUsed", ctypes.c_size_t),
                ]
            JOB_OBJECT_LIMIT_JOB_MEMORY = 0x200
            info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION()
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_JOB_MEMORY
            info.JobMemoryLimit = limit
            info.ProcessMemoryLimit = limit
            k32.CreateJobObjectW.restype = wintypes.HANDLE
            k32.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
            k32.SetInformationJobObject.restype = wintypes.BOOL
            k32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
            k32.AssignProcessToJobObject.restype = wintypes.BOOL
            k32.GetCurrentProcess.restype = wintypes.HANDLE
            job = k32.CreateJobObjectW(None, None)
            ok = k32.SetInformationJobObject(job, 9, ctypes.byref(info), ctypes.sizeof(info))
            if ok:
                assigned = k32.AssignProcessToJobObject(job, k32.GetCurrentProcess())
                if not assigned:
                    sys.stderr.write("警告：进程加入 Job 失败（内存硬限未生效）\n")
            else:
                sys.stderr.write("警告：Windows Job Object 设置失败（内存硬限未生效）\n")
        else:
            import resource
            # POSIX 的 RLIMIT_AS 计的是进程虚拟地址空间——CPython 自身启动就要映射
            # 远大于 Windows Job Object 语义的 commit 内存（16MB 连解释器都起不来，
            # 装 设失败被 except 吞掉 = 无限运行，ubuntu CI 实录）。floor 保证解释器
            # 能启动，同时用户代码的超额分配（200MB 级）仍被 MemoryError 拒绝。
            floor = 96 * 1024 * 1024
            cap = max(limit, floor)
            soft, hard = resource.getrlimit(resource.RLIMIT_AS)
            if hard not in (resource.RLIM_INFINITY, -1) and hard < cap:
                cap = hard
            resource.setrlimit(resource.RLIMIT_AS, (cap, cap))
    except Exception as e:
        sys.stderr.write("警告：内存硬限未生效（%s）\n" % e)

def main():
    req = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    _install_memory_limit(req.get("maxMemMb") or 512)
    ns = {
        "__name__": "__stragent__",
        "columns": req["columns"],
        "columnTypes": req["columnTypes"],
        "rows": req["rows"],
    }

    deps = req.get("deps", []) or []
    denied_only = bool(deps)
    if denied_only:
        _import_deps(deps, ns)
    else:
        _preload_allowed()

    _install_runtime_guard(denied_only)
    ns["__builtins__"] = _restricted_builtins(denied_only)

    # 用户 print 改道 stderr：协议 stdout 只留最终 JSON（stderr 有宿主侧上限）
    real_stdout = sys.stdout
    sys.stdout = sys.stderr
    try:
        exec(compile(req["code"], "<stragent-compute>", "exec"), ns)
    except SandboxViolation as e:
        # 沙箱拒绝：只回一行中文原因，不回整段 traceback（宿主只展示前 600 字）
        sys.stderr.write("沙箱拒绝：%s" % e)
        sys.exit(6)
    finally:
        sys.stdout = real_stdout

    if "result" not in ns:
        sys.stderr.write("代码执行完毕但没有产出 result：请把最终结果赋值给变量 result")
        sys.exit(3)

    try:
        out = json.dumps({"result": ns["result"]}, ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError) as e:
        sys.stderr.write("result 无法序列化为 JSON（只允许 JSON 可表达的值，NaN/Infinity 不允许）：%s" % e)
        sys.exit(4)
    real_stdout.write(out)


if __name__ == "__main__":
    main()
