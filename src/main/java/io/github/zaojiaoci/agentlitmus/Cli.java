package io.github.zaojiaoci.agentlitmus;
import io.github.zaojiaoci.agentlitmus.evidence.EvidenceCollector;
import io.github.zaojiaoci.agentlitmus.report.HtmlReport;
import io.github.zaojiaoci.agentlitmus.report.RadarChart;
import io.github.zaojiaoci.agentlitmus.report.CompareTable;
import io.github.zaojiaoci.agentlitmus.report.BenchmarkResult;
import io.github.zaojiaoci.agentlitmus.agent.Agents;
import io.github.zaojiaoci.agentlitmus.agent.AgentUnderTest;
import io.github.zaojiaoci.agentlitmus.dataset.MemoryCases;
import io.github.zaojiaoci.agentlitmus.core.MultiAgentBenchmark;
import io.github.zaojiaoci.agentlitmus.core.CaseJudge;
import io.github.zaojiaoci.agentlitmus.core.MemoryCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AgentLitmus 命令行入口。
 * <p>
 * 命题要求「打包为可在 openKylin 上一键运行的 CLI 工具，支持导入不同智能体配置进行批量对比评测」，
 * 本类即该 CLI 的实现：<b>不依赖任何框架、不依赖 API Key</b>，一条命令跑完并产出：
 * <ul>
 *   <li>{@code report.txt}——各智能体报告 + 横向对比表（纯文本，可贴进材料）</li>
 *   <li>{@code report.html}——含六维雷达图的自包含报告（浏览器打开即见，便于录屏）</li>
 *   <li>{@code radar.svg}——雷达图单独文件</li>
 *   <li>{@code evidence/<agent>.jsonl}——每个智能体的运行证据（命题要求"证据统一组织"）</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   litmus run                                  # 默认：内置两款智能体 + 内置用例
 *   litmus run --agents reference,degraded
 *   litmus run --cases my-cases.json --out out/
 *   litmus export-cases --out my-cases.json     # 导出内置用例，便于扩展数据集
 * </pre>
 */
public final class Cli {

    private static final String DEFAULT_OUT = "litmus-out";

    private Cli() {
    }

    public static void main(String[] args) {
        List<String> argv = new ArrayList<>(Arrays.asList(args));
        if (argv.isEmpty()) {
            argv.add("run");
        }
        String command = argv.remove(0);

        try {
            switch (command) {
                case "run" -> run(parse(argv));
                case "export-cases" -> exportCases(parse(argv));
                case "help", "-h", "--help" -> printUsage();
                default -> {
                    System.err.println("未知命令: " + command);
                    printUsage();
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("执行失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(Options options) throws IOException {
        Path outDir = Path.of(options.out);
        Files.createDirectories(outDir);

        // 1) 数据集：内置或外置
        List<MemoryCase> cases = options.cases != null
                ? MemoryCases.loadFrom(Path.of(options.cases))
                : MemoryCases.defaultCases();
        System.out.println("用例集: " + (options.cases == null ? "内置 (" : options.cases + " (")
                + cases.size() + " 条)");

        // 2) 被测智能体：按 id 解析，各自独立记忆目录
        List<String> agentIds = Arrays.stream(options.agents.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList();
        List<String> warnings = new ArrayList<>();
        List<AgentUnderTest> agents = Agents.resolve(agentIds, outDir.resolve("memory"), warnings);
        warnings.forEach(w -> System.out.println("警告: " + w));
        if (agents.isEmpty()) {
            throw new IllegalArgumentException("没有可评测的智能体，请用 --agents 指定（内置: "
                    + String.join(",", Agents.defaultIds()) + "）");
        }
        System.out.println("被测智能体: " + agents.size() + " 款");

        // 3) 批量评测（规则判定，无需模型）
        BenchmarkResult result = MultiAgentBenchmark.run(cases, agents, new CaseJudge());

        // 4) 控制台输出
        StringBuilder text = new StringBuilder();
        for (AgentUnderTest agent : result.ranked()) {
            text.append("========== ").append(agent.name()).append(" ==========\n");
            text.append(result.reportOf(agent).toText()).append("\n");
        }
        text.append(CompareTable.toText(result));
        System.out.println();
        System.out.println(text);

        // 5) 落盘产物
        Files.writeString(outDir.resolve("report.txt"), text.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("radar.svg"),
                RadarChart.svg(result.dimensionRates()), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("report.html"),
                HtmlReport.html(result), StandardCharsets.UTF_8);

        Path evidenceDir = outDir.resolve("evidence");
        for (AgentUnderTest agent : agents) {
            EvidenceCollector collector = result.evidence().get(agent);
            if (collector != null) {
                collector.exportTo(evidenceDir.resolve(agent.id() + ".jsonl"));
            }
        }

        System.out.println("报告已输出到: " + outDir.toAbsolutePath());
        System.out.println("  ├─ report.txt    文本报告 + 横向对比表");
        System.out.println("  ├─ report.html   含六维雷达图（浏览器打开，便于录屏）");
        System.out.println("  ├─ radar.svg     雷达图");
        System.out.println("  └─ evidence/     各智能体运行证据（JSON Lines）");
    }

    private static void exportCases(Options options) {
        Path file = Path.of(options.out).resolve("cases.json");
        MemoryCases.exportTo(MemoryCases.defaultCases(), file);
        System.out.println("内置用例已导出到: " + file.toAbsolutePath());
        System.out.println("可在其基础上扩展数据集，再用 --cases 指定运行。");
    }

    private static void printUsage() {
        System.out.println("""
                AgentLitmus · 智能体长期记忆评测基准

                用法:
                  litmus run [选项]              运行评测（默认内置两款智能体对比）
                  litmus export-cases [选项]     导出内置用例集，便于扩展数据集

                选项:
                  --agents <id,id,...>   指定被测智能体（内置: reference,degraded）
                  --cases <file>         使用外部用例集 JSON（默认内置 12 条）
                  --out <dir>            输出目录（默认 litmus-out）
                  --help                 显示帮助

                示例:
                  litmus run
                  litmus run --agents reference,degraded --out result/
                  litmus run --cases my-cases.json --out result/
                """);
    }

    // ------------------------------------------------------------------ 参数解析

    private static Options parse(List<String> argv) {
        Options options = new Options();
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            switch (arg) {
                case "--agents" -> options.agents = value(argv, ++i, arg);
                case "--cases" -> options.cases = value(argv, i + 1 > argv.size() - 1 ? i : i + 1, arg);
                case "--out" -> options.out = value(argv, i + 1 > argv.size() - 1 ? i : i + 1, arg);
                default -> {
                    // 忽略未知参数，避免因多余参数中断评测
                }
            }
            // 跳过已消费的值
            if (arg.startsWith("--") && i + 1 < argv.size() && !argv.get(i + 1).startsWith("--")) {
                i++;
            }
        }
        if (options.agents == null || options.agents.isBlank()) {
            options.agents = String.join(",", Agents.defaultIds());
        }
        return options;
    }

    private static String value(List<String> argv, int index, String flag) {
        if (index >= argv.size()) {
            throw new IllegalArgumentException("缺少参数值: " + flag);
        }
        return argv.get(index);
    }

    private static final class Options {
        private String agents;
        private String cases;
        private String out = DEFAULT_OUT;
    }
}
