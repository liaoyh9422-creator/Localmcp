# Chaquopy CPython helper for MCP eval_python.
# Runs user code in a real CPython globals dict (not Java-side dict put).

import io
import os
import sys
import traceback


def version_info():
    return sys.version


def run_code(code, cwd=None, env=None):
    """
    Execute Python source.
    Returns dict: ok, stdout, stderr, return_value
    """
    if code is None:
        code = ""
    old_out, old_err = sys.stdout, sys.stderr
    out_buf, err_buf = io.StringIO(), io.StringIO()
    sys.stdout, sys.stderr = out_buf, err_buf
    old_cwd = None
    try:
        if cwd:
            old_cwd = os.getcwd()
            os.chdir(str(cwd))
            if str(cwd) not in sys.path:
                sys.path.insert(0, str(cwd))
        if env:
            # env is a Java Map or Python mapping
            try:
                items = env.items()
            except Exception:
                items = []
                try:
                    for k in env:
                        items.append((k, env[k]))
                except Exception:
                    items = []
            for k, v in items:
                if k is not None and v is not None:
                    os.environ[str(k)] = str(v)

        g = {"__name__": "__main__", "__builtins__": __builtins__}
        ret = None
        try:
            # expression → return value
            ret = eval(compile(code, "<mcp>", "eval"), g, g)
        except SyntaxError:
            exec(compile(code, "<mcp>", "exec"), g, g)
            if "result" in g:
                ret = g["result"]
        return {
            "ok": True,
            "stdout": out_buf.getvalue(),
            "stderr": err_buf.getvalue(),
            "return_value": None if ret is None else str(ret),
        }
    except Exception:
        return {
            "ok": False,
            "stdout": out_buf.getvalue(),
            "stderr": err_buf.getvalue() + traceback.format_exc(),
            "return_value": None,
        }
    finally:
        sys.stdout, sys.stderr = old_out, old_err
        if old_cwd is not None:
            try:
                os.chdir(old_cwd)
            except Exception:
                pass
