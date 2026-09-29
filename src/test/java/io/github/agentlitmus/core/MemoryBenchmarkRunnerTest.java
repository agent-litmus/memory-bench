package io.github.agentlitmus.core;
import io.github.agentlitmus.dataset.MemoryCases;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.llm.LlmClient;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.agent.DegradedAnswerer;
import io.github.agentlitmus.agent.RecallAnswerer;
import io.github.agentlitmus.memory.MemoryEntry;
import io.github.agentlitmus.memory.FileLongTermMemory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆评测链路的行为测试。
 * <p>
 * 全部使用文件存储 + 确定性回答器，<b>不发起任何网络请求、不需要 API Key</b>，
 * 因此 openKylin 上可直接跑，并自动产出多维指标报告。
 */
class MemoryBenchmarkRunnerTest {

    @TempDir
    Path tempDir;

    private FileLongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new FileLongTermMemory(tempDir);
    }

    @Test
    void defaultCasesPassWithRecallAnswerer() {
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(
                memory, new RecallAnswerer(memory), new CaseJudge());

        BenchmarkReport report = runner.run(MemoryCases.defaultCases());

        // 打印报告：在 CI / openKylin 上跑完即可直接看到多维指标
        System.out.println(report.toText());

        assertEquals(72, report.total(), "内置用例集应为 72 条（六维各 12）");
        assertEquals(72, report.passedCount(), "确定性回答器应通过全部用例:\n" + report.toText());
        assertEquals(1.0, report.passRate(), 0.0001);
    }

    @Test
    void detectsFailureWhenAnswererIgnoresMemory() {
        // 一个「失忆」的被测对象：永远回答不知道
        Answerer amnesiac = (sessionId, question) -> "我不掌握相关信息。";
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(memory, amnesiac, new CaseJudge());

        BenchmarkReport report = runner.run(MemoryCases.defaultCases());

        // 依赖「应记住」的维度必须判失败，否则评测就失去了意义
        assertFalse(report.byDimension().get(Dimension.RETENTION).passed() > 0,
                "长期保持维度在失忆回答器下不应通过");
        // 记忆调用维度含 1 条「反向」用例（c10：过敏源不应出现在推荐里）——
        // 失忆回答器因为「什么都没说」会恰好通过它，其余 11 条必须判失败
        assertTrue(report.byDimension().get(Dimension.RECALL).passed() <= 1,
                "记忆调用维度在失忆回答器下最多只能通过反向用例:\n" + report.toText());
        assertTrue(report.passedCount() < report.total(),
                "整体通过率必须低于 100%，说明评测能发现问题");
    }

    @Test
    void boundaryDimensionExcludesNonRetainableInformation() {
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(
                memory, new RecallAnswerer(memory), new CaseJudge());

        BenchmarkReport report = runner.run(MemoryCases.defaultCases().stream()
                .filter(kase -> kase.dimension() == Dimension.BOUNDARY)
                .toList());

        BenchmarkReport.DimensionStat boundary = report.byDimension().get(Dimension.BOUNDARY);
        assertEquals(12, boundary.total());
        assertEquals(12, boundary.passed(), "不应保留的信息必须被正确排除:\n" + report.toText());
    }

    @Test
    void updateDimensionReturnsNewFactAndNotTheOldOne() {
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(
                memory, new RecallAnswerer(memory), new CaseJudge());

        BenchmarkReport report = runner.run(MemoryCases.defaultCases().stream()
                .filter(kase -> kase.dimension() == Dimension.UPDATE)
                .toList());

        BenchmarkReport.DimensionStat update = report.byDimension().get(Dimension.UPDATE);
        assertEquals(12, update.passed(), "动态更新后应只使用新信息:\n" + report.toText());
    }

    @Test
    void reportAggregatesAllSixDimensions() {
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(
                memory, new RecallAnswerer(memory), new CaseJudge());

        BenchmarkReport report = runner.run(MemoryCases.defaultCases());

        assertEquals(6, report.byDimension().size(), "应覆盖六个评测维度");
        assertEquals(72, report.total(), "六个维度用例数合计应为 72 条");
        report.byDimension().forEach((dimension, stat) ->
                assertEquals(12, stat.total(), dimension.label() + " 维度应有 12 条用例"));
    }

    @Test
    void collectsEvidenceAndExportsToFile() {
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(
                memory, new RecallAnswerer(memory), new CaseJudge());

        runner.run(List.of(MemoryCase.expect("r1", Dimension.RETENTION,
                List.of("用户从事订单录入工作"), "我的工作内容是什么？", "订单录入")));

        EvidenceCollector collector = runner.collector();
        // 每条用例：注入记忆 + 提问 + 回答 + 判定结果
        assertTrue(collector.size() >= 4, "应收集到记忆、提问、回答与判定四类证据");

        Path exported = collector.exportTo(tempDir.resolve("evidence").resolve("run.jsonl"));
        List<Evidence> reloaded = EvidenceCollector.loadFrom(exported);

        assertNotNull(exported);
        assertEquals(collector.size(), reloaded.size(), "导出的证据应可完整读回");
        assertTrue(reloaded.stream().anyMatch(e -> Evidence.MEMORY.equals(e.type())));
        assertTrue(reloaded.stream().anyMatch(e -> Evidence.DIALOGUE.equals(e.type())));
        assertTrue(reloaded.stream().anyMatch(e -> Evidence.ARTIFACT.equals(e.type())));
    }

    @Test
    void judgeFallsBackToRuleWhenLlmUnavailable() {
        CaseJudge judge = new CaseJudge();
        assertFalse(judge.llmAvailable());

        MemoryCase llmCase = MemoryCase.llmJudged("x1", Dimension.REUSE,
                List.of("用户希望转岗到数据分析岗位"), "帮我规划转岗路径",
                "回答应合理使用历史记忆中的转岗目标");

        // 无模型时不应抛异常，而应降级为规则判定并标注
        Judgment judgment = judge.judge(llmCase, "建议转岗到数据分析岗位");
        assertEquals(Judgment.MODE_DEGRADED, judgment.mode(),
                "需要 LLM 判定但无模型时，应降级而不是中断评测");
    }

    @Test
    void classifiesFailuresIntoFiveOutcomeTypes() {
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(
                memory, new DegradedAnswerer(memory), new CaseJudge());

        BenchmarkReport report = runner.run(MemoryCases.defaultCases());

        Map<Outcome, Long> counts = report.outcomeCounts();

        // 缺陷智能体应当被识别出三类不同的记忆缺陷，而不只是笼统的"未过"
        assertTrue(counts.get(Outcome.WRONG_REUSE) > 0,
                "复用过时旧值应被归类为错误复用");
        assertTrue(counts.get(Outcome.WRONG_PERSISTENCE) > 0,
                "泄露不应保留的信息应被归类为错误持久化");
        assertTrue(counts.get(Outcome.CONFUSION) > 0,
                "混淆相近信息应被归类为混淆");
        assertTrue(counts.get(Outcome.CORRECT) > 0, "应有通过的用例");
    }

    @Test
    void correctCasesAreClassifiedAsCorrect() {
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(
                memory, new RecallAnswerer(memory), new CaseJudge());

        BenchmarkReport report = runner.run(MemoryCases.defaultCases());

        assertEquals(72L, report.outcomeCounts().get(Outcome.CORRECT),
                "参考智能体应全部归为正确记忆");
        assertEquals(0L, report.outcomeCounts().get(Outcome.OMISSION));
        assertEquals(0L, report.outcomeCounts().get(Outcome.CONFUSION));
        assertEquals(0L, report.outcomeCounts().get(Outcome.WRONG_PERSISTENCE));
        assertEquals(0L, report.outcomeCounts().get(Outcome.WRONG_REUSE));
    }

    @Test
    void llmClientCanBePluggedInWithoutFramework() {
        // 用一行 lambda 接入任意模型——核心库不绑定任何框架
        LlmClient fakeLlm = prompt -> "PASS 回答合理使用了历史记忆";
        CaseJudge judge = new CaseJudge(fakeLlm);

        MemoryCase llmCase = MemoryCase.llmJudged("x2", Dimension.REUSE,
                List.of("用户希望转岗到数据分析岗位"), "帮我规划转岗路径",
                "回答应合理使用历史记忆中的转岗目标");

        Judgment judgment = judge.judge(llmCase, "建议转岗到数据分析岗位");

        assertTrue(judge.llmAvailable());
        assertEquals(Judgment.MODE_LLM, judgment.mode());
        assertTrue(judgment.passed());
    }
}
