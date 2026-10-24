package io.github.agentlitmus;

import io.github.agentlitmus.cli.CliOptions;
import io.github.agentlitmus.cli.Command;
import io.github.agentlitmus.cli.CompareCommand;
import io.github.agentlitmus.cli.ExportCasesCommand;
import io.github.agentlitmus.cli.LeaderboardCommand;
import io.github.agentlitmus.cli.ProbeCommand;
import io.github.agentlitmus.cli.RegradeCommand;
import io.github.agentlitmus.cli.RerenderCommand;
import io.github.agentlitmus.cli.RunCommand;
import io.github.agentlitmus.cli.RunHttpCommand;
import io.github.agentlitmus.cli.SelftestCommand;
import io.github.agentlitmus.cli.Usage;
import io.github.agentlitmus.cli.ValidateCasesCommand;
import io.github.agentlitmus.cli.VerifyCommand;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentLitmus 命令行入口。
 * <p>
 * 需求「打包为可在 openKylin 上一键运行的 CLI 工具，支持导入不同智能体配置进行批量对比评测」。
 * 本类只做三件事：控制台编码兜底、命令分发、统一错误出口；每个子命令的实现住在
 * {@code io.github.agentlitmus.cli} 包里（一个命令一个类），新增命令不再往入口堆方法。
 *
 * <p>用法（完整见 {@code litmus --help}）：
 * <pre>
 *   litmus run                                  # 默认：内置两款智能体 + 内置用例
 *   litmus run --agents reference,degraded
 *   litmus run --cases my-cases.json --out out/
 *   litmus run --repeat 5                        # 同输入重复 5 次，产出稳定性证据
 *   litmus run --agents-config examples/agents-openkylin.json --out result/
 *   litmus leaderboard out/a out/b --out board/  # 多份运行汇总成一张静态榜单
 *   litmus export-cases --out my-cases.json      # 导出内置用例，便于扩展数据集
 *   litmus validate-cases                        # 校验数据集合法性与覆盖度
 * </pre>
 */
public final class Cli {

    private Cli() {
    }

    /** 命令注册表；包内可见供分发测试核对「注册的每个命令都在帮助文本里」 */
    static Map<String, Command> commands() {
        List<Command> all = List.of(
                new RunCommand(),
                new RunHttpCommand(),
                new ExportCasesCommand(),
                new ValidateCasesCommand(),
                new RerenderCommand(),
                new VerifyCommand(),
                new LeaderboardCommand(),
                new ProbeCommand(),
                new SelftestCommand(),
                new RegradeCommand(),
                new CompareCommand());
        Map<String, Command> registry = new LinkedHashMap<>();
        for (Command command : all) {
            Command previous = registry.put(command.name(), command);
            if (previous != null) {
                throw new IllegalStateException("命令名冲突: " + command.name());
            }
        }
        return registry;
    }

    public static void main(String[] args) {
        forceUtf8ConsoleIfNeeded();
        List<String> argv = new ArrayList<>(Arrays.asList(args));
        if (argv.isEmpty()) {
            argv.add("run");
        }
        String command = argv.remove(0);

        try {
            switch (command) {
                case "help", "-h", "--help" -> Usage.print();
                default -> {
                    Command target = commands().get(command);
                    if (target == null) {
                        System.err.println("未知命令: " + command);
                        Usage.print();
                        System.exit(2);
                    }
                    target.run(CliOptions.parse(argv));
                }
            }
        } catch (Exception e) {
            System.err.println("执行失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 控制台编码撑不住中文时，把 System.out/err 换成 UTF-8。
     *
     * <p>POSIX/C locale（信创服务器、CI 容器里很常见，表现为环境变量里没有 LANG）下，
     * JVM 会按 ASCII 编码标准输出，整份报告的中文全变成 {@code ?}——文件里的 report.txt
     * 仍然是好的，因为落盘一律显式 UTF-8，于是「看日志全乱码、看产物又正常」这种
     * 自相矛盾的现象会把人引到错误的方向上去查。
     *
     * <p>只在当前编码连「测」都编不出来时才替换，所以 Windows 的 GBK 控制台不受影响。
     */
    private static void forceUtf8ConsoleIfNeeded() {
        if (consoleCharset().newEncoder().canEncode('测')) {
            return;
        }
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    /** 依次问 JVM 几个编码属性，取第一个能解析的；都问不到就当 UTF-8。 */
    static Charset consoleCharset() {
        for (String key : new String[]{"stdout.encoding", "sun.stdout.encoding", "native.encoding", "file.encoding"}) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return Charset.forName(value);
            } catch (IllegalArgumentException ignored) {
                // 属性值是环境给的，解析不了就继续问下一个
            }
        }
        return StandardCharsets.UTF_8;
    }
}
