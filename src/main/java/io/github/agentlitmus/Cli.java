package io.github.agentlitmus;
import io.github.agentlitmus.agent.AgentFactory;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.agent.HttpAnswerer;
import io.github.agentlitmus.agent.AgentRole;
import io.github.agentlitmus.core.DialogueBenchmarkRunner;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.report.HtmlReport;
import io.github.agentlitmus.report.RadarChart;
import io.github.agentlitmus.report.ReportRates;
import io.github.agentlitmus.report.RunManifest;
import io.github.agentlitmus.report.CompareTable;
import io.github.agentlitmus.report.DeltaByMemory;
import io.github.agentlitmus.report.BenchmarkResult;
import io.github.agentlitmus.report.StabilityReport;
import io.github.agentlitmus.util.Digests;
import io.github.agentlitmus.agent.Agents;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.dataset.MemoryCases;
import io.github.agentlitmus.dataset.CaseSuites;
import io.github.agentlitmus.dataset.CoverageReport;
import io.github.agentlitmus.core.MultiAgentBenchmark;
import io.github.agentlitmus.core.OracleRecheck;
import io.github.agentlitmus.agent.AgentConfig;
import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.agent.CapabilityOverlay;
import io.github.agentlitmus.agent.RunnerMode;
import io.github.agentlitmus.agent.CommandAnswerer;
import io.github.agentlitmus.probe.AgentProber;
import io.github.agentlitmus.probe.ProbeResult;
import io.github.agentlitmus.core.CaseJudge;
import io.github.agentlitmus.core.Regrade;
import io.github.agentlitmus.core.RulerSelfTest;
import io.github.agentlitmus.core.RunCompare;
import io.github.agentlitmus.dataset.GoldSet;
import io.github.agentlitmus.llm.JudgeCache;
import io.github.agentlitmus.llm.OpenAiCompatClient;
import io.github.agentlitmus.core.AttributionEvidence;
import io.github.agentlitmus.core.ControlArms;
import io.github.agentlitmus.core.Judgment;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.stats.Consistency;
import io.github.agentlitmus.stats.Discrimination;
import io.github.agentlitmus.stats.PairedCompare;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AgentLitmus 命令行入口。
 * <p>
 * 命题要求「打包为可在 openKylin 上一键运行的 CLI 工具，支持导入不同智能体配置进行批量对比评测」，
 * 本类即该 CLI 的实现：<b>不依赖任何框架、不依赖 API Key</b>，一条命令跑完并产出：
 * <ul>
 *   <li>{@code report.txt}——各智能体报告 + 横向对比表（纯文本，可贴进材料）</li>
 *   <li>{@code report.html}——含六维雷达图、归因改进建议、稳定性与覆盖度的自包含报告（浏览器打开即见，便于录屏）</li>
 *   <li>{@code radar.svg}——雷达图单独文件</li>
 *   <li>{@code evidence/<agent>.jsonl}——每个智能体的运行证据（命题要求"证据统一组织"）</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   litmus run                                  # 默认：内置两款智能体 + 内置用例
 *   litmus run --agents reference,degraded
 *   litmus run --cases my-cases.json --out out/
 *   litmus run --repeat 5                        # 同输入重复 5 次，产出稳定性证据
 *   litmus run --agents-config examples/agents-openkylin.json --out result/
 *   litmus export-cases --out my-cases.json      # 导出内置用例，便于扩展数据集
 *   litmus validate-cases                        # 校验数据集合法性与覆盖度
 * </pre>
 */
public final class Cli {

    private static final String DEFAULT_OUT = "litmus-out";

    /** probe 结果的结构化输出 */
    private static final com.fasterxml.jackson.databind.ObjectWriter PRETTY =
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter();

    /** 不带值参数的开关：解析时不能被当作「上一个参数的值」吞掉 */
    private static final Set<String> FLAG_ONLY = Set.of("--with-control", "--with-oracle", "--judge",
            "--help", "-h");

