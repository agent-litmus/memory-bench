package io.github.agentlitmus.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数解析的统一规则：带值参数恰好消费下一个 token，布尔开关不消费任何 token。
 * <p>
 * 旧版解析对 {@code --cases} 与 {@code --agents} 用了两套取值方式，缺值时会把
 * {@code "--cases"} 自己当成文件名、或把下一个开关当成值——这些边界现在必须当场报错。
 */
class CliOptionsTest {

    @Test
    void valueFlagsConsumeExactlyOneToken() {
        CliOptions o = CliOptions.parse(List.of(
                "--agents", "reference,degraded",
                "--suite", "full",
                "--out", "out/x",
                "--from", "a", "--to", "b"));
        assertEquals("reference,degraded", o.agents);
        assertEquals("full", o.suite);
        assertEquals("out/x", o.out);
        assertTrue(o.outExplicit);
        assertEquals("a", o.from);
        assertEquals("b", o.to);
        assertTrue(o.positional.isEmpty(), "开关的值不能落进位置参数");
    }

    @Test
    void booleanFlagsDoNotSwallowFollowingFlags() {
        CliOptions o = CliOptions.parse(List.of("--with-control", "--judge", "--suite", "basic"));
        assertTrue(o.withControl);
        assertTrue(o.judge);
        assertFalse(o.withOracle);
        assertEquals("basic", o.suite);
    }

    @Test
    void missingValueIsAnImmediateErrorNotASilentWrongDefault() {
        // 旧版会把 "--cases" 自己当成文件名，运行期才以「读不到文件」炸出——现在当场报缺值
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(List.of("--cases")));
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(List.of("--agents", "--suite", "basic")));
    }

    @Test
    void positionalArgsAreCollectedInOrder() {
        CliOptions o = CliOptions.parse(List.of("out/run-a", "out/run-b", "--out", "board"));
        assertEquals(List.of("out/run-a", "out/run-b"), o.positional);
        assertEquals("board", o.out);
    }

    @Test
    void unknownFlagsAreIgnoredInsteadOfAbortingALongRun() {
        CliOptions o = CliOptions.parse(List.of("--frobnicate", "--suite", "basic"));
        assertEquals("basic", o.suite);
        assertTrue(o.positional.isEmpty());
    }

    @Test
    void invalidIntegersKeepDefaultsWithAWarning() {
        CliOptions o = CliOptions.parse(List.of("--repeat", "abc", "--parallel", "-1x"));
        assertEquals(1, o.repeat);
        assertEquals(1, o.parallel);
    }

    @Test
    void negativeButValidIntegersAreAccepted() {
        CliOptions o = CliOptions.parse(List.of("--parallel", "8", "--repeat", "3"));
        assertEquals(8, o.parallel);
        assertEquals(3, o.repeat);
    }

    @Test
    void commaSeparatedJudgeModelsSurviveParsingUntouched() {
        CliOptions o = CliOptions.parse(List.of("--judge", "--judge-model", "qwen-plus,deepseek-chat"));
        assertTrue(o.judge);
        assertEquals("qwen-plus,deepseek-chat", o.judgeModel);
        assertEquals(List.of("qwen-plus", "deepseek-chat"), Runs.splitList(o.judgeModel));
    }

    @Test
    void splitListTrimsAndDropsEmpties() {
        assertEquals(List.of("a", "b"), Runs.splitList(" a , b ,"));
        assertEquals(List.of(), Runs.splitList(null));
        assertEquals(List.of(), Runs.splitList(" ,, "));
    }

    @Test
    void agentDefaultIsBuiltinPair() {
        CliOptions o = CliOptions.parse(List.of());
        assertEquals("reference,degraded", o.agents);
        assertEquals(CliOptions.DEFAULT_OUT, o.out);
        assertFalse(o.outExplicit);
    }
}
