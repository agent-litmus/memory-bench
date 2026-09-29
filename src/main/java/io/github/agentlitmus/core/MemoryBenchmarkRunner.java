package io.github.agentlitmus.core;
import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.evidence.Transcript;
import io.github.agentlitmus.memory.LongTermMemory;
import io.github.agentlitmus.memory.MemoryEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆评测执行器。
 * <p>
 * 每条用例的执行流程固定为五步，全过程都会留下证据：
 * <ol>
 *   <li>清空该用例的会话，保证用例之间互不污染；</li>
 *   <li>注入种子记忆（记忆写入即证据）；</li>
 *   <li>若用例声明了更新，用新事实取代第一条种子（对应动态更新维度）；</li>
 *   <li>向被测回答器提问（提问与回答均为证据）；</li>
 *   <li>判定回答并汇总（判定结果作为产物证据）。</li>
 * </ol>
 * 执行器不依赖任何模型：被测对象由 {@link Answerer} 注入，
 * 用 {@link RecallAnswerer} 时全流程确定性可复现，无需 API Key。
 */
public class MemoryBenchmarkRunner {

    private final LongTermMemory memory;
    private final Answerer answerer;
    private final CaseJudge judge;
    private final EvidenceCollector collector;
    private final Path artifactDir;

    public MemoryBenchmarkRunner(LongTermMemory memory, Answerer answerer, CaseJudge judge) {
        this(memory, answerer, judge, new EvidenceCollector());
    }

    public MemoryBenchmarkRunner(LongTermMemory memory, Answerer answerer, CaseJudge judge,
                                 EvidenceCollector collector) {
        this(memory, answerer, judge, collector, null);
    }

    /**
     * @param artifactDir 运行产物目录（每条用例一个 Markdown 文件）；为 null 表示不落盘
     */
    public MemoryBenchmarkRunner(LongTermMemory memory, Answerer answerer, CaseJudge judge,
                                 EvidenceCollector collector, Path artifactDir) {
        this.memory = memory;
        this.answerer = answerer;
        this.judge = judge;
        this.collector = collector == null ? new EvidenceCollector() : collector;
        this.artifactDir = artifactDir;
    }

    public EvidenceCollector collector() {
        return collector;
    }

    public BenchmarkReport run(List<MemoryCase> cases) {
        List<Judgment> judgments = new ArrayList<>();
        for (MemoryCase kase : cases) {
            judgments.add(runOne(kase));
        }
        return new BenchmarkReport(judgments);
    }

    public Judgment runOne(MemoryCase kase) {
        String sessionId = kase.sessionId();
        memory.clear(sessionId);

        List<String> transcript = new ArrayList<>();
        transcript.add("# 用例 " + kase.id() + "（" + kase.dimension().label() + "）");
        transcript.add("");

        // 1) 注入种子记忆
        transcript.add("## 注入记忆");
        String firstEntryId = null;
        for (String seed : kase.seeds()) {
            MemoryEntry entry = MemoryEntry.of(sessionId, seed, "fact",
                    MemoryEntry.DEFAULT_CREDIBILITY, kase.seedsRetainable());
            String id = memory.write(sessionId, entry);
            if (firstEntryId == null) {
                firstEntryId = id;
            }
            transcript.add("- " + seed + "（retainable=" + kase.seedsRetainable() + "）");
            collector.collect(Evidence.memory(sessionId, seed, id));
        }
        transcript.add("");

        // 2) 动态更新：新事实取代旧事实
        if (kase.updateTo() != null && !kase.updateTo().isBlank() && firstEntryId != null) {
            memory.update(sessionId, firstEntryId, MemoryEntry.fact(sessionId, kase.updateTo()));
            collector.collect(Evidence.memory(sessionId, kase.updateTo(), firstEntryId));
            transcript.add("## 更新记忆");
            transcript.add("- " + kase.updateTo());
            transcript.add("");
        }

        // 3) 提问
        collector.collect(Evidence.dialogue(sessionId, kase.question()));
        String answer = answerer.answer(sessionId, kase.question());
        collector.collect(Evidence.dialogue(sessionId, answer == null ? "" : answer));

        // 4) 判定
        Judgment judgment = judge.judge(kase, answer);
        collector.collect(Evidence.artifact(sessionId, judgment.toString(), "judgment"));

        // 5) 落盘为文件产物并登记证据
        transcript.add("## 提问");
        transcript.add("- " + kase.question());
        transcript.add("");
        transcript.add("## 智能体回答");
        transcript.add(answer == null || answer.isBlank() ? "（空回答）" : answer);
        transcript.add("");
        transcript.add("## 判定");
        transcript.add("- " + judgment);
        String path = Transcript.write(artifactDir, sessionId, transcript);
        if (path != null) {
            collector.collect(Evidence.artifact(sessionId, path, "transcript"));
        }
        return judgment;
    }
}