    private Cli() {
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
                case "run" -> run(parse(argv));
                case "run-http" -> runHttp(parse(argv));
                case "export-cases" -> exportCases(parse(argv));
                case "validate-cases" -> validateCases(parse(argv));
                case "rerender" -> rerender(parse(argv));
                case "verify" -> verify(parse(argv));
                case "probe" -> probe(parse(argv));
                case "selftest" -> selftest(parse(argv));
                case "regrade" -> regrade(parse(argv));
                case "compare" -> compare(parse(argv));
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

    private static void run(Options options) throws IOException {
        Path outDir = Path.of(options.out);
        Files.createDirectories(outDir);

        // 1) 数据集：内置、分档题集或外置
        List<MemoryCase> cases = loadCases(options);
        System.out.println("用例集: " + (options.cases != null ? options.cases
                        : "档位 " + options.suite + " = " + CaseSuites.describe(options.suite))
                + "，共 " + cases.size() + " 条");

        // 1.5) 数据集治理：覆盖度校验（借鉴 agentbench 的数据治理能力）
        CoverageReport coverage = CoverageReport.of(cases);
        System.out.println(coverage.toText());
        if (!coverage.valid()) {
            coverage.issues().forEach(i -> System.out.println("警告(数据治理): " + i));
        }

        // 2) 被测智能体：优先用配置文件，否则按内置 id 解析
        List<String> warnings = new ArrayList<>();
        List<AgentUnderTest> agents;
        if (options.agentsConfig != null && !options.agentsConfig.isBlank()) {
            agents = AgentFactory.loadFrom(Path.of(options.agentsConfig), warnings);
        } else {
            List<String> agentIds = Arrays.stream(options.agents.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .toList();
            agents = Agents.resolve(agentIds, outDir.resolve("memory"), warnings);
        }
        warnings.forEach(w -> System.out.println("警告: " + w));
        if (agents.isEmpty()) {
            throw new IllegalArgumentException("没有可评测的智能体，请用 --agents 指定（内置: "
                    + String.join(",", Agents.defaultIds()) + "）");
        }
        long primaryN = agents.stream().filter(a -> a.role() == AgentRole.PRIMARY).count();
        long extraN = agents.stream().filter(a -> a.role() == AgentRole.EXTRA).count();
        System.out.println("被测智能体: " + agents.size() + " 款（主对比 " + primaryN + " / 附加 " + extraN + "）");

        // 2.5) 评委层：只有显式 --judge 且拿得到密钥时才启用；缺任一项就如实停在规则层
        List<String> judgeNotes = new ArrayList<>();
        JudgeCache judgeCache = options.judge ? JudgeCache.open(outDir.resolve("judge-cache.json")) : null;
        CaseJudge ruler = buildRuler(options, judgeCache, judgeNotes);
        judgeNotes.forEach(n -> System.out.println("评委层: " + n));

        // 3) 批量评测（规则判定，无需模型）；产物目录用于逐条用例落盘
        int repeat = Math.max(1, options.repeat);
        int parallel = Math.max(1, options.parallel);
        BenchmarkResult result;
        StabilityReport stability = null;
        Map<AgentUnderTest, List<BenchmarkReport>> repeatedReports = new LinkedHashMap<>();
        if (repeat > 1) {
            MultiAgentBenchmark.RepeatedResult repeated = MultiAgentBenchmark.runRepeated(
                    cases, agents, ruler, outDir.resolve("artifacts"), repeat);
            repeated.reports().forEach((a, list) -> repeatedReports.put(a, list));
            Map<AgentUnderTest, BenchmarkReport> repMap = new LinkedHashMap<>();
            repeatedReports.forEach((a, list) -> repMap.put(a, list.get(0)));
            result = new BenchmarkResult(repMap, repeated.evidence());
            stability = StabilityReport.of(repeated.reports());
            System.out.println("稳定性评测：同输入重复 " + repeat + " 次完成");
        } else {
            result = MultiAgentBenchmark.run(cases, agents, ruler, outDir.resolve("artifacts"), parallel);
        }

        // 3.5) 同题双臂对照：同一份提问与判定，只把注入事实换成中性开场
        String delta = null;
        BenchmarkResult controlResult = null;
        List<MemoryCase> twins = null;
        if (options.withControl) {
            twins = ControlArms.twinsOf(cases);
            System.out.println("对照臂: " + twins.size() + " 条（边界识别类不参与差值，见 ControlArms 说明）");
            controlResult = MultiAgentBenchmark.run(twins, agents, ruler,
                    outDir.resolve("artifacts-control"), parallel);
        }

        // 3.6) 神谕对照：失败用例附上正确事实重问，把「记不住」与「答不对」分开。
        //      两条臂都要调整，否则 Δ 的分子分母口径不一致——只调主臂会让增量凭空变大。
        String oracleText = null;
        Map<AgentUnderTest, Set<String>> oracleCases = new LinkedHashMap<>();
        if (options.withOracle) {
            StringBuilder sb = new StringBuilder();
            result = applyOracle(result, cases, outDir, "", options.oracleMax, sb, ruler, oracleCases);
            if (controlResult != null) {
                controlResult = applyOracle(controlResult, twins, outDir, "-control",
                        options.oracleMax, sb, ruler, oracleCases);
            }
            if (repeat > 1) {
                sb.append("注：稳定性统计基于未做神谕调整的原始判定。\n");
            }
            oracleText = sb.toString();
            System.out.println(oracleText);
        }

        if (controlResult != null) {
            StringBuilder sb = new StringBuilder();
            for (AgentUnderTest agent : result.primary()) {
                DeltaByMemory d = deltaOf(result.reportOf(agent), controlResult.reportOf(agent));
                if (d.hasControls()) {
                    sb.append("── ").append(agent.name()).append(" ──\n")
                            .append(d.toText()).append("\n");
                }
            }
            delta = sb.toString();
            for (AgentUnderTest agent : agents) {
                EvidenceCollector collector = controlResult.evidence().get(agent);
                if (collector != null) {
                    collector.exportTo(outDir.resolve("evidence").resolve(agent.id() + "-control.jsonl"));
                }
            }
        }

        // 4) 控制台输出
        StringBuilder text = new StringBuilder();
        for (AgentUnderTest agent : result.ranked()) {
            text.append("========== ").append(agent.name())
                    .append(agent.role() == AgentRole.EXTRA ? " [附加案例]" : "")
                    .append(" ==========\n");
            text.append(result.reportOf(agent).toText()).append("\n");
        }
        text.append(CompareTable.toText(result));

        // 题目区分度：不额外跑任何东西，只看「同一道题在不同被测上是否出现了分化」
        text.append(Discrimination.of(result).toText()).append('\n');

        // 归因证据化：报告里的归因有多少条是「看出来的」而不是「推出来的」
        Map<AgentUnderTest, AttributionEvidence.Result> attribution = attributionOf(result, cases, oracleCases);
        String attributionText = renderAttribution(attribution);
        text.append(attributionText).append('\n');

        // 同题配对 + bootstrap：两款主对比之间的差值才有统计含义
        List<AgentUnderTest> primary = result.primary();
        if (primary.size() == 2) {
            text.append(PairedCompare.toText(primary.get(0).name(), primary.get(1).name(),
                    PairedCompare.of(result.reportOf(primary.get(0)), result.reportOf(primary.get(1)))))
                    .append('\n');
        }

        // 逐题一致率：总体标准差会平均掉「个别题反复翻面」，这里把抖动落到题号上
        if (repeat > 1) {
            for (Map.Entry<AgentUnderTest, List<BenchmarkReport>> entry : repeatedReports.entrySet()) {
                text.append("── 逐题一致率: ").append(entry.getKey().name()).append(" ──\n")
                        .append(Consistency.of(entry.getValue()).toText()).append('\n');
            }
        }

        if (oracleText != null && !oracleText.isBlank()) {
            text.append('\n').append(oracleText);
        }
        if (delta != null && !delta.isBlank()) {
            text.append("\n").append(delta);
        }
        if (stability != null) {
            text.append(stability.toText());
        }
        if (!judgeNotes.isEmpty()) {
            text.append("\n── 判定口径 ──\n");
            text.append("规则版本: ").append(CaseJudge.RULES_VERSION).append('\n');
            judgeNotes.forEach(n -> text.append("评委层: ").append(n).append('\n'));
            if (judgeCache != null) {
                text.append(judgeCache.statsLine()).append('\n');
            }
        }
        System.out.println();
        System.out.println(text);

        // 5) 落盘产物
        Files.writeString(outDir.resolve("report.txt"), text.toString(), StandardCharsets.UTF_8);
        Map<String, Map<Dimension, Double>> primaryRates = new LinkedHashMap<>();
        for (AgentUnderTest agent : result.primary()) {
            primaryRates.put(agent.name(), RadarChart.ratesOf(result.reportOf(agent)));
        }
        Files.writeString(outDir.resolve("radar.svg"), RadarChart.svg(primaryRates), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("report.html"),
                HtmlReport.html(result, stability, coverage, delta, oracleText, attributionText),
                StandardCharsets.UTF_8);

        Path evidenceDir = outDir.resolve("evidence");
        for (AgentUnderTest agent : agents) {
            EvidenceCollector collector = result.evidence().get(agent);
            if (collector != null) {
                collector.exportTo(evidenceDir.resolve(agent.id() + ".jsonl"));
            }
        }

        // 5.5) 评委缓存与归因明细都必须先于运行契约落盘：它们也是产物，要一起进哈希
        if (judgeCache != null) {
            judgeCache.save();
        }
        writeAttribution(attribution, outDir);

        // 6) 运行契约：在所有产物落盘之后写，才能把它们全部纳入哈希
        RunManifest.of(cases, agents, result, outDir, repeat, parallel, CaseJudge.RULES_VERSION,
                options.cases == null ? options.suite : null).writeTo(outDir);

        System.out.println("报告已输出到: " + outDir.toAbsolutePath());
        System.out.println("  ├─ manifest.json 运行契约（题集/规则版本/环境/产物哈希），用 litmus verify 对账");
        System.out.println("  ├─ report.txt    文本报告 + 横向对比表（含 Δ 时，另附记忆增量）");
        System.out.println("  ├─ report.html   含六维雷达图、归因改进建议、稳定性与覆盖度（浏览器打开，便于录屏）");
        System.out.println("  ├─ radar.svg     雷达图");
        System.out.println("  ├─ artifacts/    逐条用例的运行产物（Markdown，可直接打开查看）");
        System.out.println("  ├─ attribution.json 归因证据化明细（有多少条归因由独立证据支撑）");
        System.out.println("  └─ evidence/     各智能体运行证据（JSON Lines）");
    }

    /**
     * 取本轮要用的题集：外置文件优先，其次分档题集，最后内置基础档。
     * <p>
     * 三者互斥而不是叠加——把"外置文件"和"档位"混着跑会让题集哈希对不上，
     * 运行契约也就失去了"这批分数出自哪套题"的唯一答案。
     */
    private static List<MemoryCase> loadCases(Options options) {
        if (options.cases != null && !options.cases.isBlank()) {
            return MemoryCases.loadFrom(Path.of(options.cases));
        }
        return CaseSuites.of(options.suite);
    }

    /** 合并主臂与对照臂的判定，算该智能体的记忆增量 */
    private static DeltaByMemory deltaOf(BenchmarkReport main, BenchmarkReport control) {
        List<Judgment> all = new ArrayList<>(main.judgments());
        all.addAll(control.judgments());
        return DeltaByMemory.of(new BenchmarkReport(all));
    }

    /**
     * 逐智能体统计「归因证据化比例」：带归因的判定里，有多少条能用<b>回答文本之外</b>的证据坐实。
     * <p>
     * 记忆注入模式下记忆由评测框架写入、逐条可读，因此该模式本身就等于「记忆可读」——
     * 这是模式带来的观测事实，不需要适配器再声明一次。对话模式下记忆在被测对象内部，
     * 只有配置里显式声明了 {@code MEMORY_INSPECTABLE}（例如给了 {@code stateDir}）才算可读。
     */
    private static Map<AgentUnderTest, AttributionEvidence.Result> attributionOf(
            BenchmarkResult result, List<MemoryCase> cases,
            Map<AgentUnderTest, Set<String>> oracleCases) {
        Map<String, MemoryCase> casesById = new LinkedHashMap<>();
        for (MemoryCase kase : cases) {
            casesById.put(kase.id(), kase);
        }
        Map<AgentUnderTest, AttributionEvidence.Result> out = new LinkedHashMap<>();
        for (AgentUnderTest agent : result.ranked()) {
            EvidenceCollector collector = result.evidence().get(agent);
            Set<AgentCapability> caps = new LinkedHashSet<>(agent.answerer().capabilities());
            if (agent.mode() == RunnerMode.MEMORY) {
                caps.add(AgentCapability.MEMORY_INSPECTABLE);
            }
            out.put(agent, AttributionEvidence.evaluate(
                    result.reportOf(agent).judgments(), casesById,
                    collector == null ? List.of() : collector.all(), caps,
                    oracleCases.getOrDefault(agent, Set.of())));
        }
        return out;
    }

    private static String renderAttribution(Map<AgentUnderTest, AttributionEvidence.Result> attribution) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 归因证据化（归因有多少条由独立证据支撑）==========\n");
        for (Map.Entry<AgentUnderTest, AttributionEvidence.Result> entry : attribution.entrySet()) {
            AttributionEvidence.Result r = entry.getValue();
            sb.append("── ").append(entry.getKey().name()).append(" ──\n");
            sb.append(r.headline()).append('\n');
            sb.append("可用证据: ").append(r.basis()).append('\n');
            r.byCause().forEach((cause, tally) -> sb.append("  ").append(cause.label())
                    .append("  ").append(tally[0]).append('/').append(tally[1]).append(" 条有证据\n"));
            if (!r.unbacked().isEmpty()) {
                sb.append("  未证据化 ").append(r.unbacked().size()).append(" 条（引用这些归因时需注明是推断）:\n");
                r.unbacked().stream().limit(20).forEach(u -> sb.append("    ").append(u).append('\n'));
                if (r.unbacked().size() > 20) {
                    sb.append("    …另有 ").append(r.unbacked().size() - 20).append(" 条，完整清单见 attribution.json\n");
                }
            }
        }
        // 全体的最低值决定这次运行的归因可信度：只要有一款以推断为主，就不能笼统说「归因有据」
        attribution.values().stream()
                .filter(r -> r.attributed() > 0)
                .mapToDouble(AttributionEvidence.Result::ratio)
                .min()
                .ifPresent(min -> sb.append("全体最低: ")
                        .append(String.format(java.util.Locale.ROOT, "%.1f%%", min * 100))
                        .append(min >= AttributionEvidence.TARGET
                                ? "（≥ 目标 80%，验收项 #3 达标）\n"
                                : "（< 目标 80%，验收项 #3 未达标——补 --with-oracle 或声明 stateDir 可提升）\n"));
        return sb.toString();
    }

    /**
     * 归因证据化明细落盘为 {@code attribution.json}。
     * <p>
     * 报告正文只列前 20 条未证据化归因，完整清单在这里；它必须在 {@code RunManifest.of} 之前写完，
     * 这样它自己也会被计入产物哈希——否则「归因有多少条没证据」这句话本身就没有对账依据。
     */
    private static void writeAttribution(Map<AgentUnderTest, AttributionEvidence.Result> attribution,
                                         Path outDir) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("target", AttributionEvidence.TARGET);
        List<Map<String, Object>> agents = new ArrayList<>();
        double min = 1.0;
        boolean any = false;
        for (Map.Entry<AgentUnderTest, AttributionEvidence.Result> entry : attribution.entrySet()) {
            AttributionEvidence.Result r = entry.getValue();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("agent", entry.getKey().name());
            node.put("attributed", r.attributed());
            node.put("backed", r.backed());
            node.put("ratio", r.ratio());
            node.put("meetsTarget", r.meetsTarget());
            node.put("basis", r.basis());
            Map<String, Object> byCause = new LinkedHashMap<>();
            r.byCause().forEach((cause, tally) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("backed", tally[0]);
                item.put("attributed", tally[1]);
                byCause.put(cause.name(), item);
            });
            node.put("byCause", byCause);
            node.put("unbacked", r.unbacked());
            agents.add(node);
            if (r.attributed() > 0) {
                any = true;
                min = Math.min(min, r.ratio());
            }
        }
        root.put("agents", agents);
        root.put("minRatio", any ? min : 1.0);
        root.put("meetsTarget", !any || min >= AttributionEvidence.TARGET);
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("attribution.json"),
                PRETTY.writeValueAsString(root), StandardCharsets.UTF_8);
    }

    /**
     * 对结果里的每个智能体做神谕重问，返回调整后的结果，并把逐条结论写入
     * {@code <out>/oracle<tag>/<agent>.md}。
     * <p>
     * 调整只有一种方向：把「附上正确事实仍答错」的失败改成不可判（BASE_OR_EXEC）。
     * 因此有效分是<b>排除非记忆成因失败后</b>的记忆得分——报告必须同时给出重问条数，
     * 否则读者无从知道分母被移动过多少条。
     */
    private static BenchmarkResult applyOracle(BenchmarkResult source, List<MemoryCase> cases,
                                               Path outDir, String tag, int maxCases,
                                               StringBuilder sink, CaseJudge ruler,
                                               Map<AgentUnderTest, Set<String>> oracleCases) throws IOException {
        Map<AgentUnderTest, BenchmarkReport> adjusted = new LinkedHashMap<>();
        Path oracleDir = outDir.resolve("oracle" + (tag.isBlank() ? "" : tag));
        Files.createDirectories(oracleDir);
        for (Map.Entry<AgentUnderTest, BenchmarkReport> entry : source.reports().entrySet()) {
            AgentUnderTest agent = entry.getKey();
            String text;
            if (!OracleRecheck.applicableTo(agent)) {
                adjusted.put(agent, entry.getValue());
                text = "【" + agent.name() + "】" + OracleRecheck.skipReason(agent);
            } else {
                OracleRecheck recheck = new OracleRecheck(agent.answerer(), ruler, maxCases,
                        source.evidence().get(agent));
                OracleRecheck.Result recheckResult = recheck.run(cases, entry.getValue());
                adjusted.put(agent, recheckResult.report());
                if (oracleCases != null) {
                    Set<String> ids = new LinkedHashSet<>();
                    recheckResult.checks().forEach(c -> ids.add(c.caseId()));
                    oracleCases.put(agent, ids);
                }
                text = "【" + agent.name() + "】\n" + recheckResult.toText();
            }
            sink.append(text).append('\n');
            Files.writeString(oracleDir.resolve(agent.id() + ".md"), text, StandardCharsets.UTF_8);
        }
        return new BenchmarkResult(adjusted, source.evidence());
    }

    /**
     * 接入探测（probe）：跑评测<b>之前</b>先探清这个平台能被测到什么程度。
     * <p>
     * 输入是一份智能体配置（与 {@code run --agents-config} 同一格式），输出三样东西：
     * <ul>
     *   <li>{@code probe.json}——逐条观测结论 + 生效能力 + 置信层级；</li>
     *   <li>{@code agents-<id>.draft.json}——回填好 capabilities/stateDir 的配置草案，可直接用于 run；</li>
     *   <li>{@code probe.md}——人读的结论与「哪些用例在这个平台上测不了」。</li>
     * </ul>
     * 探测只花极小的对话成本（每个对象不到十回合）且全部使用虚构标记词，
     * 目的是让后面几百回合的评测建立在<b>正确的可测边界</b>上。
     */
    private static void probe(Options options) throws IOException {
        if (options.agentsConfig == null || options.agentsConfig.isBlank()) {
            throw new IllegalArgumentException("litmus probe 需要 --agents-config <file>："
                    + "探针按配置逐个探测被测对象（http / command / builtin）");
        }
        Path outDir = Path.of(options.out);
        Files.createDirectories(outDir);
        List<String> warnings = new ArrayList<>();
        List<AgentConfig> configs = AgentFactory.loadConfigs(Path.of(options.agentsConfig));
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("配置文件里没有可用的智能体: " + options.agentsConfig);
        }
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 接入探测结论\n\n配置: ").append(options.agentsConfig).append("\n\n");
        List<Map<String, Object>> jsonResults = new ArrayList<>();
        for (AgentConfig config : configs) {
            AgentUnderTest agent;
            try {
                agent = AgentFactory.create(config);
            } catch (Exception e) {
                warnings.add("配置无效，已跳过 [" + config.id() + "]: " + e.getMessage());
                continue;
            }
            List<Path> roots = new ArrayList<>(agent.memoryRoots());
            if (options.stateDir != null && !options.stateDir.isBlank()) {
                Path override = Path.of(options.stateDir).toAbsolutePath();
                if (roots.stream().noneMatch(r -> r.toAbsolutePath().equals(override))) {
                    roots.add(0, override);
                }
            }
            ProbeResult result = new AgentProber(agent.answerer(), config.declaredCapabilities(), roots)
                    .probe(config.id(), config.name());
            System.out.println(result.toText());
            jsonResults.add(result.toMap());
            markdown.append("## ").append(result.agentName()).append("\n\n")
                    .append("- 置信层级：**").append(result.tier().name())
                    .append("（").append(result.tier().label()).append("）**\n")
                    .append("- 跨会话用例可用：").append(result.crossSessionUsable()
                            ? "是" : "否（跨会话题将判为不可判、移出分母）").append('\n')
                    .append("- 生效能力：").append(result.granted().isEmpty() ? "仅对话"
                            : result.granted().stream().map(c -> c.label()).toList()).append("\n\n")
                    .append("| 观测 | 结论 | 依据 |\n| --- | --- | --- |\n");
            result.steps().forEach(step -> markdown.append("| ").append(mdEscape(step.name()))
                    .append(" | ").append(step.result().label())
                    .append(" | ").append(mdEscape(step.detail())).append(" |\n"));
            markdown.append('\n');
            result.warnings().forEach(w -> markdown.append("> 提示：").append(mdEscape(w)).append("\n"));
            markdown.append('\n');
            Path draft = outDir.resolve("agents-" + config.id() + ".draft.json");
            Files.writeString(draft, result.configDraftJson(config.type(), config.endpoint(),
                    config.command(), config.args(), config.resetArgs()), StandardCharsets.UTF_8);
        }
        Files.writeString(outDir.resolve("probe.md"), markdown.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("probe.json"), PRETTY.writeValueAsString(jsonResults),
                StandardCharsets.UTF_8);
        warnings.forEach(w -> System.out.println("警告: " + w));
        System.out.println("探测结论已输出到: " + outDir.toAbsolutePath()
                + "\n  ├─ probe.md               逐条观测与提示（人读）"
                + "\n  ├─ probe.json             结构化结论（脚本消费）"
                + "\n  └─ agents-<id>.draft.json 回填能力后的配置草案，可直接用于 litmus run --agents-config");
    }

    /** Markdown 表格里不能出现裸竖线或换行；观测依据里两者都可能有 */
    private static String mdEscape(String text) {
        return text == null ? "" : text.replace("|", "／").replaceAll("\\s+", " ").trim();
    }

    /**
     * 组装本轮用的判定器。
     * <p>
     * 评委层是<b>可选增强</b>，不是前提：拿不到密钥就退回纯规则层，并把原因写进报告。
     * 这条路径必须始终可用——评测工具如果必须有 API Key 才能跑，那它在离线环境、
     * 内网、以及"我不想为了复核一个数字去申请额度"的场景里就等于不存在。
     *
     * @param notes 出参：把"评委层为什么没启用 / 启用了哪个评委"写成人读的一行
     */
    private static CaseJudge buildRuler(Options options, JudgeCache cache, List<String> notes) {
        if (!options.judge) {
            notes.add("未启用（规则层判定，口径 " + CaseJudge.RULES_VERSION
                    + "）；加 --judge 可让语义层接管「关键词分不开」的用例");
            return new CaseJudge();
        }
        String key = OpenAiCompatClient.keyFromEnv();
        if (key == null) {
            notes.add("已请求但不可用——环境变量里没有密钥（LITMUS_LLM_API_KEY / AI_DASHSCOPE_API_KEY），"
                    + "本轮退回规则层");
            return new CaseJudge();
        }
        OpenAiCompatClient client = new OpenAiCompatClient(options.judgeEndpoint, options.judgeModel, key);
        notes.add("已启用 " + client.model() + " @ " + client.endpoint()
                + "，指纹 " + client.fingerprint()
                + "，密钥来源 " + OpenAiCompatClient.keySourceName()
                + "（密钥本身不写入任何产物）");
        return new CaseJudge(client, client.model() + "@" + client.fingerprint(), cache);
    }

    /**
     * 尺子自证：用标注集量<b>判定器自己</b>，报一致率 / Cohen's κ / 弃权率与逐条分歧。
     * <p>
     * 这条命令不接触任何被测对象，因此可以进 CI：每次改判定规则都自动对账，
     * 防止"改了尺子却以为改的是结论"。
     * <p>
     * 门禁只卡<b>能判的</b>项：规则层的开口即对率必须达标；评委层的 κ 只有在评委真的启用时才算，
     * 没启用就写"不可判"而不是算它通过——用缺席的评委去证明尺子准，是这套工具在别处一律拒绝的动作。
     */
    private static void selftest(Options options) throws IOException {
        Path outDir = Path.of(options.out);
        Files.createDirectories(outDir);
        GoldSet gold = options.gold == null || options.gold.isBlank()
                ? GoldSet.bundled() : GoldSet.load(Path.of(options.gold));
        List<MemoryCase> pool = CaseSuites.of(CaseSuites.FULL);

        List<String> notes = new ArrayList<>();
        JudgeCache cache = null;
        CaseJudge judgeRuler = null;
        if (options.judge) {
            cache = JudgeCache.open(outDir.resolve("judge-cache.json"));
            judgeRuler = buildRuler(options, cache, notes);
            if (!judgeRuler.llmAvailable()) {
                judgeRuler = null;
            }
        } else {
            notes.add("未启用（加 --judge 可同时量评委层，并让 κ 门禁生效）");
        }

        RulerSelfTest.Result result = RulerSelfTest.run(gold, pool, judgeRuler,
                String.join("；", notes), cache);
        if (cache != null) {
            cache.save();
        }

        String text = result.toText();
        System.out.println();
        System.out.println(text);
        Files.writeString(outDir.resolve("selftest.txt"), text, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("selftest.json"), result.toJson(), StandardCharsets.UTF_8);
        System.out.println("自证结果已输出到: " + outDir.toAbsolutePath()
                + "\n  ├─ selftest.txt  人读报告（一致率 / κ / 弃权率 / 分歧明细）"
                + "\n  └─ selftest.json 结构化结果（含混淆矩阵，可接 CI）");

        List<String> failures = result.gateFailures();
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * 离线重评：不重跑被测对象，只用<b>当前</b>口径把一次已完成运行里的回答重判一遍。
     * <p>
     * 产物一律写到 {@code --out}，源目录只读——写回源目录会让它自己的运行契约哈希全部失配，
     * 等于为了重评毁掉原始证据。因此 {@code --out} 与 {@code --from} 相同直接报错。
     */
    private static void regrade(Options options) throws IOException {
        Path runDir = Path.of(options.from == null ? DEFAULT_OUT : options.from);
        Path manifestFile = runDir.resolve(RunManifest.FILE_NAME);
        if (!Files.exists(manifestFile)) {
            System.err.println("未找到运行契约: " + manifestFile
                    + "（请用 --from 指定评测输出目录；没有契约就无法确认这批数字出自哪个口径）");
            System.exit(1);
        }
        Path outDir = Path.of(options.out);
        if (outDir.toAbsolutePath().equals(runDir.toAbsolutePath())) {
            throw new IllegalArgumentException("--out 不能与 --from 相同："
                    + "重评对源目录只读，写进去会让源运行契约的产物哈希全部失配");
        }
        Files.createDirectories(outDir);

        RunManifest manifest = RunManifest.read(manifestFile);
        List<MemoryCase> pool = reconcileCases(options, manifest);
        List<String> notes = new ArrayList<>();
        JudgeCache cache = options.judge ? JudgeCache.open(outDir.resolve("judge-cache.json")) : null;
        CaseJudge ruler = buildRuler(options, cache, notes);
        System.out.println("重评对象   : " + runDir.toAbsolutePath() + "（只读）");
        System.out.println("题集       : " + pool.size() + " 条（档位 "
                + (options.cases == null ? String.valueOf(manifest.suite()) : "外置文件") + "）");
        notes.forEach(n -> System.out.println("评委层: " + n));

        Regrade.Result result = Regrade.run(runDir, pool, ruler);
        if (cache != null) {
            cache.save();
        }

        String text = result.toText();
        System.out.println();
        System.out.println(text);
        Files.writeString(outDir.resolve("regrade.txt"), text, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("regrade.json"), result.toJson(), StandardCharsets.UTF_8);
        System.out.println("重评结果已输出到: " + outDir.toAbsolutePath()
                + "\n  ├─ regrade.txt   逐条裁决变化（人读）"
                + "\n  └─ regrade.json  结构化结果（脚本消费）");
        if (!result.usable()) {
            System.exit(1);
        }
    }

    /**
     * 版本回归门禁：逐题比较两次运行，有显著回归或新增不可判时以非零退出码结束，可直接挂 CI。
     * <p>
     * 两侧的裁决都是从各自证据流里<b>照抄</b>的当时读数，不重判——重判是 {@code regrade} 的事。
     * 口径或题集不一致时直接判红线：那种情况下差值同时包含「尺子变了」与「东西变了」，
     * 报出来的回归是假的，而假回归会让人去查一个不存在的问题。
     */
    private static void compare(Options options) throws IOException {
        String a = options.from != null ? options.from
                : (options.positional.isEmpty() ? null : options.positional.get(0));
        String b = options.to != null ? options.to
                : (options.positional.size() > 1 ? options.positional.get(1) : null);
        if (a == null || b == null) {
            System.err.println("用法: litmus compare <基线目录> <本次目录>   （或 --from <A> --to <B>）");
            System.exit(2);
        }
        Path dirA = Path.of(a);
        Path dirB = Path.of(b);
        RunCompare.Result result = RunCompare.of(dirA, dirB);

        String text = result.toText();
        System.out.println();
        System.out.println(text);

        if (options.outExplicit) {
            Path outDir = Path.of(options.out);
            if (outDir.toAbsolutePath().equals(dirA.toAbsolutePath())
                    || outDir.toAbsolutePath().equals(dirB.toAbsolutePath())) {
                throw new IllegalArgumentException("--out 不能是被比较的运行目录之一："
                        + "写进去会让该目录的运行契约产物哈希失配");
            }
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("compare.txt"), text, StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("compare.json"), result.toJson(), StandardCharsets.UTF_8);
            System.out.println("比较结果已输出到: " + outDir.toAbsolutePath()
                    + "（compare.txt / compare.json）");
        }
        System.out.println("退出码     : " + (result.blocked() ? "1（有红线，CI 应失败）" : "0（通过）"));
        System.exit(result.blocked() ? 1 : 0);
    }

    /**
     * 对<b>真实智能体</b>跑评测：通过 HTTP 调用，以对话方式注入事实。
     * <p>
     * 这是面向"真实智能体运行证据"的评测入口，用于验证工具在真实系统上的可用性。
     */
    private static void runHttp(Options options) throws IOException {
        if (options.endpoint == null || options.endpoint.isBlank()) {
            throw new IllegalArgumentException("请使用 --endpoint 指定智能体地址，例如 --endpoint http://localhost:8089");
        }
        Path outDir = Path.of(options.out);
        Files.createDirectories(outDir);

        List<MemoryCase> cases = loadCases(options);

        HttpAnswerer answerer = new HttpAnswerer(options.endpoint);
        // --state-dir 把「记忆写没写进去」从推断升成观测；不给就如实停在 T1
        List<Path> memoryRoots = options.stateDir == null || options.stateDir.isBlank()
                ? List.of() : List.of(Path.of(options.stateDir));
        AgentUnderTest realAgent = AgentUnderTest.dialogue("real-agent", "真实智能体（HTTP）",
                "通过 HTTP 接口接入的真实智能体", answerer, AgentRole.PRIMARY, memoryRoots);
        System.out.println("被测智能体: " + options.endpoint);
        System.out.println("用例集: " + cases.size() + " 条（对话式注入；档位 "
                + (options.cases == null ? options.suite : "外置文件") + "）");

        List<String> judgeNotes = new ArrayList<>();
        JudgeCache judgeCache = options.judge ? JudgeCache.open(outDir.resolve("judge-cache.json")) : null;
        CaseJudge ruler = buildRuler(options, judgeCache, judgeNotes);
        judgeNotes.forEach(n -> System.out.println("评委层: " + n));

        DialogueBenchmarkRunner runner = new DialogueBenchmarkRunner(
                answerer, ruler, new EvidenceCollector(), outDir.resolve("artifacts"),
                memoryRoots);
        int parallel = Math.max(1, options.parallel);
        BenchmarkReport report = runner.run(cases, parallel);

        // 神谕对照：把「记不住」与「答不对」分开；两条臂都要调，否则 Δ 口径不一致
        StringBuilder oracleSink = new StringBuilder();
        Map<AgentUnderTest, Set<String>> httpOracleCases = new LinkedHashMap<>();
        if (options.withOracle) {
            BenchmarkReport only = report;
            BenchmarkResult single = new BenchmarkResult(Map.of(realAgent, only),
                    Map.of(realAgent, runner.collector()));
            BenchmarkResult adjusted = applyOracle(single, cases, outDir, "",
                    options.oracleMax, oracleSink, ruler, httpOracleCases);
            report = adjusted.reportOf(realAgent);
        }

        String delta = null;
        if (options.withControl) {
            List<MemoryCase> twins = ControlArms.twinsOf(cases);
            System.out.println("对照臂: " + twins.size() + " 条（同题、仅替换注入事实为中性开场）");
            BenchmarkReport controlReport = runner.run(twins, parallel);
            if (options.withOracle) {
                BenchmarkResult controlSingle = new BenchmarkResult(Map.of(realAgent, controlReport),
                        Map.of(realAgent, runner.collector()));
                controlReport = applyOracle(controlSingle, twins, outDir, "-control",
                        options.oracleMax, oracleSink, ruler, httpOracleCases).reportOf(realAgent);
            }
            delta = deltaOf(report, controlReport).toText();
        }

        BenchmarkResult result = new BenchmarkResult(Map.of(realAgent, report),
                Map.of(realAgent, runner.collector()));

        System.out.println();
        System.out.println(report.toText());
        if (delta != null) {
            System.out.println(delta);
        }

        // 与 run 保持一致：单对象走 HTTP 时同样要交代「归因有多少条是看出来的」
        Map<AgentUnderTest, AttributionEvidence.Result> attribution =
                attributionOf(result, cases, httpOracleCases);
        String attributionText = renderAttribution(attribution);

        StringBuilder text = new StringBuilder(report.toText());
        if (!oracleSink.isEmpty()) {
            text.append('\n').append(oracleSink);
        }
        if (delta != null) {
            text.append('\n').append(delta);
        }
        text.append('\n').append(attributionText);
        Files.writeString(outDir.resolve("real-agent-report.txt"), text.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("real-agent-report.html"),
                HtmlReport.html(result, null, CoverageReport.of(cases), delta,
                        oracleSink.toString(), attributionText),
                StandardCharsets.UTF_8);
        writeAttribution(attribution, outDir);
        runner.collector().exportTo(outDir.resolve("evidence").resolve("real-agent.jsonl"));
        if (judgeCache != null) {
            judgeCache.save();
        }
        RunManifest.of(cases, List.of(realAgent), result, outDir, 1, parallel, CaseJudge.RULES_VERSION,
                options.cases == null ? options.suite : null).writeTo(outDir);

        System.out.println("报告已输出到: " + outDir.toAbsolutePath());
    }

    private static void exportCases(Options options) {
        Path file = Path.of(options.out).resolve("cases.json");
        MemoryCases.exportTo(loadCases(options), file);
        System.out.println("内置用例已导出到: " + file.toAbsolutePath());
        System.out.println("可在其基础上扩展数据集，再用 --cases 指定运行。");
    }

    /** 校验数据集合法性与覆盖度（数据治理入口） */
    private static void validateCases(Options options) {
        List<MemoryCase> cases = loadCases(options);
        CoverageReport coverage = CoverageReport.of(cases);
        System.out.println(coverage.toText());
        System.exit(coverage.valid() ? 0 : 1);
    }

    /**
     * 对账一次运行：重算运行契约里的每一项哈希并与目录比对。
     * <p>
     * 这是「可复现」从口号变成可检查动作的那一步——题集、规则版本、环境、产物文件任何一处被改动，
     * 本命令都会指出是哪一处，并以非零退出码结束（可用于材料归档校验）。
     */
    private static void verify(Options options) throws IOException {
        Path runDir = Path.of(options.from == null ? DEFAULT_OUT : options.from);
        Path manifestFile = runDir.resolve(RunManifest.FILE_NAME);
        if (!Files.exists(manifestFile)) {
            System.err.println("未找到运行契约: " + manifestFile + "（请用 --from 指定评测输出目录）");
            System.exit(1);
        }
        RunManifest manifest = RunManifest.read(manifestFile);
        System.out.println("========== 运行契约对账 ==========");
        System.out.println("运行目录   : " + runDir.toAbsolutePath());
        System.out.println("生成时间   : " + manifest.generatedAt());
        System.out.println("判定口径   : " + manifest.rulesVersion());
        System.out.println("用例集     : " + manifest.caseCount() + " 条  sha256=" + Digests.shortForm(manifest.caseSetSha256())
                + "  档位=" + (manifest.suite() == null || manifest.suite().isBlank()
                    ? "未记录（旧契约，按 --suite 对账）" : manifest.suite()));
        System.out.println("环境       : " + manifest.environment().osName() + " " + manifest.environment().osVersion()
                + " (" + manifest.environment().osArch() + ") / JDK " + manifest.environment().javaVersion());
        System.out.println("被测对象   : " + manifest.agents().size() + " 款");
        manifest.agents().forEach(entry -> System.out.println("  - " + entry.name()
                + "  置信层级 " + entry.confidenceTier()
                + "  能力 " + (entry.capabilities().isEmpty() ? "仅对话" : entry.capabilities())
                + "  隔离 " + entry.isolation()));

        List<String> issues = new ArrayList<>(manifest.verify(runDir));

        // 题集对账：用当前代码/当前文件重算，能发现「跑结果的题集和现在仓库里的题集不是同一份」
        List<MemoryCase> cases = reconcileCases(options, manifest);
        String now = Digests.sha256Hex(MemoryCases.canonicalJson(cases));
        if (!now.equals(manifest.caseSetSha256())) {
            issues.add("题集与本次运行不一致：契约 " + Digests.shortForm(manifest.caseSetSha256())
                    + " ≠ 当前 " + Digests.shortForm(now) + "（如为外置题集请加 --cases）");
        }

        if (issues.isEmpty()) {
            System.out.println("结论       : 通过——" + manifest.files().size() + " 份产物全部与契约一致");
            System.out.println("==================================");
            return;
        }
        System.out.println("结论       : 不一致（" + issues.size() + " 项）");
        issues.forEach(issue -> System.out.println("  - " + issue));
        System.out.println("==================================");
        System.exit(1);
    }

    /**
     * 题集对账该用哪一份题：契约里记了档位就按<b>那个档位</b>重算。
     * <p>
     * 否则对着 full 档的输出目录跑一次默认档位的对账，只会得到假的「题集不一致」——
     * 假的失败比没有检查更糟，它会让人以为对账这个动作本身不可信。
     */
    private static List<MemoryCase> reconcileCases(Options options, RunManifest manifest) {
        if (options.cases != null && !options.cases.isBlank()) {
            return MemoryCases.loadFrom(Path.of(options.cases));
        }
        String suite = manifest.suite();
        if (suite == null || suite.isBlank()) {
            return CaseSuites.of(options.suite);
        }
        try {
            return CaseSuites.of(suite);
        } catch (IllegalArgumentException e) {
            System.out.println("题集档位   : 契约记录的 " + suite + " 在当前代码里已不存在，按 --suite "
                    + options.suite + " 对账（题集哈希大概率不一致，这是真的不一致）");
            return CaseSuites.of(options.suite);
        }
    }

    private static void printUsage() {
        System.out.println("""
                AgentLitmus · 智能体长期记忆评测基准

                用法:
                  litmus run [选项]              运行评测（默认内置两款智能体对比）
                  litmus validate-cases [选项]   校验数据集合法性与覆盖度
                  litmus export-cases [选项]     导出内置用例集，便于扩展数据集
                  litmus rerender --from <dir> [--out <新目录>]
                                                 按已有报告重新渲染雷达图（不重跑评测）；
                                                 源目录已登记产物哈希时必须给 --out，拒绝就地改写
                  litmus verify --from <dir>     对账一次运行的产物与题集哈希（可复现校验）
                  litmus probe --agents-config <file>
                                                 接入探测：先探清这个平台能被测到什么程度，
                                                 产出置信层级与可直接用的配置草案
                  litmus selftest [选项]         尺子自证：用标注集量判定器自己，报一致率 / κ / 弃权率，
                                                 不达标则退出码非零（可接 CI）
                  litmus regrade --from <dir> --out <dir>
                                                 离线重评：不重跑被测对象，用当前口径重判已有证据，
                                                 逐条列出裁决变化；源目录只读
                  litmus compare <A> <B> [--out <dir>]
                                                 版本回归门禁：逐题比较两次运行；显著回归或新增不可判
                                                 ⇒ 退出码非零（可接 CI）

                选项:
                  --agents <id,id,...>   指定被测智能体（内置: reference,degraded）
                  --agents-config <file> 从 JSON 文件导入智能体配置（http/command/builtin），可标注 role
                  --suite <name>         题集档位：basic/security/pressure/crosscut/standard/full（默认 basic）
                  --cases <file>         使用外部用例集 JSON（覆盖 --suite）
                  --out <dir>            输出目录（默认 litmus-out）
                  --endpoint <url>       真实智能体地址（run-http 使用）
                  --repeat <N>           同输入重复评测 N 次，产出稳定性证据（默认 1）
                  --parallel <N>         用例并行度，加速真实智能体评测（默认 1 串行；总 token 消耗不变）
                  --with-control         追加「同题双臂」对照臂，报告记忆增量 Δ（用例数与 token 翻倍）
                  --with-oracle          失败用例附上正确事实重问，区分「记不住」与「答不对」
                  --oracle-max <N>       神谕重问的条数上限（默认 40；真实智能体上重问要花 token）
                  --state-dir <dir>      被测对象的记忆落盘目录（run-http 用；给出后可升到 T2 证据）
                  --judge                启用评委模型（语义层）：接管关键词分不开的用例，
                                         密钥只从环境变量读（LITMUS_LLM_API_KEY / AI_DASHSCOPE_API_KEY）
                  --judge-model <name>   评委模型名（默认 qwen-plus）
                  --judge-endpoint <url> OpenAI 兼容的 chat/completions 端点（默认 DashScope 兼容模式）
                  --gold <file>          外置标注集（selftest 用；默认用随 jar 发布的内置标注集）
                  --from <dir>           源运行目录（verify / rerender / regrade / compare 的 A 侧）
                  --to <dir>             compare 的 B 侧；也可用两个位置参数代替 --from/--to
                  --help                 显示帮助

                示例:
                  litmus run
                  litmus run --agents reference,degraded --out result/
                  litmus run --repeat 5 --out stable/
                  litmus run --agents-config examples/agents-openkylin.json --out result/
                  litmus run --cases my-cases.json --out result/
                  litmus run-http --endpoint http://localhost:8089 --out real/
                  litmus probe --agents-config examples/agents-openkylin.json --out probe-out/
                  litmus run --agents-config agents.json --with-control --with-oracle
                  litmus rerender --from samples/full-final-150
                  litmus validate-cases
                  litmus selftest --out selftest-out/
                  litmus selftest --judge        # 同时量规则层与评委层，κ 门禁生效
                  litmus regrade --from out/hermes-01 --out out/hermes-01-regraded
                  litmus compare out/hermes-01 out/hermes-02   # 回归门禁；有红线则退出码 1
                """);
    }

    /**
     * 按已有报告重新渲染雷达图——<b>不重跑评测</b>。
     * <p>
     * 评测一旦涉及真实智能体，往往耗时数十分钟并消耗云端额度；而图表样式只是<b>渲染问题</b>，
     * 与数据采集无关。因此从 {@code report.txt} 解析各智能体六维通过率后直接重新出图，
     * 并同步更新 {@code report.html} 中内嵌的 SVG。
     *
     * <p>产物默认写到哪里由 {@link #rerenderTarget} 决定：带 {@code --out} 时源目录只读，
     * 不带时只有在源目录没有 {@code manifest.json} 的情况下才允许就地改写。
     */
    private static void rerender(Options o) {
        Path from = Path.of(o.from == null ? DEFAULT_OUT : o.from);
        Path report = from.resolve("report.txt");
        if (!Files.exists(report)) {
            System.err.println("未找到报告文件: " + report + "（请先运行评测，或用 --from 指定运行目录）");
            return;
        }
        Path target = rerenderTarget(from, Path.of(o.out), o.outExplicit);
        boolean inPlace = target.equals(from);
        try {
            Map<String, Map<Dimension, Double>> series = ReportRates.parse(report);
            if (series.isEmpty()) {
                System.err.println("未能从 " + report + " 解析出横向对比数据。");
                return;
            }
            if (!inPlace) {
                Files.createDirectories(target);
            }
            String svg = RadarChart.svg(series);
            Path svgOut = target.resolve("radar.svg");
            Files.writeString(svgOut, svg, StandardCharsets.UTF_8);
            System.out.println("已重新生成雷达图: " + svgOut + "（" + series.size() + " 款智能体）");
            series.forEach((name, rates) -> System.out.println("  - " + name));

            Path srcHtml = from.resolve("report.html");
            if (Files.exists(srcHtml)) {
                String content = Files.readString(srcHtml, StandardCharsets.UTF_8);
                String updated = replaceFirstSvg(content, svg);
                Path htmlOut = target.resolve("report.html");
                if (updated == null) {
                    System.out.println("内嵌雷达图无从替换（HTML 里没有完整的 <svg>…</svg> 块），只产出 radar.svg");
                } else if (inPlace && updated.equals(content)) {
                    System.out.println("内嵌雷达图已是最新（与现有版本逐字节一致，未改写）");
                } else {
                    Files.writeString(htmlOut, updated, StandardCharsets.UTF_8);
                    System.out.println("已同步更新内嵌雷达图: " + htmlOut);
                }
            }
            if (!inPlace) {
                Files.copy(report, target.resolve("report.txt"), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("源目录未被改动: " + from);
                System.out.println("注意: " + target + " 里没有 manifest.json——"
                        + "产物哈希属于原始那次运行，这个目录不能拿去做 verify 对账。");
            }
        } catch (IOException e) {
            System.err.println("重新渲染失败: " + e.getMessage());
        }
    }

    /**
     * 决定重渲染的产物写到哪里。
     *
     * <p>就地改写 {@code radar.svg} / {@code report.html} 会让同一个目录的
     * {@code verify} 报「产物被改动」——一次运行的可对账性就这样被一条画图命令毁掉，
     * 而用户只是想让图好看一点。所以目录里已经登记了哈希就拒绝就地写，
     * 让人换一个 {@code --out}；没登记过（临时目录、旧版本产物）才允许就地。
     */
    static Path rerenderTarget(Path from, Path out, boolean outExplicit) {
        Path source = from.toAbsolutePath().normalize();
        if (outExplicit) {
            Path target = out.toAbsolutePath().normalize();
            if (target.equals(source)) {
                throw new IllegalStateException("输出目录与 --from 相同：" + source
                        + "。源目录需要只读，否则这次渲染会覆盖掉自己解析的那份报告。请换一个 --out。");
            }
            return target;
        }
        if (Files.exists(source.resolve("manifest.json"))) {
            throw new IllegalStateException("拒绝就地重渲染：" + source
                    + " 已用 manifest.json 登记产物哈希，改写 radar.svg / report.html 之后"
                    + " verify 会报「产物被改动」——画图命令不该毁掉一次运行的可对账性。"
                    + "请加 --out <新目录>，产物写到新目录，源目录不动。");
        }
        return source;
    }

    /**
     * 用新的 SVG 替换 HTML 中第一个 {@code <svg>…</svg>} 块。
     *
     * @return 替换后的内容；找不到完整 SVG 块时返回 {@code null}（与「替换后内容一致」区分开）
     */
    static String replaceFirstSvg(String html, String svg) {
        int start = html.indexOf("<svg");
        if (start < 0) {
            return null;
        }
        int end = html.indexOf("</svg>", start);
        if (end < 0) {
            return null;
        }
        return html.substring(0, start) + svg + html.substring(end + "</svg>".length());
    }

    // ------------------------------------------------------------------ 参数解析

    private static Options parse(List<String> argv) {
        Options options = new Options();
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            switch (arg) {
                case "--agents" -> options.agents = value(argv, ++i, arg);
                case "--cases" -> options.cases = value(argv, i + 1 > argv.size() - 1 ? i : i + 1, arg);
                case "--suite" -> options.suite = value(argv, ++i, arg);
                case "--out" -> {
                    options.out = value(argv, i + 1 > argv.size() - 1 ? i : i + 1, arg);
                    options.outExplicit = true;
                }
                case "--endpoint" -> options.endpoint = value(argv, i + 1 > argv.size() - 1 ? i : i + 1, arg);
                case "--agents-config" -> options.agentsConfig = value(argv, i + 1 > argv.size() - 1 ? i : i + 1, arg);
                case "--from" -> options.from = value(argv, ++i, arg);
                case "--to" -> options.to = value(argv, ++i, arg);
                case "--with-control" -> options.withControl = true;
                case "--judge" -> options.judge = true;
                case "--judge-model" -> options.judgeModel = value(argv, ++i, arg);
                case "--judge-endpoint" -> options.judgeEndpoint = value(argv, ++i, arg);
                case "--gold" -> options.gold = value(argv, ++i, arg);
                case "--with-oracle" -> options.withOracle = true;
                case "--state-dir" -> options.stateDir = value(argv, i + 1 > argv.size() - 1 ? i : i + 1, arg);
                case "--oracle-max" -> {
                    try {
                        options.oracleMax = Integer.parseInt(value(argv, ++i, arg));
                    } catch (NumberFormatException e) {
                        System.err.println("警告: --oracle-max 参数无效，已忽略（使用默认 "
                                + OracleRecheck.DEFAULT_MAX_CASES + "）");
                    }
                }
                case "--parallel" -> {
                    try {
                        options.parallel = Integer.parseInt(value(argv, ++i, arg));
                    } catch (NumberFormatException e) {
                        System.err.println("警告: --parallel 参数无效，已忽略（使用默认 1）");
                    }
                }
                case "--repeat" -> {
                    try {
                        options.repeat = Integer.parseInt(value(argv, ++i, arg));
                    } catch (NumberFormatException e) {
                        System.err.println("警告: --repeat 参数无效，已忽略（使用默认 1）");
                    }
                }
                default -> {
                    // 位置参数（compare 的两个运行目录）收下来；未知的开关仍然忽略，避免中断评测
                    if (!arg.startsWith("-")) {
                        options.positional.add(arg);
                    }
                }
            }
            // 跳过已消费的值（布尔开关不消费后续参数）
            if (arg.startsWith("--") && !FLAG_ONLY.contains(arg)
                    && i + 1 < argv.size() && !argv.get(i + 1).startsWith("--")) {
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
        private String agentsConfig;
        private String cases;
        private String suite = CaseSuites.BASIC;
        private String out = DEFAULT_OUT;
        private String endpoint;
        private int repeat = 1;
        private int parallel = 1;
        private boolean withControl;
        private boolean withOracle;
        private String stateDir;
        private boolean judge;
        private String judgeModel;
        private String judgeEndpoint;
        private String gold;
        private int oracleMax = OracleRecheck.DEFAULT_MAX_CASES;
        private String from;
        private String to;
        /** {@code --out} 是否显式给出：compare 只在显式给出时才写产物，避免默认目录撞上被比较的运行目录 */
        private boolean outExplicit;
        private final List<String> positional = new ArrayList<>();
    }
}
