package io.github.agentlitmus.cli;

import io.github.agentlitmus.agent.AgentRole;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.HttpAnswerer;
import io.github.agentlitmus.core.AttributionEvidence;
import io.github.agentlitmus.core.CaseJudge;
import io.github.agentlitmus.core.ControlArms;
import io.github.agentlitmus.core.DialogueBenchmarkRunner;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.dataset.CoverageReport;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.llm.JudgeCache;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.report.BenchmarkResult;
import io.github.agentlitmus.report.HtmlReport;
import io.github.agentlitmus.report.RunManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code litmus run-http}——对<b>真实智能体</b>跑评测：通过 HTTP 调用，以对话方式注入事实。
 * <p>
 * 这是面向"真实智能体运行证据"的评测入口，用于验证工具在真实系统上的可用性。
 */
public final class RunHttpCommand implements Command {

    @Override
    public String name() {
        return "run-http";
    }

    @Override
    public void run(CliOptions options) throws IOException {
        if (options.endpoint == null || options.endpoint.isBlank()) {
            throw new IllegalArgumentException("请使用 --endpoint 指定智能体地址，例如 --endpoint http://localhost:8089");
        }
        Path outDir = Path.of(options.out);
        Files.createDirectories(outDir);

        List<MemoryCase> cases = Runs.loadCases(options);

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
        CaseJudge ruler = Runs.buildRuler(options, judgeCache, judgeNotes);
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
            BenchmarkResult single = new BenchmarkResult(Map.of(realAgent, report),
                    Map.of(realAgent, runner.collector()));
            BenchmarkResult adjusted = Runs.applyOracle(single, cases, outDir, "",
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
                controlReport = Runs.applyOracle(controlSingle, twins, outDir, "-control",
                        options.oracleMax, oracleSink, ruler, httpOracleCases).reportOf(realAgent);
            }
            delta = Runs.deltaOf(report, controlReport).toText();
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
                Runs.attributionOf(result, cases, httpOracleCases);
        String attributionText = Runs.renderAttribution(attribution);

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
        Runs.writeAttribution(attribution, outDir);
        runner.collector().exportTo(outDir.resolve("evidence").resolve("real-agent.jsonl"));
        if (judgeCache != null) {
            judgeCache.save();
        }
        RunManifest.of(cases, List.of(realAgent), result, outDir, 1, parallel, CaseJudge.RULES_VERSION,
                options.cases == null ? options.suite : null).writeTo(outDir);

        System.out.println("报告已输出到: " + outDir.toAbsolutePath());
    }
}
