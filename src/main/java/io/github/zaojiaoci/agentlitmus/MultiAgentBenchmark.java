package io.github.zaojiaoci.agentlitmus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多智能体批量评测执行器。
 * <p>
 * 对每一个被测智能体：使用<b>独立的记忆目录</b>与<b>独立的证据收集器</b>跑完整套用例，
 * 保证智能体之间互不污染——这是「批量对比」结果可信的前提。
 */
public final class MultiAgentBenchmark {

    private MultiAgentBenchmark() {
    }

    public static BenchmarkResult run(List<MemoryCase> cases,
                                      List<AgentUnderTest> agents,
                                      CaseJudge judge) {
        Map<AgentUnderTest, BenchmarkReport> reports = new LinkedHashMap<>();
        Map<AgentUnderTest, EvidenceCollector> evidences = new LinkedHashMap<>();

        for (AgentUnderTest agent : agents) {
            // 每个智能体独立的证据收集器，便于分别导出留证
            EvidenceCollector collector = new EvidenceCollector();
            // 智能体的记忆已在 Agents.resolve 中按 id 隔离，这里复用其 answerer
            MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(
                    memoryOf(agent), agent.answerer(), judge, collector);
            reports.put(agent, runner.run(cases));
            evidences.put(agent, collector);
        }

        return new BenchmarkResult(reports, evidences);
    }

    /**
     * 取出智能体背后的记忆实现。
     * <p>
     * 约定：被测智能体由 {@link Agents#builtin} 构造时持有各自独立的 {@link FileLongTermMemory}；
     * 这里通过反射无关的方式取回——由构造方保证一致性。
     */
    private static LongTermMemory memoryOf(AgentUnderTest agent) {
        return MemoryRegistry.resolve(agent);
    }
}
