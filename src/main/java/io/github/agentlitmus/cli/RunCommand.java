package io.github.agentlitmus.cli;

import io.github.agentlitmus.agent.AgentFactory;
import io.github.agentlitmus.agent.AgentRole;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.Agents;
import io.github.agentlitmus.core.AttributionEvidence;
import io.github.agentlitmus.core.CaseJudge;
import io.github.agentlitmus.core.ControlArms;
import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.MultiAgentBenchmark;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.dataset.CaseSuites;
import io.github.agentlitmus.dataset.CoverageReport;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.llm.JudgeCache;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.report.BenchmarkResult;
import io.github.agentlitmus.report.CompareTable;
import io.github.agentlitmus.report.DeltaByMemory;
import io.github.agentlitmus.report.HtmlReport;
import io.github.agentlitmus.report.RadarChart;
import io.github.agentlitmus.report.RunManifest;
import io.github.agentlitmus.report.StabilityReport;
import io.github.agentlitmus.stats.Consistency;
import io.github.agentlitmus.stats.Discrimination;
import io.github.agentlitmus.stats.PairedCompare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code litmus run}——批量评测入口。
 * <p>
 * 需求「打包为可在 openKylin 上一键运行的 CLI 工具，支持导入不同智能体配置进行批量对比评测」。
 * 产出：{@code report.txt}（纯文本报告 + 横向对比表）、{@code report.html}（含六维雷达图、
 * 归因改进建议、稳定性与覆盖度的自包含报告）、{@code radar.svg}、{@code evidence/<agent>.jsonl}
 * 与逐条用例的 Markdown 产物，最后写运行契约 {@code manifest.json} 供 {@code verify} 对账。
 */
public final class RunCommand implements Command {

    @Override
    public String name() {
        return "run";
    }

    @Override
    public void run(CliOptions options) throws IOException {
        Path outDir = Path.of(options.out);
        Files.createDirectories(outDir);

        // 1) 数据集：内置、分档题集或外置
        List<MemoryCase> cases = Runs.loadCases(options);
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
        CaseJudge ruler = Runs.buildRuler(options, judgeCache, judgeNotes);
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
            result = Runs.applyOracle(result, cases, outDir, "", options.oracleMax, sb, ruler, oracleCases);
            if (controlResult != null) {
                controlResult = Runs.applyOracle(controlResult, twins, outDir, "-control",
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
                DeltaByMemory d = Runs.deltaOf(result.reportOf(agent), controlResult.reportOf(agent));
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
        Map<AgentUnderTest, AttributionEvidence.Result> attribution =
                Runs.attributionOf(result, cases, oracleCases);
        String attributionText = Runs.renderAttribution(attribution);
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
        Runs.writeAttribution(attribution, outDir);

        // 6) 运行契约：在所有产物落盘之后写，才能把它们全部纳入哈希
        RunManifest.of(cases, agents, result, outDir, repeat, parallel, CaseJudge.RULES_VERSION,
                options.cases == null ? options.suite : null).writeTo(outDir);

        System.out.println("报告已输出到: " + outDir.toAbsolutePath());
        System.out.println("  ├─ manifest.json 运行契约（题集/规则版本/环境/产物哈希），用 litmus verify 对账");
        System.out.println("  ├─ report.txt    文本报告 + 横向对比表（含 Δ 时，另附记忆增量）");
        System.out.println("  ├─ report.html   含六维雷达图、归因改进建议、稳定性与覆盖度（浏览器打开，便于演示）");
        System.out.println("  ├─ radar.svg     雷达图");
        System.out.println("  ├─ artifacts/    逐条用例的运行产物（Markdown，可直接打开查看）");
        System.out.println("  ├─ attribution.json 归因证据化明细（有多少条归因由独立证据支撑）");
        System.out.println("  └─ evidence/     各智能体运行证据（JSON Lines）");
    }
}
