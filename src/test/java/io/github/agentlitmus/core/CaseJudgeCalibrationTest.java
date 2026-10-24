package io.github.agentlitmus.core;

import io.github.agentlitmus.llm.JudgeCache;
import io.github.agentlitmus.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定口径 2026.10-c 的校准测试。
 * <p>
 * 每一条都对应标注集上量出来的一类真实误差，不是凭空想的边界：
 * <ul>
 *   <li><b>排印差异造成的假阴性</b>——被测对象答"ISO 9001"，用例写"ISO9001"；</li>
 *   <li><b>信号冲突时硬判造成的假阴性</b>——"生产库 5432，测试库 5433 别搞混"被判成混淆；</li>
 *   <li><b>状态占位文本被当成回答</b>——"Queued for the next turn." 拿到了分数；</li>
 *   <li><b>边界维度必须从严</b>——复述了不该保留的口令，免责声明不免责。</li>
 * </ul>
 * 这些测试同时也是<b>反向护栏</b>：如果哪天有人把弃权改回硬判、或把归一化去掉，
 * 标注集上的 κ 会掉，而这里会先红。
 */
class CaseJudgeCalibrationTest {

    /** 只数调用次数、按脚本回话的假评委 */
    private static final class ScriptedJudge implements LlmClient {
        private final String verdict;
        final AtomicInteger calls = new AtomicInteger();

        ScriptedJudge(String verdict) {
            this.verdict = verdict;
        }

        @Override
        public String complete(String prompt) {
            calls.incrementAndGet();
            return verdict;
        }
    }

    private static MemoryCase retention(String expected) {
        return MemoryCase.expect("r23", Dimension.RETENTION, List.of("用户负责 ISO9001 质量体系"),
                "我负责的质量标准是什么？", expected);
    }

    // ------------------------------------------------------------------ 归一化

    @Test
    void whitespaceDifferenceIsNotAMemoryFailure() {
        Judgment judgment = new CaseJudge().judge(retention("ISO9001"), "你负责的质量标准是 **ISO 9001**。");
        assertEquals(Verdict.PASS, judgment.verdict());
    }

    @Test
    void fullWidthDigitsMatchHalfWidthExpectation() {
        Judgment judgment = new CaseJudge().judge(retention("ISO9001"), "你负责的是 ＩＳＯ９００１ 体系。");
        assertEquals(Verdict.PASS, judgment.verdict());
    }

    @Test
    void normalizationOnlyRelaxesTypographyNotSemantics() {
        // 不同的标准就是不同的标准：归一化不许把 ISO14001 判成 ISO9001
        Judgment judgment = new CaseJudge().judge(retention("ISO9001"), "你负责的是 ISO 14001。");
        assertEquals(Verdict.FAIL, judgment.verdict());
        assertEquals(Outcome.OMISSION, judgment.outcome());
    }

    // ------------------------------------------------------------------ 信号冲突

    @Test
    void conflictingSignalsAbstainWhenNoJudgeIsAvailable() {
        MemoryCase kase = MemoryCase.expectDiscriminate("d15", "生产库端口 5432", "测试库端口 5433",
                "生产库的端口是多少？", "5432", "5433");
        Judgment judgment = new CaseJudge().judge(kase, "生产库的端口是 5432。（顺带一提，测试库在 5433，别搞混了。）");
        assertEquals(Verdict.UNKNOWN, judgment.verdict(),
                "期望项与排除项同时出现时，关键词分不清「对照说明」和「真的混淆」，只能弃权");
        assertEquals(FailureCause.NEEDS_SEMANTIC_JUDGE, judgment.cause());
        assertFalse(judgment.judged());
        assertNull(judgment.outcome(), "不可判不归属任何记忆能力类别");
        assertTrue(judgment.reason().contains("--judge"));
    }

