package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.RunnerMode;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.memory.MemoryRegistry;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.report.BenchmarkResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多智能体批量评测执行器。
 * <p>
 * 按智能体声明的 {@link RunnerMode} 自动选择执行路径：
 * <ul>
 *   <li>{@code MEMORY}——记忆由框架注入，用 {@link MemoryBenchmarkRunner}；</li>
 *   <li>{@code DIALOGUE}——记忆由对话建立，用 {@link DialogueBenchmarkRunner}（真实智能体）。</li>
 * </ul>
 * 每个智能体使用<b>独立的证据收集器</b>，保证结果互不污染；批量对比因此可信。
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
            EvidenceCollector collector = new EvidenceCollector();
            BenchmarkReport report = agent.mode() == RunnerMode.MEMORY
                    ? new MemoryBenchmarkRunner(MemoryRegistry.resolve(agent), agent.answerer(), judge, collector).run(cases)
                    : new DialogueBenchmarkRunner(agent.answerer(), judge, collector).run(cases);

            reports.put(agent, report);
            evidences.put(agent, collector);
        }

        return new BenchmarkResult(reports, evidences);
    }
}
