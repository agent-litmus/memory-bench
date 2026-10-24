package io.github.agentlitmus.core;

import io.github.agentlitmus.llm.LlmClient;
import io.github.agentlitmus.report.BenchmarkReport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三态判定语义的行为测试。
 * <p>
 * 这组测试守的不是"功能有没有"，而是<b>口径不被悄悄改回去</b>：
 * 一旦有人把「无响应 / 评委故障」重新算成失败，前面几条立刻红。
 * 通过率的可信度完全依赖这条边界。
 */
class VerdictSemanticsTest {

    private static MemoryCase plainCase() {
        return MemoryCase.expect("x1", Dimension.RETENTION,
                List.of("用户所在的团队是星澜科技质检部"), "我所在团队叫什么", "星澜科技质检部");
    }

    private static MemoryCase llmCase() {
        return MemoryCase.llmJudged("x2", Dimension.REUSE,
                List.of("用户偏好Markdown格式的周报模板"), "帮我起草周报", "回答应体现用户偏好 Markdown 模板");
    }

    @Test
    void 空回答判为不可判而不是失败() {
        Judgment judgment = new CaseJudge().judge(plainCase(), "");

        assertEquals(Verdict.UNKNOWN, judgment.verdict());
        assertFalse(judgment.judged(), "不可判不得进入评分分母");
        assertNull(judgment.outcome(), "不可判不属于任何一种记忆能力缺陷");
        assertEquals(FailureCause.NO_RESPONSE, judgment.cause());
        assertEquals(Judgment.MODE_NO_RESPONSE, judgment.mode());
    }

    @Test
    void 全空白回答同样不可判() {
        assertEquals(Verdict.UNKNOWN, new CaseJudge().judge(plainCase(), "   \n\t ").verdict());
    }

    @Test
    void 无响应也压不住无约束用例的假通过() {
        // llmJudged 用例既无包含也无排除约束：若先跑规则，空回答会因"无规则约束"而被判通过
        Judgment judgment = new CaseJudge().judge(llmCase(), "");

        assertEquals(Verdict.UNKNOWN, judgment.verdict());
        assertEquals(Judgment.MODE_NO_RESPONSE, judgment.mode());
    }

    @Test
    void 需要评委但没有评委时不可判() {
        Judgment judgment = new CaseJudge().judge(llmCase(), "好的，按你惯用的 Markdown 模板起草如下……");

        assertEquals(Verdict.UNKNOWN, judgment.verdict());
        assertEquals(Judgment.MODE_DEGRADED, judgment.mode());
        assertNull(judgment.cause(), "判定未发生，不能替被测对象记一笔记忆缺陷");
    }

    @Test
    void 评委抛异常时不可判且规则结果不再冒充结论() {
        LlmClient broken = prompt -> {
            throw new IllegalStateException("429 Too Many Requests");
        };
        Judgment judgment = new CaseJudge(broken).judge(llmCase(), "按 Markdown 模板起草如下");

        assertEquals(Verdict.UNKNOWN, judgment.verdict());
        assertTrue(judgment.reason().contains("评委调用失败"));
    }

    @Test
    void 评委空输出时不可判() {
        Judgment judgment = new CaseJudge(prompt -> "   ").judge(llmCase(), "按 Markdown 模板起草如下");

        assertEquals(Verdict.UNKNOWN, judgment.verdict());
        assertTrue(judgment.reason().contains("评委无输出"));
    }

    @Test
    void 评委判定PASS与FAIL各自生效() {
        Judgment pass = new CaseJudge(prompt -> "PASS 体现了偏好").judge(llmCase(), "按 Markdown 模板起草如下");
        Judgment fail = new CaseJudge(prompt -> "FAIL 没有体现偏好").judge(llmCase(), "随便写一段周报");

        assertEquals(Verdict.PASS, pass.verdict());
        assertEquals(Outcome.CORRECT, pass.outcome());
        assertEquals(Verdict.FAIL, fail.verdict());
        assertEquals(Outcome.OMISSION, fail.outcome());
    }

    @Test
    void 规则不过时不再调用评委() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient counting = prompt -> {
            calls.incrementAndGet();
            return "PASS 不该被调用";
        };
        // plainCase 不需要语义判定：评委一次都不该被调用
        Judgment judgment = new CaseJudge(counting).judge(plainCase(), "我不知道");

        assertEquals(Verdict.FAIL, judgment.verdict());
        assertEquals(Outcome.OMISSION, judgment.outcome());
        assertEquals(0, calls.get(), "评委调用不应发生在规则已能判定的用例上");
    }

    @Test
    void 报告把不可判移出分母并同时公示覆盖率() {
        Judgment pass1 = Judgment.rule("a", Dimension.RETENTION, true, "命中", Outcome.CORRECT, null);
        Judgment pass2 = Judgment.rule("b", Dimension.RECALL, true, "命中", Outcome.CORRECT, null);
        Judgment fail = Judgment.rule("c", Dimension.UPDATE, false, "未命中", Outcome.WRONG_REUSE,
                FailureCause.STALE_REUSE);
        Judgment unknown = Judgment.noResponse("d", Dimension.BOUNDARY, "无有效响应");
        BenchmarkReport report = new BenchmarkReport(List.of(pass1, pass2, fail, unknown));

        assertEquals(4, report.total());
        assertEquals(3, report.judgedCount());
        assertEquals(1, report.unknownCount());
        // 旧口径会把这条无响应算成失败得到 50%；新口径是 2/3 有效分
        assertEquals(2.0 / 3.0, report.passRate(), 0.0001);
        assertEquals(0.75, report.coverage(), 0.0001);
        assertEquals(0.5, report.lowerBound(), 0.0001);
        assertEquals(List.of("d"), report.unknowns().stream().map(Judgment::caseId).toList());
    }

    @Test
    void 结果分类统计不吸收不可判() {
        BenchmarkReport report = new BenchmarkReport(List.of(
                Judgment.rule("a", Dimension.RETENTION, true, "命中", Outcome.CORRECT, null),
                Judgment.noResponse("b", Dimension.RETENTION, "无有效响应")));

        long classified = report.outcomeCounts().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(1, classified, "不可判不应出现在五类结果里");
    }

    @Test
    void 全不可判时有效分为零但不抛异常() {
        BenchmarkReport report = new BenchmarkReport(List.of(
                Judgment.noResponse("a", Dimension.RETENTION, "无有效响应")));

        assertEquals(0.0, report.passRate(), 0.0001);
        assertEquals(0.0, report.coverage(), 0.0001);
        assertTrue(report.toText().contains("不可判"));
    }

    @Test
    void 判定口径版本必须进运行契约() {
        // 口径变更必须能被看出来：改动判定语义时这个常量必须同步上调，否则历史结果会被误当成同口径可比
        assertTrue(CaseJudge.RULES_VERSION.matches("\\d{4}\\.\\d{2}-[a-z]"),
                "RULES_VERSION 需为「年.月-序号」格式，实际为 " + CaseJudge.RULES_VERSION);
    }
}
