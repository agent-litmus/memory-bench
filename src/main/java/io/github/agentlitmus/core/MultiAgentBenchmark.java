package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.RunnerMode;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.memory.FileLongTermMemory;
import io.github.agentlitmus.memory.LongTermMemory;
import io.github.agentlitmus.memory.MemoryRegistry;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.report.BenchmarkResult;

import java.nio.file.Path;
import java.util.ArrayList;
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
        return run(cases, agents, judge, null);
    }

    /**
     * @param artifactRoot 运行产物根目录；非 null 时每个智能体一个子目录，逐条用例落盘
     */
    public static BenchmarkResult run(List<MemoryCase> cases,
                                      List<AgentUnderTest> agents,
                                      CaseJudge judge,
                                      Path artifactRoot) {
        return run(cases, agents, judge, artifactRoot, 1);
    }

    /**
     * @param parallelism 用例并行度（{@code <=1} 表示串行）。
     *                    仅对对话式（真实智能体）路径生效：内置确定性智能体是纯内存计算，
     *                    本身瞬时完成，无需并行。
     */
    public static BenchmarkResult run(List<MemoryCase> cases,
                                      List<AgentUnderTest> agents,
                                      CaseJudge judge,
                                      Path artifactRoot,
                                      int parallelism) {
        Map<AgentUnderTest, BenchmarkReport> reports = new LinkedHashMap<>();
        Map<AgentUnderTest, EvidenceCollector> evidences = new LinkedHashMap<>();

        for (AgentUnderTest agent : agents) {
            EvidenceCollector collector = new EvidenceCollector();
            Path dir = artifactRoot == null ? null : artifactRoot.resolve(agent.id());
            BenchmarkReport report = agent.mode() == RunnerMode.MEMORY
                    ? new MemoryBenchmarkRunner(MemoryRegistry.resolve(agent), agent.answerer(), judge, collector, dir).run(cases)
                    : new DialogueBenchmarkRunner(agent.answerer(), judge, collector, dir).run(cases, parallelism);

            reports.put(agent, report);
            evidences.put(agent, collector);
        }

        return new BenchmarkResult(reports, evidences);
    }

    /**
     * 同输入重复评测 N 次，用于稳定性证据（命题「稳定性与可复现性」15% 权重）。
     * <p>
     * 每次重复都从<b>干净状态</b>独立运行：内置记忆模式每次使用独立的记忆目录，
     * 对话模式每次使用不同的会话 ID 前缀（见 {@link MemoryCase#withSessionId}），
     * 避免真实智能体的跨轮记忆累积污染后续重复，使结果只反映被测对象本身的稳定性。
     *
     * @param repeats 重复次数（>=1；为 1 时等价单次 run）
     * @return 每智能体的 N 次报告与（首次运行的）证据
     */
    public static RepeatedResult runRepeated(List<MemoryCase> cases,
                                             List<AgentUnderTest> agents,
                                             CaseJudge judge,
                                             Path artifactRoot,
                                             int repeats) {
        int n = Math.max(1, repeats);
        Map<AgentUnderTest, List<BenchmarkReport>> reports = new LinkedHashMap<>();
        Map<AgentUnderTest, EvidenceCollector> evidences = new LinkedHashMap<>();

        for (AgentUnderTest agent : agents) {
            List<BenchmarkReport> reps = new ArrayList<>();
            EvidenceCollector firstEvidence = null;
            for (int r = 1; r <= n; r++) {
                EvidenceCollector collector = new EvidenceCollector();
                Path dir = (artifactRoot == null) ? null : artifactRoot.resolve(agent.id() + "-r" + r);
                BenchmarkReport rep;
                if (agent.mode() == RunnerMode.MEMORY) {
                    // 复用被测对象自身关联的长期记忆实例（与 answerer 同一份），
                    // 并在每轮重复前清空所有会话，保证各次重复从干净状态独立运行——
                    // 否则注入与回答可能落在不同记忆实例上，导致结果失真。
                    LongTermMemory memory = MemoryRegistry.resolve(agent);
                    for (String sid : cases.stream().map(MemoryCase::sessionId).distinct().toList()) {
                        memory.clear(sid);
                    }
                    rep = new MemoryBenchmarkRunner(memory, agent.answerer(), judge, collector, dir).run(cases);
                } else {
                    final int runNo = r;
                    List<MemoryCase> isolated = cases.stream()
                            .map(c -> c.withSessionId(c.sessionId() + "-r" + runNo))
                            .toList();
                    rep = new DialogueBenchmarkRunner(agent.answerer(), judge, collector, dir).run(isolated);
                }
                reps.add(rep);
                if (r == 1) {
                    firstEvidence = collector;
                }
            }
            reports.put(agent, reps);
            evidences.put(agent, firstEvidence);
        }
        return new RepeatedResult(reports, evidences);
    }

    /** 重复评测结果：每智能体一份 N 次报告，以及首次运行的证据 */
    public record RepeatedResult(Map<AgentUnderTest, List<BenchmarkReport>> reports,
                                 Map<AgentUnderTest, EvidenceCollector> evidence) {
    }
}