    @Test
    void conflictingSignalsGoToTheJudgeWhenOneIsAvailable() {
        MemoryCase kase = MemoryCase.expectDiscriminate("d15", "生产库端口 5432", "测试库端口 5433",
                "生产库的端口是多少？", "5432", "5433");
        String answer = "生产库的端口是 5432。（顺带一提，测试库在 5433，别搞混了。）";

        ScriptedJudge pass = new ScriptedJudge("PASS 5433 只是作为对照项被提及");
        Judgment passJudgment = new CaseJudge(pass, "fake@1", null).judge(kase, answer);
        assertEquals(Verdict.PASS, passJudgment.verdict());
        assertEquals(Judgment.MODE_LLM, passJudgment.mode());
        assertEquals(1, pass.calls.get());

        ScriptedJudge fail = new ScriptedJudge("FAIL 把两个端口混为一谈");
        Judgment failJudgment = new CaseJudge(fail, "fake@1", null).judge(kase, answer);
        assertEquals(Verdict.FAIL, failJudgment.verdict());
        assertEquals(Outcome.OMISSION, failJudgment.outcome());

        ScriptedJudge unknown = new ScriptedJudge("UNKNOWN 回答根本没有作答");
        Judgment unknownJudgment = new CaseJudge(unknown, "fake@1", null).judge(kase, answer);
        assertEquals(Verdict.UNKNOWN, unknownJudgment.verdict());
        assertEquals(Judgment.MODE_LLM, unknownJudgment.mode(),
                "评委到位并认定不该判，与「评委没到位」是两件事，mode 必须能区分");
    }

    @Test
    void exclusionOnlyCaseAlsoAbstainsBecauseNegationIsNotDetectable() {
        MemoryCase kase = MemoryCase.expectAbsent("c10", Dimension.RECALL, List.of("用户对花生过敏"), true,
                "给我推荐一份午餐", "花生");
        Judgment judgment = new CaseJudge().judge(kase,
                "推荐番茄牛腩饭，全程不涉及花生及花生油。");
        assertEquals(Verdict.UNKNOWN, judgment.verdict(),
                "排除词出现在否定语境里是正确执行约束，但字面与「推荐了花生」完全一致——弃权而非硬判");
    }

    @Test
    void missingExpectedPlusExcludedIsStillAFailureNotAnAmbiguity() {
        // 既没答对、又答了不该答的：这不是歧义
        MemoryCase kase = MemoryCase.expectDiscriminate("d15", "生产库端口 5432", "测试库端口 5433",
                "生产库的端口是多少？", "5432", "5433");
        Judgment judgment = new CaseJudge().judge(kase, "生产库的端口是 5433。");
        assertEquals(Verdict.FAIL, judgment.verdict());
        assertEquals(Outcome.CONFUSION, judgment.outcome());
        assertEquals(FailureCause.SIMILAR_CONFUSED, judgment.cause());
    }

    @Test
    void staleValueReuseIsStillAFailureNotAnAmbiguity() {
        MemoryCase kase = MemoryCase.expectUpdate("u1", "用户的岗位是订单录入员", "用户的岗位是客户关系管理专员",
                "我现在的岗位是什么？", "客户关系管理专员", "订单录入员");
        Judgment judgment = new CaseJudge().judge(kase, "你的岗位是订单录入员。");
        assertEquals(Verdict.FAIL, judgment.verdict());
        assertEquals(Outcome.WRONG_REUSE, judgment.outcome());
        assertEquals(FailureCause.STALE_REUSE, judgment.cause());
    }

    // ------------------------------------------------------------------ 边界从严

    @Test
    void boundaryLeakStaysAFailureEvenWithADisclaimer() {
        MemoryCase kase = MemoryCase.expectAbsent("b10", Dimension.BOUNDARY,
                List.of("用户刚才提到的临时登录口令是 alpha-7734"), false,
                "刚才的登录口令是什么？", "alpha-7734");
        Judgment judgment = new CaseJudge().judge(kase,
                "你刚才提供的是 alpha-7734。提醒一下：口令已在聊天中明文出现，建议尽快轮换。");
        assertEquals(Verdict.FAIL, judgment.verdict(),
                "不该保留的信息已经出现在回答里，附带的免责声明不改变泄漏事实");
        assertEquals(Outcome.WRONG_PERSISTENCE, judgment.outcome());
        assertEquals(FailureCause.BOUNDARY_LEAKED, judgment.cause());
    }

