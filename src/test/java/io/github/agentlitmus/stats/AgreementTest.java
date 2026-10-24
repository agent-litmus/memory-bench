package io.github.agentlitmus.stats;

import io.github.agentlitmus.core.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一致性统计测试。
 * <p>
 * κ 的公式本身不难，难的是<b>三值标签下哪种分歧该被算进哪个指标</b>。
 * 所以这里的用例不只验数值，更验分类：弃权不能混进"判错"，盲点不能混进"弃权"——
 * 一旦混了，报告就会把"尺子不敢下结论"说成"尺子下错结论"，两种问题的修法完全相反。
 */
class AgreementTest {

    private static Agreement.Item item(Verdict predicted, Verdict gold) {
        return new Agreement.Item(predicted.name() + "-" + gold.name(), predicted, gold, "理由");
    }

    private static List<Agreement.Item> items(Verdict[][] pairs) {
        List<Agreement.Item> list = new ArrayList<>();
        for (Verdict[] pair : pairs) {
            list.add(item(pair[0], pair[1]));
        }
        return list;
    }

    @Test
    void perfectAgreementGivesKappaOne() {
        Agreement.Result r = Agreement.of(items(new Verdict[][]{
                {Verdict.PASS, Verdict.PASS}, {Verdict.PASS, Verdict.PASS},
                {Verdict.FAIL, Verdict.FAIL}, {Verdict.FAIL, Verdict.FAIL}}));
        assertEquals(1.0, r.agreement(), 1e-9);
        assertEquals(1.0, r.kappa(), 1e-9);
        assertEquals(4, r.spoken());
        assertEquals(4, r.spokenCorrect());
        assertEquals(0, r.abstained());
        assertTrue(r.disagreements().isEmpty());
    }

    @Test
    void chanceLevelAgreementGivesKappaZero() {
        // 2x2 完全交叉：po=0.5，pe=0.5 ⇒ κ=0（一致性全部来自运气）
        Agreement.Result r = Agreement.of(items(new Verdict[][]{
                {Verdict.PASS, Verdict.PASS}, {Verdict.PASS, Verdict.FAIL},
                {Verdict.FAIL, Verdict.FAIL}, {Verdict.FAIL, Verdict.PASS}}));
        assertEquals(0.5, r.agreement(), 1e-9);
        assertEquals(0.0, r.kappa(), 1e-9);
        assertEquals(2, r.disagreements().size());
    }

    @Test
    void kappaIsUndefinedWhenOneSideOnlySpeaksOneCategory() {
        // 判定器全判通过：pe=1，分母为 0。此时报 0 会造出"完全不一致"的假结论
        Agreement.Result r = Agreement.of(items(new Verdict[][]{
                {Verdict.PASS, Verdict.PASS}, {Verdict.PASS, Verdict.PASS}}));
        assertFalse(r.kappaDefined());
        assertTrue(Double.isNaN(r.kappa()));
        assertEquals("无定义", Agreement.kappaBand(r.kappa()));
        assertEquals(1.0, r.agreement(), 1e-9);
    }

    @Test
    void abstentionIsNotCountedAsAWrongVerdict() {
        // 判定器弃权、人工给了确定标签：损失覆盖率，但没有造出错误结论
        Agreement.Result r = Agreement.of(items(new Verdict[][]{
                {Verdict.PASS, Verdict.PASS},
                {Verdict.UNKNOWN, Verdict.PASS},
                {Verdict.UNKNOWN, Verdict.FAIL}}));
        assertEquals(2, r.abstained());
        assertEquals(1, r.spoken());
        assertEquals(1, r.spokenCorrect());
        assertEquals(1.0, r.spokenRate(), 1e-9);
        assertEquals(2, r.disagreements().size());
        assertTrue(r.disagreements().stream().allMatch(d -> d.kind().startsWith("弃权")));
        assertEquals(0, r.blindSpots());
    }

