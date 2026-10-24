package io.github.agentlitmus.core;

import io.github.agentlitmus.llm.JudgeCache;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评委团的多数决聚合。
 *
 * <p>被测的是聚合语义，不是评委本身：<b>严格多数</b>（&gt; 半数）才判 PASS/FAIL；
 * 平票、「不可判」占多数、输出不符合约定，都记不可判——评委团的分歧是信息，
 * 抹平它等于花多份钱得到一份瞎猜。同时验证多位评委的缓存互不串味。
 */
class CaseJudgePanelTest {

    /** 需要 LLM 语义判定的用例：规则层放行，裁决完全交给评委 */
    private static MemoryCase llmCase() {
        return MemoryCase.llmJudged("p1", Dimension.RETENTION,
                List.of("用户偏好用 Markdown 写周报"), "帮我起草这周周报", "回答是否体现 Markdown 偏好");
    }

    private static CaseJudge.Judge judge(String id, String output, AtomicInteger calls) {
        return new CaseJudge.Judge(prompt -> {
            calls.incrementAndGet();
            return output;
        }, id);
    }

    @Test
    void strictMajorityPassWins() {
        AtomicInteger calls = new AtomicInteger();
        CaseJudge panel = new CaseJudge(List.of(
                judge("a@1", "PASS 体现了偏好", calls),
                judge("b@2", "FAIL 没体现", calls),
                judge("c@3", "PASS 体现了偏好", calls)), null);
        Judgment judgment = panel.judge(llmCase(), "按 Markdown 模板起草如下……");
        assertEquals(Verdict.PASS, judgment.verdict());
        assertEquals(Outcome.CORRECT, judgment.outcome());
        assertTrue(judgment.reason().contains("多数决"), judgment.reason());
        assertTrue(judgment.reason().contains("PASS 2"), judgment.reason());
    }

    @Test
    void tieAbstainsInsteadOfGuessing() {
        AtomicInteger calls = new AtomicInteger();
        CaseJudge panel = new CaseJudge(List.of(
                judge("a@1", "PASS", calls),
                judge("b@2", "FAIL", calls)), null);
        assertEquals(Verdict.UNKNOWN, panel.judge(llmCase(), "按 Markdown 模板起草如下……").verdict());
    }

    @Test
    void unknownPluralityIsUnknownEvenWhenOthersAgree() {
        AtomicInteger calls = new AtomicInteger();
        CaseJudge panel = new CaseJudge(List.of(
                judge("a@1", "PASS", calls),
                judge("b@2", "UNKNOWN 回答未作答", calls),
                judge("c@3", "UNKNOWN 回答未作答", calls)), null);
        assertEquals(Verdict.UNKNOWN, panel.judge(llmCase(), "按 Markdown 模板起草如下……").verdict());
    }

    @Test
    void nonConformingOutputCountsAsAbstention() {
        AtomicInteger calls = new AtomicInteger();
        CaseJudge panel = new CaseJudge(List.of(
                judge("a@1", "我认为可以", calls),
                judge("b@2", "PASS", calls),
                judge("c@3", "PASS", calls)), null);
        // 一位输出不符合约定按弃权计：PASS 2 / 3 仍是严格多数，判过
        assertEquals(Verdict.PASS, panel.judge(llmCase(), "按 Markdown 模板起草如下……").verdict());
    }

    @Test
    void allJudgesBrokenMeansJudgmentDidNotHappen() {
        AtomicInteger calls = new AtomicInteger();
        CaseJudge panel = new CaseJudge(List.of(
                judge("a@1", null, calls),
                judge("b@2", null, calls)), null);
        Judgment judgment = panel.judge(llmCase(), "按 Markdown 模板起草如下……");
        assertEquals(Verdict.UNKNOWN, judgment.verdict());
        assertTrue(judgment.reason().contains("未发生判定"), judgment.reason());
    }

    @Test
    void panelFingerprintJoinsAllJudgeIds() {
        CaseJudge panel = new CaseJudge(List.of(
                judge("a@1", "PASS", new AtomicInteger()),
                judge("b@2", "PASS", new AtomicInteger())), null);
        assertEquals("a@1|b@2", panel.judgeFingerprint());
        assertTrue(panel.llmAvailable());
        assertNull(new CaseJudge().judgeFingerprint(), "无评委时指纹应为 null");
        assertFalse(new CaseJudge().llmAvailable());
    }

    /**
     * 缓存按评委各自键控：同一份回答第二次判定时两位评委都应命中缓存（零新请求），
     * 且换评委指纹不串味——否则「评委团」退化成「一位评委 + 它的复读机」。
     */
    @Test
    void cacheIsKeyedPerJudge() {
        AtomicInteger callsA = new AtomicInteger();
        AtomicInteger callsB = new AtomicInteger();
        JudgeCache cache = JudgeCache.inMemory();
        MemoryCase kase = llmCase();
        CaseJudge panel = new CaseJudge(List.of(
                judge("a@1", "PASS 体现了偏好", callsA),
                judge("b@2", "PASS 同意", callsB)), cache);

        Judgment first = panel.judge(kase, "按 Markdown 模板起草如下……");
        Judgment second = panel.judge(kase, "按 Markdown 模板起草如下……");

        assertEquals(Verdict.PASS, first.verdict());
        assertEquals(Verdict.PASS, second.verdict());
        assertEquals(first.verdict(), second.verdict());
        assertTrue(second.reason().contains("缓存"), "第二次应标注缓存命中: " + second.reason());
        assertEquals(1, callsA.get(), "评委 a 第二次必须命中缓存");
        assertEquals(1, callsB.get(), "评委 b 第二次必须命中缓存");

        // 换一位评委（新指纹）：不得命中旧裁决
        AtomicInteger callsC = new AtomicInteger();
        CaseJudge other = new CaseJudge(List.of(judge("c@9", "FAIL 不同意", callsC)), cache);
        assertEquals(Verdict.FAIL, other.judge(kase, "按 Markdown 模板起草如下……").verdict());
        assertEquals(1, callsC.get());
    }
}
