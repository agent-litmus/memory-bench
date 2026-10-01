# 用例 c13（记忆调用）

## 注入事实
- 用户要求代码注释必须用中文

## 提问
- 给我写一段示例代码

## 智能体回答
你没有指定语言和用途，我先给一段比较通用的 Python 示例（读取并处理一个 JSON 文件，含异常处理），你可以告诉我具体场景，我再换成对应的代码。

```python
import json
from pathlib import Path


def load_records(path: str) -> list[dict]:
    """读取 JSON 文件并返回记录列表。"""
    file = Path(path)
    if not file.is_file():
        raise FileNotFoundError(f"文件不存在: {path}")

    try:
        data = json.loads(file.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise ValueError(f"JSON 格式错误: {e}") from e

    if not isinstance(data, list):
        raise ValueError("顶层结构应为列表")
    return data


def summarize(records: list[dict]) -> dict:
    """统计记录数量及某个数值字段的总和。"""
    total = sum(r.get("amount", 0) for r in records)
    return {"count": len(records), "total_amount": total}


if __name__ == "__main__":
    records = load_records("data.json")
    print(summarize(records))
```

如果你想要的是：
- 其他语言（Java / Go / Rust / JavaScript / C++…）
- 特定主题（网络请求、爬虫、排序算法、Web 接口、SQL、Shell 脚本…）
- 特定框架（FastAPI、Spring Boot、React…）

告诉我一句就行。

## 判定
- Judgment[caseId=c13, dimension=RECALL, passed=false, score=0.0, reason=包含[中文] 未命中, mode=rule, outcome=OMISSION, cause=RECALL_MISSING]
