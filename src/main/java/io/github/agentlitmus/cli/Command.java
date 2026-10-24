package io.github.agentlitmus.cli;

/**
 * CLI 子命令。
 * <p>
 * 入口 {@code io.github.agentlitmus.Cli} 只做分发：命令名 → 本接口实现。
 * 新增子命令 = 实现本接口 + 在 {@code Cli.commands()} 注册一行 + 帮助文本加一行，
 * 不再需要往单个千行大类里继续堆方法。
 */
public interface Command {

    /** 命令名（argv 第一个 token） */
    String name();

    /** 执行；参数已由 {@link CliOptions#parse} 解析完毕 */
    void run(CliOptions options) throws Exception;
}