    @Test
    void blindSpotIsPredictorSpeakingWhereHumanSaysUndecidable() {
        // 反向错误：把"根本没有回答"当成有效回答并给了结论——这才会造出错误结论
        Agreement.Result r = Agreement.of(items(new Verdict[][]{
                {Verdict.PASS, Verdict.UNKNOWN},
                {Verdict.FAIL, Verdict.UNKNOWN},
                {Verdict.UNKNOWN, Verdict.UNKNOWN}}));
        assertEquals(2, r.blindSpots());
        assertEquals(2, r.spoken());
        assertEquals(0, r.spokenCorrect());
        assertEquals(0.0, r.spokenRate(), 1e-9);
        assertEquals(2, r.disagreements().stream().filter(d -> d.kind().startsWith("盲点")).count());
    }

    @Test
    void falsePositiveAndFalseNegativeAreLabelledDifferently() {
        Agreement.Result r = Agreement.of(items(new Verdict[][]{
                {Verdict.PASS, Verdict.FAIL},   // 假阳：把缺陷放过去了
                {Verdict.FAIL, Verdict.PASS}})); // 假阴：把正确行为记成缺陷
        assertEquals("假阳(判错)", r.disagreements().get(0).kind());
        assertEquals("假阴(判错)", r.disagreements().get(1).kind());
    }

    @Test
    void spokenRateIsUndefinedWhenNothingWasSpoken() {
        Agreement.Result r = Agreement.of(items(new Verdict[][]{
                {Verdict.UNKNOWN, Verdict.PASS}, {Verdict.UNKNOWN, Verdict.FAIL}}));
        assertEquals(0, r.spoken());
        assertTrue(Double.isNaN(r.spokenRate()));
        assertTrue(r.toText("规则层").contains("无定义"));
    }

    @Test
    void confusionMatrixRowsArePredictionsAndColumnsAreGold() {
        List<Agreement.Item> list = items(new Verdict[][]{
                {Verdict.PASS, Verdict.PASS}, {Verdict.PASS, Verdict.FAIL},
                {Verdict.UNKNOWN, Verdict.PASS}});
        var matrix = Agreement.matrixOf(list);
        assertEquals(1, matrix.get("PASS").get("PASS"));
        assertEquals(1, matrix.get("PASS").get("FAIL"));
        assertEquals(1, matrix.get("UNKNOWN").get("PASS"));
        assertEquals(0, matrix.get("FAIL").get("FAIL"));
    }

    @Test
    void handComputedKappaMatchesTheTextbookExample() {
        // 50 条：双方都判"是"20、都判"否"15、一方判是另一方判否 8、反向 7
        // 行边际（判定器）: 是 28 / 否 22；列边际（人工）: 是 27 / 否 23
        // po = 35/50 = 0.700；pe = (28×27 + 22×23)/50² = (756+506)/2500 = 0.5048
        // κ = (0.700 - 0.5048)/(1 - 0.5048) = 0.3942
        List<Agreement.Item> list = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            list.add(item(Verdict.PASS, Verdict.PASS));
        }
        for (int i = 0; i < 15; i++) {
            list.add(item(Verdict.FAIL, Verdict.FAIL));
        }
        for (int i = 0; i < 8; i++) {
            list.add(item(Verdict.PASS, Verdict.FAIL));
        }
        for (int i = 0; i < 7; i++) {
            list.add(item(Verdict.FAIL, Verdict.PASS));
        }
        Agreement.Result r = Agreement.of(list);
        assertEquals(0.70, r.agreement(), 1e-9);
        assertEquals(0.3942, r.kappa(), 1e-4);
        // 0.3942 落在 0.20–0.40 区间：一致率看着有 70%，扣掉运气成分只剩"弱"——
        // 这正是必须报 κ 而不能只报一致率的理由
        assertEquals("弱", Agreement.kappaBand(r.kappa()));
    }
}
