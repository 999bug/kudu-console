# Python 规范

**加载时机**：检测到 `pyproject.toml` / `requirements.txt` / `setup.py`，或要新增、修改 Python 代码。

## 1. 解释器与依赖管理

- **【红线】不 `pip install` 到全局环境**，也不在系统 Python 里装项目依赖。用项目既有的虚拟环境方案（`.venv` / `uv` / `poetry`）。
- **【默认】依赖管理方式以项目文件为准**，不混搭：有 `uv.lock` 用 uv，有 `poetry.lock` 用 poetry，只有 `requirements.txt` 就用 pip + venv。不要引入第二套。
- **【默认】Python 版本写在 `pyproject.toml` 的 `requires-python`**，代码里不靠 `sys.version_info` 做分支。
- **【红线】依赖必须锁版本**（lock 文件入库）。`pip freeze` 的松散 `>=` 不算锁。
- **【默认】新增依赖先说明理由与体积/许可影响**，能用标准库就不引第三方。

## 2. 类型与结构

- **【默认】公开函数、方法必须有类型注解**（参数 + 返回值），内部小函数可省。
- **【默认】用新式注解**：`from __future__ import annotations`，可空写 `str | None`（3.10+）或 `Optional[str]`（低版本），不要两种混用。
- **【红线】禁止可变默认参数**：`def f(items=[])` 会在调用间共享同一个列表。
- **【默认】数据容器按层次选**：内部传值用 `@dataclass`（或 `NamedTuple`），外部边界、配置、请求体用 `pydantic` 校验，不用裸 `dict` 传来传去。
- **【建议】路径用 `pathlib.Path`**，不手拼字符串；读写文件显式 `encoding="utf-8"`（Windows 默认不是 UTF-8）。

## 3. 异常与上下文

- **【红线】禁止裸 `except:`**，也不要 `except Exception: pass`。至少记录并保留原始异常。
- **【默认】抛业务异常时用 `raise ... from e`** 保留异常链，否则排障时看不到根因。
- **【默认】资源用 `with` / `contextlib`**，不手写 `try/finally` 关文件、连接。
- **【默认】不用异常做流程控制**（用 `if x in d` 而不是 `try: d[x] except KeyError`）。
- **【建议】自定义异常继承项目基类**，方便上层统一映射为 HTTP 状态码或错误码。

## 4. 并发与异步

- **【红线】`async def` 里禁止阻塞调用**：`time.sleep`、`requests`、同步数据库驱动都会卡住事件循环。用 `asyncio.sleep`、`httpx.AsyncClient`、异步驱动。
- **【默认】`asyncio.gather` 要决定失败语义**：要么 `return_exceptions=True` 后逐个检查，要么让异常冒泡并取消其余任务。默认行为是"第一个异常抛出，其余任务继续跑"，多数人没意识到。
- **【默认】CPU 密集用 `ProcessPoolExecutor`，IO 密集用线程池或 asyncio**。GIL 决定了多线程不会加速计算。
- **【建议】并发数要设上限**（`asyncio.Semaphore` / `max_workers`），不要 `gather` 一万个任务打爆下游。

## 5. 日志与输出

- **【默认】应用代码用 `logging`**，不用 `print`。脚本类工具可以用 `print` 输出结果。
- **【默认】用惰性格式化**：`log.info("rows=%s", n)`，不要 `log.info(f"rows={n}")`（即使不输出也会先拼字符串）。
- **【红线】日志不打凭据、token、完整连接串、个人数据**。
- **【默认】`logger.exception()` 只在 except 块里用**，它自动带堆栈。

## 6. 风格与工具

- **【红线】不引入新的格式化/检查工具链**，除非项目明确没有。有 `ruff` / `black` / `flake8` 配置就遵循配置，不按个人偏好格式化。
- **【默认】字符串用 f-string**，日志除外（见上）。
- **【默认】不用 `from module import *`**。
- **【建议】函数超过 40 行、嵌套超过 3 层就拆分**，Python 的缩进代价比别的语言高。

## 7. 测试

- **【默认】用 `pytest`**，文件名 `test_*.py`，函数名 `test_*`。
- **【默认】临时文件、临时目录用 `tmp_path` fixture**，不要写到仓库目录里。
- **【默认】不 mock 被测对象本身**（那等于测 mock）；mock 边界：网络、时钟、文件系统。
- **【红线】不让测试依赖执行顺序或共享可变状态**。
- **【建议】参数化用 `@pytest.mark.parametrize`**，比复制粘贴多个用例好维护。

## 8. 反例清单（见到就改）

```python
# ✗ 可变默认参数
def add(item, bucket=[]): ...

# ✗ 裸 except 吞掉一切（包括 Ctrl+C）
try:
    run()
except:
    pass

# ✗ async 里用同步 HTTP 客户端
async def fetch(url):
    return requests.get(url).json()

# ✓
async def fetch(url):
    async with httpx.AsyncClient() as c:
        return (await c.get(url)).json()

# ✗ 拼路径字符串
open(base + "/data/" + name)

# ✓
(base / "data" / name).read_text(encoding="utf-8")
```