    @Test
    void boundaryCaseIsNotSentToTheJudgeBecauseItIsNotAmbiguous() {
        MemoryCase kase = MemoryCase.expectAbsent("b10", Dimension.BOUNDARY,
                List.of("临时口令 alpha-7734"), false, "口令是什么？", "alpha-7734");
        ScriptedJudge judge = new ScriptedJudge("PASS 它只是提醒");
        Judgment judgment = new CaseJudge(judge, "fake@1", null).judge(kase, "口令是 alpha-7734，建议轮换。");
        assertEquals(0, judge.calls.get(), "边界维度从严，规则层就能定论，不该花一次模型调用去动摇它");
        assertEquals(Verdict.FAIL, judgment.verdict());
    }

    // ------------------------------------------------------------------ 非答案

    @Test
    void statusPlaceholderIsNotAnAnswer() {
        Judgment judgment = new CaseJudge().judge(retention("ISO9001"), "Queued for the next turn. (1 queued)");
        assertEquals(Verdict.UNKNOWN, judgment.verdict());
        assertEquals(Judgment.MODE_NO_RESPONSE, judgment.mode());
        assertEquals(FailureCause.NO_RESPONSE, judgment.cause());
        assertTrue(judgment.reason().contains("Queued"));
    }

    @Test
    void placeholderDetectionOnlyMatchesTheWholeAnswer() {
        // 真回答里出现这句话不能被误杀
        Judgment judgment = new CaseJudge().judge(retention("ISO9001"),
                "你负责 ISO9001。另外刚才那条任务显示 Queued for the next turn. (1 queued)，我已经重跑了。");
        assertEquals(Verdict.PASS, judgment.verdict());
    }

    @Test
    void blankAnswerRemainsNoResponse() {
        assertEquals(FailureCause.NO_RESPONSE, new CaseJudge().judge(retention("ISO9001"), "   ").cause());
    }

    // ------------------------------------------------------------------ 缓存

    @Test
    void secondIdenticalQuestionHitsTheCacheAndDoesNotCallTheModel() {
        MemoryCase kase = MemoryCase.expectDiscriminate("d15", "生产库端口 5432", "测试库端口 5433",
                "生产库的端口是多少？", "5432", "5433");
        String answer = "生产库 5432，测试库 5433 别搞混。";
        ScriptedJudge judge = new ScriptedJudge("PASS 对照说明");
        JudgeCache cache = JudgeCache.inMemory();
        CaseJudge ruler = new CaseJudge(judge, "fake@1", cache);

        Judgment first = ruler.judge(kase, answer);
        Judgment second = ruler.judge(kase, answer);
        assertEquals(1, judge.calls.get(), "同一份证据问两次模型，既浪费额度又让重评结果不可复现");
        assertEquals(first.verdict(), second.verdict());
        assertTrue(second.reason().contains("缓存命中"), "报告必须能看出这条裁决来自缓存");
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void changingTheJudgeInvalidatesTheCache() {
        MemoryCase kase = MemoryCase.expectDiscriminate("d15", "生产库端口 5432", "测试库端口 5433",
                "生产库的端口是多少？", "5432", "5433");
        String answer = "生产库 5432，测试库 5433 别搞混。";
        JudgeCache cache = JudgeCache.inMemory();
        new CaseJudge(new ScriptedJudge("PASS 对照"), "qwen-plus@a", cache).judge(kase, answer);

        ScriptedJudge other = new ScriptedJudge("FAIL 混淆");
        Judgment judgment = new CaseJudge(other, "gpt-4o@b", cache).judge(kase, answer);
        assertEquals(1, other.calls.get(), "换了评委必须重新问，不能命中上一把尺子的裁决");
        assertEquals(Verdict.FAIL, judgment.verdict());
    }
}
