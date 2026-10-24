package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.report.BenchmarkReport;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力门禁测试：测不了的平台不该被判失败，而该被移出分母并公示原因。
 * <p>
 * 这是本项目最容易被写错的一处语义——「做不到」和「没做到」在数字上必须不同：
 * 前者是评测边界（不可判），后者才是被测对象的记忆缺陷（失败）。
 */
class CapabilityGatingTest {

    /**
     * 带「上下文窗口 + 长期记忆」两层存储的被测对象。
     * <p>
     * 陈述句（不以问号结尾）同时写入两层；提问则先把两层内容原样吐出来——
     * 这样「重置后还答得出」唯一可能的来源就是长期记忆，正是跨会话题要观测的事。
     */
    private static final class ContextWindowAnswerer implements Answerer {
        private final Map<String, StringBuilder> windows = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, StringBuilder> longTerm = new java.util.concurrent.ConcurrentHashMap<>();
        private final Set<AgentCapability> capabilities;

        ContextWindowAnswerer(Set<AgentCapability> capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public String answer(String sessionId, String question) {
            StringBuilder window = windows.computeIfAbsent(sessionId, k -> new StringBuilder());
            StringBuilder memory = longTerm.computeIfAbsent(sessionId, k -> new StringBuilder());
            if (question.endsWith("？") || question.endsWith("?")) {
                return window + " " + memory;
            }
            window.append(question).append(' ');
            memory.append(question).append(' ');
            return "好的";
        }

        @Override
        public void resetSession(String sessionId) {
            if (capabilities.contains(AgentCapability.RESETS_CONTEXT)) {
                windows.remove(sessionId);
            }
        }

        @Override
        public Set<AgentCapability> capabilities() {
            return capabilities;
        }
    }

    @Test
    void crossSessionCaseIsUnknownWhenContextCannotBeReset() {
        // 复位不了会话：跨会话题答对只可能是靠窗口，判它通过是虚高、判它失败是冤枉
        Answerer noReset = new ContextWindowAnswerer(EnumSet.noneOf(AgentCapability.class));
        DialogueBenchmarkRunner runner = new DialogueBenchmarkRunner(noReset, new CaseJudge());
        MemoryCase cross = MemoryCase.expectCrossSession("g1", Dimension.RETENTION,
                List.of("我的项目代号是 沧海-17"), "我的项目代号是什么？", "沧海-17");

        BenchmarkReport report = runner.run(List.of(cross));
        Judgment judgment = report.judgments().get(0);

        assertEquals(Verdict.UNKNOWN, judgment.verdict(), "能力不足应判不可判，不是失败");
        assertEquals(FailureCause.NOT_OBSERVABLE, judgment.cause());
        assertEquals(0, report.judgedCount(), "不可判不进入分母");
        assertEquals(1, report.total(), "但必须进入计划总数，覆盖率才能暴露这件事");
        assertEquals(0.0, report.coverage(), 0.0001, "覆盖率归零，报告首屏就能看到证据强度");
        assertTrue(judgment.reason().contains("RESETS_CONTEXT") || judgment.reason().contains("可重置会话"),
                "理由必须写清缺哪项能力: " + judgment.reason());
    }

    @Test
    void sameCaseRunsWhenCapabilityDeclared() {
        Answerer withReset = new ContextWindowAnswerer(EnumSet.of(AgentCapability.RESETS_CONTEXT));
        DialogueBenchmarkRunner runner = new DialogueBenchmarkRunner(withReset, new CaseJudge());
        MemoryCase cross = MemoryCase.expectCrossSession("g2", Dimension.RETENTION,
                List.of("我的项目代号是 沧海-17"), "我的项目代号是什么？", "沧海-17");

        BenchmarkReport report = runner.run(List.of(cross));

        assertEquals(1, report.judgedCount(), "能复位会话后这道题就是有效判定");
        assertEquals(Verdict.PASS, report.judgments().get(0).verdict(),
                "重置后的窗口里只剩注入过的事实，答对只能来自记忆");
    }

    @Test
    void ordinaryCasesNeedNoCapability() {
        // 非跨会话题只要有对话接口就能测：门禁不能顺手把整份题集移出分母
        Answerer plain = new ContextWindowAnswerer(EnumSet.noneOf(AgentCapability.class));
        DialogueBenchmarkRunner runner = new DialogueBenchmarkRunner(plain, new CaseJudge());
        MemoryCase normal = MemoryCase.expect("g3", Dimension.RETENTION,
                List.of("我的项目代号是 沧海-17"), "我的项目代号是什么？", "沧海-17");

        BenchmarkReport report = runner.run(List.of(normal));

        assertEquals(1, report.judgedCount());
        assertEquals(1, report.passedCount(), "上下文里能看到注入原文，判通过");
    }
}
