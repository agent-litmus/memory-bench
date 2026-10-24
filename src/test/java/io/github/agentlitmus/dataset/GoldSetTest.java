package io.github.agentlitmus.dataset;

import io.github.agentlitmus.core.CaseJudge;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.core.RulerSelfTest;
import io.github.agentlitmus.core.Verdict;
import io.github.agentlitmus.stats.Agreement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标注集与尺子自证的测试——<b>这一组测试本身就是 CI 里的校准闸门</b>。
 * <p>
 * 它盯三件事：
 * <ol>
 *   <li><b>标注集没有失效</b>：每条都能对到当前题集，且用例的判定关键词与标注时一致。
 *       题目改了而标注没改，κ 就成了拿旧答案给新尺子打的分，必须报错而不是继续算；</li>
 *   <li><b>规则层达到下限</b>：离线、无 Key 的那把尺子，"开口即对率"必须 ≥
 *       {@link RulerSelfTest#RULE_AGREEMENT_FLOOR}。改判定规则改坏了，这里先红；</li>
 *   <li><b>已知误差没有被悄悄"修好"</b>：标注集里那条唯一的假阳性（关键词出现≠关键词被应用）
 *       是<b>故意留着</b>的——它记录了规则层原理上做不到的事。哪天它不见了，
 *       说明有人为了让数字好看动了标注，而不是动了尺子。</li>
 * </ol>
 * 全程不联网：评委层的 κ 由 {@code litmus selftest --judge} 在有密钥的环境里量，
 * 缺席时如实记为"不可判"，不在测试里假装达标。
 */
class GoldSetTest {

    private static final GoldSet GOLD = GoldSet.bundled();

    @Test
    void bundledGoldSetResolvesAgainstTheCurrentCasePool() {
        List<String> issues = GOLD.validate();
        assertTrue(issues.isEmpty(), "标注集与当前题集对不上，κ 与一致率都失去意义：\n" + String.join("\n", issues));
        assertEquals(56, GOLD.entries().size());
        assertNotNull(GOLD.version());
        assertNotNull(GOLD.honestyNote());
    }

    @Test
    void everyEntryCarriesALabelAndAReason() {
        for (GoldSet.GoldEntry entry : GOLD.entries()) {
            assertNotNull(entry.label(), entry.caseId());
            assertFalse(entry.rationale().isBlank(), "没有理由的标签无法复核: " + entry.caseId());
            assertNotNull(entry.source(), "样本必须能溯源到真实运行产物: " + entry.caseId());
        }
    }

    @Test
    void blankAnswersAreLabelledUndecidable() {
        for (GoldSet.GoldEntry entry : GOLD.entries()) {
            if (entry.blankAnswer()) {
                assertEquals(Verdict.UNKNOWN, entry.label(),
                        "当时就没有回答，人工标签只能是「不可判」: " + entry.caseId());
            }
        }
    }

    @Test
    void allThreeLabelsAndAllHardClassesAreCovered() {
        Map<Verdict, Integer> labels = GOLD.labelCounts();
        assertTrue(labels.get(Verdict.PASS) > 0);
        assertTrue(labels.get(Verdict.FAIL) > 0);
        assertTrue(labels.get(Verdict.UNKNOWN) > 0, "只标通过与未过的标注集量不出三值口径是否成立");
        Map<String, Integer> focus = GOLD.focusCounts();
        for (String required : List.of("contrast", "negation", "format", "nonanswer", "leak-with-disclaimer")) {
            assertTrue(focus.getOrDefault(required, 0) > 0, "标注集必须覆盖难点类: " + required);
        }
    }

    @Test
    void ruleRulerMeetsTheAgreementFloor() {
        RulerSelfTest.Result result = RulerSelfTest.run(GOLD, CaseSuites.of(CaseSuites.FULL), null,
                "测试环境不联网", null);
        assertTrue(result.valid());
        Agreement.Result rule = result.rule().agreement();
        assertTrue(rule.spokenRate() >= RulerSelfTest.RULE_AGREEMENT_FLOOR,
                String.format("规则层开口即对率 %.1f%% 低于下限 %.0f%%%n%s",
                        rule.spokenRate() * 100, RulerSelfTest.RULE_AGREEMENT_FLOOR * 100, result.toText()));
        assertTrue(rule.kappaDefined(), "κ 无定义意味着规则层在这批样本上只输出了一种结论");
        assertEquals(0, rule.blindSpots(), "把「根本没有回答」当成有效回答的盲点必须为 0（见 NonAnswer）");
        assertTrue(result.gateFailures().isEmpty(), String.join("\n", result.gateFailures()));
    }

    @Test
    void abstentionIsReportedAlongsideAccuracyNeverInsteadOfIt() {
        Agreement.Result rule = RulerSelfTest.run(GOLD, CaseSuites.of(CaseSuites.FULL), null, null, null)
                .rule().agreement();
        assertTrue(rule.abstained() > 0, "规则层在信号冲突时确实会弃权，这是设计而不是缺陷");
        assertTrue(rule.abstainRate() < 0.5, "弃权超过一半就等于没测，得回去看判定规则");
        String text = rule.toText("规则层");
        assertTrue(text.contains("开口即对率") && text.contains("弃权率"),
                "两个指标必须同时出现：只报一个都会误导");
    }

    @Test
    void theOneKnownFalsePositiveStaysDocumented() {
        Agreement.Result rule = RulerSelfTest.run(GOLD, CaseSuites.of(CaseSuites.FULL), null, null, null)
                .rule().agreement();
        List<Agreement.Disagreement> wrong = rule.disagreements().stream()
                .filter(d -> d.kind().startsWith("假阳") || d.kind().startsWith("假阴"))
                .toList();
        assertEquals(1, wrong.size(), "规则层的判错条数变了：要么是尺子改进了（请同步更新标注与文档），"
                + "要么是新引入了误差。当前: " + wrong);
        assertTrue(wrong.get(0).key().startsWith("t13@hermes"),
                "唯一的已知假阳性是 t13@hermes（关键词出现≠关键词被应用），实际: " + wrong.get(0).key());
    }

    @Test
    void judgeLayerIsReportedAsUndecidableWhenNoJudgeIsWired() {
        RulerSelfTest.Result result = RulerSelfTest.run(GOLD, CaseSuites.of(CaseSuites.FULL), null,
                "无可用评委", null);
        assertEquals(null, result.judge());
        assertTrue(result.toText().contains("不可判"),
                "评委层缺席时必须明写 κ 门禁不可判，不能拿规则层的数字顶替");
        assertTrue(result.toJson().contains("judgeLayerUnavailable"));
        assertTrue(result.gateFailures().isEmpty(), "评委缺席不是尺子不达标");
    }

    @Test
    void tamperingWithAnnotatedKeywordsIsDetected() {
        List<GoldSet.GoldEntry> tampered = new ArrayList<>(GOLD.entries());
        GoldSet.GoldEntry original = tampered.get(0);
        tampered.set(0, new GoldSet.GoldEntry(original.caseId(), original.agent(), original.question(),
                original.answer(), "被改过的期望串", original.excludedAtAnnotation(),
                original.label(), original.focus(), original.rationale(), original.source()));
        GoldSet gold = new GoldSet(GOLD.version(), GOLD.annotatedAt(), GOLD.annotator(),
                GOLD.protocol(), GOLD.honestyNote(), tampered);
        List<String> issues = gold.validate();
        assertFalse(issues.isEmpty(), "用例关键词改了而标注没改，必须吵起来");
        assertTrue(issues.get(0).contains(original.caseId()));
    }

    @Test
    void annotationPointingAtADeletedCaseIsDetected() {
        GoldSet.GoldEntry orphan = new GoldSet.GoldEntry("不存在的用例", "x", "q", "a",
                null, null, Verdict.PASS, "normal", "理由", "src");
        GoldSet gold = new GoldSet(GOLD.version(), GOLD.annotatedAt(), GOLD.annotator(),
                GOLD.protocol(), GOLD.honestyNote(), List.of(orphan));
        List<String> issues = gold.validate(Map.of());
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("已不在题集中"));
    }

    @Test
    void shaChangesWhenAnyLabelOrAnswerChanges() {
        String base = GOLD.sha256();
        GoldSet.GoldEntry first = GOLD.entries().get(0);
        List<GoldSet.GoldEntry> changed = new ArrayList<>(GOLD.entries());
        changed.set(0, new GoldSet.GoldEntry(first.caseId(), first.agent(), first.question(), first.answer(),
                first.expectedAtAnnotation(), first.excludedAtAnnotation(),
                first.label() == Verdict.PASS ? Verdict.FAIL : Verdict.PASS,
                first.focus(), first.rationale(), first.source()));
        GoldSet gold = new GoldSet(GOLD.version(), GOLD.annotatedAt(), GOLD.annotator(),
                GOLD.protocol(), GOLD.honestyNote(), changed);
        assertFalse(base.equals(gold.sha256()), "换了标注必须能从 sha256 看出来，否则 selftest 报告无法对账");
    }

    @Test
    void rulesVersionIsRecordedSoCalibrationCanBeTiedToACaliber() {
        RulerSelfTest.Result result = RulerSelfTest.run(GOLD, CaseSuites.of(CaseSuites.FULL), null, null, null);
        assertEquals(CaseJudge.RULES_VERSION, result.rulesVersion());
        assertTrue(result.toJson().contains(CaseJudge.RULES_VERSION));
        assertTrue(result.toJson().contains("confusion"), "混淆矩阵要落进 JSON，便于复核 κ 是怎么算出来的");
    }

    @Test
    void goldEntriesAllResolveToRealCases() {
        Map<String, MemoryCase> byId = GOLD.resolveCases();
        for (GoldSet.GoldEntry entry : GOLD.entries()) {
            MemoryCase kase = byId.get(entry.caseId());
            assertNotNull(kase, "标注指向的用例不存在: " + entry.caseId());
            assertFalse(kase.requiresLlmJudge(),
                    "标注集目前只覆盖规则可判的用例；需语义判定的用例要另开一批标注: " + entry.caseId());
        }
    }
}
