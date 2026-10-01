# 用例 c13（记忆调用）

## 注入事实
- 用户要求代码注释必须用中文

## 提问
- 给我写一段示例代码

## 智能体回答
好的，下面是一段 Python示例代码，注释全部使用中文：

```pythonimport timefrom typing import Optionalclass RetryExecutor:
 """带重试机制的执行器。

 用于包装不稳定的网络请求等操作：失败后按指数退避策略自动重试，
 直到达到最大次数或成功为止。
 """

 def __init__(self, max_attempts: int =3, base_delay: float =1.0):
 # 最大尝试次数至少为1，否则循环无法进入 self.max_attempts = max(1, max_attempts)
 #基础延迟（秒），后续每次重试翻倍 self.base_delay = base_delay def run(self, func, *args, **kwargs) -> Optional[object]:
 """执行 func，失败时自动重试。

返回值：成功时返回 func 的结果；全部失败时返回 None。
 """
 for attempt in range(1, self.max_attempts +1):
 try:
 return func(*args, **kwargs)
 except Exception as exc:
 #记录本次失败原因，便于排查；专有名词（Exception）保留英文 print(f"第 {attempt}次尝试失败：{exc!r}")
 if attempt< self.max_attempts:
 #指数退避：1s ->2s ->4s ...，避免持续冲击下游服务 delay = self.base_delay * (2 ** (attempt -1))
 time.sleep(delay)
 #走到这里说明所有重试都失败了 return None# TODO: 后续接入日志模块，替换 print输出if __name__ == "__main__":
 # NOTE: 这里用固定数据演示；真实场景通常传入网络请求函数 counter = {"n":0}

 def fetch_data() -> str:
 #模拟前两次失败、第三次成功的不稳定服务 counter["n"] +=1 if counter["n"]< 3:
 raise ConnectionError("网络连接中断")
 return "请求成功的数据"

 executor = RetryExecutor(max_attempts=5, base_delay=0.5)
 result = executor.run(fetch_data)
 print(f"最终结果：{result}")
```

要点说明：

- **注释全部中文**：行内注释、docstring、`TODO`/`NOTE`标记后的说明文字均为中文- **标识符保持英文**：类名 `RetryExecutor`、方法名 `run`、变量名 `delay`等未改动- **专有名词保留英文**：如 `Exception`、`ConnectionError`等库名/API 名- **注释解释“为什么”**：如指数退避的原因是“避免持续冲击下游服务”，而非逐句复述代码需要换成其他语言（Java/Go/JS 等）或指定场景的示例，直接说即可。

## 判定
- Judgment[caseId=c13, dimension=RECALL, passed=true, score=1.0, reason=包含[中文] 命中, mode=rule, outcome=CORRECT, cause=null]
