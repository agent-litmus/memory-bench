"""AgentLitmus pip 薄壳：让 Python 用户免装 Java 使用 AgentLitmus CLI。

包本身不含评测逻辑——判定器保持单一实现（Java），本包只负责：
首次运行时下载对应平台的自包含可执行（内置运行时），之后原样转发命令。
"""

__version__ = "1.0.0"
