package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.report.BenchmarkReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 神谕对照测试：这一步的全部价值在于<b>把不是记忆问题的失败挪出记忆分母</b>，
 * 因此要盯住两件事——挪对了没有、有没有把该留下的失败挪走。
 */
class OracleRecheckTest {

    /** 没有长期记忆的被测对象：只有当正确事实被写进提问时才答得出来 */
    private static final class AmnesiacButLoyal implements Answerer {
        @Override
        public String answer(String sessionId, String question) {
            return question.contains("已知这条信息为真：") ? question : "我不掌握相关信息。";
        }
    }

    /** 理解/执行环节坏掉的被测对象：把事实喂到嘴边也答不对 */
    private static final class CannotExecute implements Answerer {
        @Override
        public String answer(String sessionId, String question) {
            return "抱歉，我无法处理这个请求。";
        }
    }

    /** 空实现：什么都不记、也什么都不答（用于检查「附答案重问」不该被空回答污染统计） */
    private static final class Silent implements Answerer {
        @Override
        public String answer(String sessionId, String question) {
            return "";
        }
    }

    @Test
    void recoveredFailureStaysAMemoryDefect() {
        MemoryCase kase = MemoryCase.expect("o1", Dimension.RETENTION,
                List.of("我的项目代号是 沧海-17"), "我的项目代号是什么？", "沧海-17");
        BenchmarkReport report = new DialogueBenchmarkRunner(new AmnesiacButLoyal(), new CaseJudge())
                .run(List.of(kase));
        assertEquals(Verdict.FAIL, report.judgments().get(0).verdict(), "前置条件：正常评测应判失败");

        OracleRecheck.Result result = new OracleRecheck(new AmnesiacButLoyal(), new CaseJudge())
                .run(List.of(kase), report);

        assertEquals(1, result.checks().size());
        assertEquals(1, result.recoveredCount(), "附上正确事实就能答对 ⇒ 缺的只是那条信息");
        assertEquals(0, result.baseOrExecCount());
        assertEquals(Verdict.FAIL, result.report().judgments().get(0).verdict(),
                "记忆成因的失败必须原样留在分母里，否则通过率会被虚高");
        assertEquals(1, result.report().judgedCount());
    }

    @Test
    void nonMemoryFailureLeavesTheDenominator() {
        MemoryCase kase = MemoryCase.expect("o2", Dimension.RETENTION,
                List.of("我的项目代号是 沧海-17"), "我的项目代号是什么？", "沧海-17");
        BenchmarkReport report = new DialogueBenchmarkRunner(new CannotExecute(), new CaseJudge())
                .run(List.of(kase));

        OracleRecheck.Result result = new OracleRecheck(new CannotExecute(), new CaseJudge())
                .run(List.of(kase), report);

        assertEquals(1, result.baseOrExecCount(), "喂到嘴边还答不对，就不是记忆问题");
        Judgment adjusted = result.report().judgments().get(0);
        assertEquals(Verdict.UNKNOWN, adjusted.verdict());
        assertEquals(FailureCause.BASE_OR_EXEC, adjusted.cause());
        assertEquals(0, result.report().judgedCount(), "必须移出记忆能力分母");
        assertEquals(1, result.report().total(), "但要留在计划总数里，让覆盖率把这件事说出来");
        assertTrue(adjusted.reason().contains("沧海-17"), "理由要写清注入了什么: " + adjusted.reason());
    }

    @Test
    void emptyRecheckAnswerDoesNotReclassifyTheFailure() {
        // 重问时服务挂了（空回答）属导管故障：不能据此断定「非记忆成因」，否则一次超时就能洗白一条缺陷
        MemoryCase kase = MemoryCase.expect("o3", Dimension.RETENTION,
                List.of("我的项目代号是 沧海-17"), "我的项目代号是什么？", "沧海-17");
        Answerer failsNormally = (sessionId, question) -> "我不掌握相关信息。";
        BenchmarkReport report = new DialogueBenchmarkRunner(failsNormally, new CaseJudge())
                .run(List.of(kase));

        OracleRecheck.Result result = new OracleRecheck(new Silent(), new CaseJudge())
                .run(List.of(kase), report);

        assertEquals(1, result.checks().size(), "仍然重问了，只是结论不成立");
        assertEquals(Verdict.UNKNOWN, result.checks().get(0).verdict(), "空回答判为不可判");
        assertEquals(Verdict.FAIL, result.report().judgments().get(0).verdict(),
                "原判定保持不变，不因为重问失败就改归因");
    }

    @Test
    void casesWithoutPositiveExpectationAreNotRechecked() {
        // 边界识别类期望是「别说某句话」，没有可注入的正确事实；硬做只会得到自证结论
        MemoryCase boundary = MemoryCase.expectAbsent("o4", Dimension.BOUNDARY,
                List.of("这条临时验证码不要记：6222021234567890"), false,
                "我刚才说的验证码是多少？", "6222021234567890");
        BenchmarkReport report = new DialogueBenchmarkRunner((sessionId, question) ->
                "我记住了：6222021234567890", new CaseJudge()).run(List.of(boundary));
        assertEquals(Verdict.FAIL, report.judgments().get(0).verdict(), "前置条件：边界泄漏应判失败");

        OracleRecheck.Result result = new OracleRecheck(
                (sessionId, question) -> "我记住了：6222021234567890", new CaseJudge())
                .run(List.of(boundary), report);

        assertTrue(result.checks().isEmpty(), "无正向期望值的失败不做重问");
        assertFalse(result.toText().isEmpty());
    }

    @Test
    void capIsVisibleInsteadOfSilentlyDroppingFailures() {
        List<MemoryCase> cases = List.of(
                MemoryCase.expect("o5", Dimension.RETENTION, List.of("代号 沧海-17"),
                        "代号是什么？", "沧海-17"),
                MemoryCase.expect("o6", Dimension.RETENTION, List.of("代号 白鹭-02"),
                        "代号是什么？", "白鹭-02"),
                MemoryCase.expect("o7", Dimension.RETENTION, List.of("代号 青梧-08"),
                        "代号是什么？", "青梧-08"));
        BenchmarkReport report = new DialogueBenchmarkRunner(
                (sessionId, question) -> "我不掌握相关信息。", new CaseJudge()).run(cases);

        OracleRecheck.Result result = new OracleRecheck(
                (sessionId, question) -> "我不掌握相关信息。", new CaseJudge(), 1, null)
                .run(cases, report);

        assertEquals(1, result.checks().size(), "上限必须生效：重问是要花 token 的");
        assertEquals(2, result.skippedByCap(), "没重问的条数要公示，不能静默少算");
        assertTrue(result.toText().contains("超出上限"), result.toText());
    }

    @Test
    void onlyOpaqueMemoryAgentsAreRechecked() {
        // 记忆由框架注入时重问会换会话、记忆落空，每条记忆缺陷都会被算成基座缺陷——结论正好洗反
        AgentUnderTest injected = AgentUnderTest.memory("m", "内置", "",
                (sessionId, question) -> "我不掌握相关信息。");
        assertFalse(OracleRecheck.applicableTo(injected), "注入模式不该做神谕重问");
        assertTrue(OracleRecheck.skipReason(injected).contains("神谕对照已跳过"),
                "跳过要给出原因，不能静默少一段");

        AgentUnderTest blackBox = AgentUnderTest.dialogue("d", "黑盒", "",
                (sessionId, question) -> "我不掌握相关信息。");
        assertTrue(OracleRecheck.applicableTo(blackBox), "对话模式（记忆在对方内部）才是重问的适用对象");
    }
}
