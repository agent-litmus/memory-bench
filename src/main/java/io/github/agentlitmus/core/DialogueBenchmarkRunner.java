package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.report.BenchmarkReport;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话式评测执行器——面向<b>真实智能体</b>。
 * <p>
 * 与 {@link MemoryBenchmarkRunner} 的区别在于「记忆如何建立」：
 * <ul>
 *   <li>{@link MemoryBenchmarkRunner}：直接把事实写入被测对象背后的记忆存储，适合评测记忆系统本身；</li>
 *   <li>本执行器：把事实<b>当作用户说的话</b>发给智能体，由它自己在对话中记住——
 *       这正是真实智能体建立记忆的方式，外部无法（也不应）代劳。</li>
 * </ul>
 * 因此本执行器<b>不依赖任何记忆实现</b>，只要被测对象能对话即可评测，
 * 可用于 HTTP 服务、命令行智能体、任意 LLM 应用。
 */
public class DialogueBenchmarkRunner {

    private final Answerer agent;
    private final CaseJudge judge;
    private final EvidenceCollector collector;

    public DialogueBenchmarkRunner(Answerer agent, CaseJudge judge) {
        this(agent, judge, new EvidenceCollector());
    }

    public DialogueBenchmarkRunner(Answerer agent, CaseJudge judge, EvidenceCollector collector) {
        this.agent = agent;
        this.judge = judge;
        this.collector = collector == null ? new EvidenceCollector() : collector;
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

        // 1) 通过对话注入事实（用户先把信息告诉智能体）
        for (String seed : kase.seeds()) {
            collector.collect(Evidence.dialogue(sessionId, seed));
            agent.answer(sessionId, seed);
        }

        // 2) 动态更新：告知新事实，看它是否覆盖旧信息
        if (kase.updateTo() != null && !kase.updateTo().isBlank()) {
            collector.collect(Evidence.dialogue(sessionId, kase.updateTo()));
            agent.answer(sessionId, kase.updateTo());
        }

        // 2.5) 跨会话：重置对话上下文（长期记忆保留），确保答对只能来自长期记忆
        if (kase.crossSession()) {
            collector.collect(Evidence.dialogue(sessionId,
                    "[评测] 重置会话上下文：清空当前对话，仅保留长期记忆"));
            agent.resetSession(sessionId);
        }

        // 3) 提问并判定
        collector.collect(Evidence.dialogue(sessionId, kase.question()));
        String answer = agent.answer(sessionId, kase.question());
        collector.collect(Evidence.dialogue(sessionId, answer == null ? "" : answer));

        Judgment judgment = judge.judge(kase, answer);
        collector.collect(Evidence.artifact(sessionId, judgment.toString(), "judgment"));
        return judgment;
    }
}
