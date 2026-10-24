package io.github.agentlitmus.stats;

import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.FailureCause;
import io.github.agentlitmus.core.Judgment;
import io.github.agentlitmus.core.Outcome;
import io.github.agentlitmus.core.Verdict;
import io.github.agentlitmus.report.BenchmarkReport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计层的锁死测试：这一块的正确性无法靠"看着像对"来保证，必须钉住四个不变量。
 * <ol>
 *   <li>bootstrap 用固定种子 ⇒ 同输入逐字节同输出（否则第三方无法复现区间）；</li>
 *   <li>不可判<b>不进分母</b>，也不能被当成失败；</li>
 *   <li>全过/全败的题必须被标记为无区分力，但<b>不被删除</b>；</li>
 *   <li>两份完全相同的报告比较，结论必须是"未见显著差异"，不能因为小数点而判优。</li>
 * </ol>
 */
class StatsTest {

    private static Judgment of(String caseId, Verdict verdict) {
        return switch (verdict) {
            case PASS -> Judgment.rule(caseId, Dimension.RETENTION, Verdict.PASS, "命中",
                    Outcome.CORRECT, null);
            case FAIL -> Judgment.rule(caseId, Dimension.RETENTION, Verdict.FAIL, "未命中",
                    Outcome.OMISSION, FailureCause.RECALL_MISSING);
            case UNKNOWN -> Judgment.noResponse(caseId, Dimension.RETENTION, "被测无响应");
        };
    }

    private static BenchmarkReport twoCases(Verdict a, Verdict b) {
        return new BenchmarkReport(List.of(of("c1", a), of("c2", b)));
    }

    @Test
    void bootstrapIsReproducible() {
        PairedCompare.Result first = PairedCompare.of(twoCases(Verdict.PASS, Verdict.FAIL),
                twoCases(Verdict.PASS, Verdict.PASS));
        PairedCompare.Result second = PairedCompare.of(twoCases(Verdict.PASS, Verdict.FAIL),
                twoCases(Verdict.PASS, Verdict.PASS));

        assertEquals(first.ciLow(), second.ciLow(), 1e-12);
        assertEquals(first.ciHigh(), second.ciHigh(), 1e-12);
        assertEquals(PairedCompare.toText("A", "B", first), PairedCompare.toText("A", "B", second));
    }

    @Test
    void identicalReportsAreNotJudgedAsDifference() {
        BenchmarkReport a = twoCases(Verdict.PASS, Verdict.FAIL);
        BenchmarkReport b = twoCases(Verdict.PASS, Verdict.FAIL);
        PairedCompare.Result result = PairedCompare.of(a, b);

        assertEquals(0.0, result.delta(), 1e-9);
        assertFalse(result.significant(), "同一份结果不可能显著");
        assertTrue(result.conclusion().contains("未见显著差异"), result.conclusion());
    }

    @Test
    void unknownIsExcludedFromPairing() {
        // c1 只有 A 判定成功，B 不可判 ⇒ 这道题必须整条退出配对，而不是算 A 赢
        BenchmarkReport a = new BenchmarkReport(List.of(of("c1", Verdict.PASS), of("c2", Verdict.PASS)));
        BenchmarkReport b = new BenchmarkReport(List.of(
                Judgment.noResponse("c1", Dimension.RETENTION, "无响应"), of("c2", Verdict.FAIL)));

        PairedCompare.Result result = PairedCompare.of(a, b);

        assertEquals(1, result.pairs(), "只有 c2 可配对");
        assertEquals(1, result.aOnlyWins());
        assertEquals(0, result.bOnlyWins());
    }

    @Test
    void allPassAndAllFailAreFlaggedButKept() {
        List<BenchmarkReport> runs = List.of(
                twoCases(Verdict.PASS, Verdict.FAIL),
                twoCases(Verdict.PASS, Verdict.FAIL));
        Discrimination discrimination = Discrimination.of(runs);

        assertEquals(2, discrimination.items().size(), "无区分力的题必须留在题集里");
        Discrimination.Item allPass = discrimination.items().get(0);
        assertEquals(Discrimination.Flag.LOW_DISCRIMINATION, allPass.flag());
        Discrimination.Item allFail = discrimination.items().get(1);
        assertEquals(Discrimination.Flag.LOW_DISCRIMINATION_FAIL, allFail.flag());
        assertEquals(0.0, discrimination.effectiveRatio(), 1e-9);
        assertTrue(discrimination.toText().contains("只标记，不自动删除"), discrimination.toText());
    }

    @Test
    void discriminatingCasePasses() {
        Discrimination discrimination = Discrimination.of(List.of(
                twoCases(Verdict.PASS, Verdict.FAIL),
                twoCases(Verdict.FAIL, Verdict.FAIL)));

        assertTrue(discrimination.items().get(0).discriminative());
        assertEquals(0.5, discrimination.effectiveRatio(), 1e-9);
    }

    @Test
    void flakyCaseIsNamedNotAveraged() {
        // 总体通过率在两次运行里都是 50%，标准差为 0——但 c1 一直在翻面
        List<BenchmarkReport> runs = List.of(
                twoCases(Verdict.PASS, Verdict.FAIL),
                twoCases(Verdict.FAIL, Verdict.FAIL));
        Consistency consistency = Consistency.of(runs);

        assertEquals(0.5, consistency.agreementRate(), 1e-9);
        assertEquals(1, consistency.flaky().size());
        assertEquals("c1", consistency.flaky().get(0).caseId());
        assertTrue(consistency.toText().contains("c1"), consistency.toText());
    }

    @Test
    void allUnknownCaseIsMarked() {
        Consistency consistency = Consistency.of(List.of(
                new BenchmarkReport(List.of(Judgment.noResponse("c9", Dimension.RECALL, "无响应"))),
                new BenchmarkReport(List.of(Judgment.noResponse("c9", Dimension.RECALL, "无响应")))));

        assertEquals(Consistency.Pattern.ALL_UNKNOWN, consistency.items().get(0).pattern());
        assertEquals(0.0, consistency.agreementRate(), 1e-9);
    }

    @Test
    void singleRunSkipsConsistency() {
        Consistency consistency = Consistency.of(List.of(twoCases(Verdict.PASS, Verdict.PASS)));
        assertTrue(consistency.toText().contains("--repeat >= 2"), consistency.toText());
    }
}
