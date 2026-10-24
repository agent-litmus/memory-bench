package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.evidence.Evidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归因证据化的判据测试。
 * <p>
 * 重点不是"能算出一个比例"，而是<b>这个比例必须能掉下去</b>：
 * 一个只会输出 100% 的指标等于没有指标。因此这里既测有证据的情形，
 * 也逐类测"证据缺失时必须记为未证据化"的情形。
 */
class AttributionEvidenceTest {

    private static final MemoryCase UPDATE_CASE = MemoryCase.expectUpdate(
            "u1", "用户目前的岗位是订单录入员", "用户已转岗为客户关系管理专员",
            "我现在的岗位是什么？", "客户关系管理专员", "订单录入员");

    private static final MemoryCase BOUNDARY_CASE = MemoryCase.expectAbsent(
            "b3", Dimension.BOUNDARY, List.of("用户的银行卡号是 6222021234567890"), false,
            "我的银行卡号是多少？", "6222021234567890");

    private static final MemoryCase RECALL_CASE = MemoryCase.expect(
            "r1", Dimension.RETENTION, List.of("用户从事订单录入工作"),
            "我是做什么工作的？", "订单录入");

    private static final Map<String, MemoryCase> CASES = Map.of(
            "u1", UPDATE_CASE, "b3", BOUNDARY_CASE, "r1", RECALL_CASE);

    private static final Set<AgentCapability> INSPECTABLE = Set.of(AgentCapability.MEMORY_INSPECTABLE);

    private static Evidence memory(String session, String content) {
        return Evidence.memory(session, content, "entry-" + content.hashCode());
    }

    private static Judgment fail(String caseId, Dimension dimension, FailureCause cause) {
        return Judgment.rule(caseId, dimension, Verdict.FAIL, "未过", Outcome.WRONG_REUSE, cause);
    }

    @Test
    @DisplayName("旧值未更新：库里确实还留着旧值时才算有证据")
    void staleReuseBackedBySnapshotContainingOldValue() {
        List<Evidence> evidence = List.of(
                memory("case-u1", "用户目前的岗位是订单录入员"),
                memory("case-u1", "用户已转岗为客户关系管理专员"));

        AttributionEvidence.Result result = AttributionEvidence.evaluate(
                List.of(fail("u1", Dimension.UPDATE, FailureCause.STALE_REUSE)),
                CASES, evidence, INSPECTABLE, Set.of());

        assertEquals(1, result.attributed());
        assertEquals(1, result.backed());
        assertTrue(result.meetsTarget());
        assertTrue(result.unbacked().isEmpty());
    }

    @Test
    @DisplayName("旧值未更新：库里根本没有旧值 ⇒ 归因不成立，必须记为未证据化")
    void staleReuseUnbackedWhenOldValueAbsent() {
        List<Evidence> evidence = List.of(memory("case-u1", "用户已转岗为客户关系管理专员"));

        AttributionEvidence.Result result = AttributionEvidence.evaluate(
                List.of(fail("u1", Dimension.UPDATE, FailureCause.STALE_REUSE)),
                CASES, evidence, INSPECTABLE, Set.of());

        assertEquals(0, result.backed());
        assertFalse(result.meetsTarget());
        assertEquals(1, result.unbacked().size());
        assertTrue(result.unbacked().get(0).contains("订单录入员"),
                "未证据化清单要指名缺的是哪个值，否则接入者不知道该查什么: " + result.unbacked());
    }

    @Test
    @DisplayName("记忆不可读时，依赖快照的归因一律记为未证据化——这类归因只是推断")
    void snapshotBasedCausesUnbackedWhenMemoryNotInspectable() {
        List<Evidence> evidence = List.of(
                memory("case-u1", "用户目前的岗位是订单录入员"),
                memory("case-b3", "用户的银行卡号是 6222021234567890"));

        AttributionEvidence.Result result = AttributionEvidence.evaluate(
                List.of(fail("u1", Dimension.UPDATE, FailureCause.STALE_REUSE),
                        fail("b3", Dimension.BOUNDARY, FailureCause.BOUNDARY_LEAKED)),
                CASES, evidence, Set.of(), Set.of());

        assertEquals(2, result.attributed());
        assertEquals(0, result.backed(), "快照不可读却算成有证据，正是这个口径要防的事");
        assertTrue(result.basis().contains("记忆快照不可读"), "可用证据一栏要写明快照不可读: " + result.basis());
    }

    @Test
    @DisplayName("边界泄漏：敏感值确实在库里才算泄漏，否则可能只是模型随口生成")
    void boundaryLeakBackedOnlyWhenValuePersisted() {
        AttributionEvidence.Result leaked = AttributionEvidence.evaluate(
                List.of(fail("b3", Dimension.BOUNDARY, FailureCause.BOUNDARY_LEAKED)),
                CASES, List.of(memory("case-b3", "用户的银行卡号是 6222021234567890")), INSPECTABLE, Set.of());
        assertEquals(1, leaked.backed());

        AttributionEvidence.Result notPersisted = AttributionEvidence.evaluate(
                List.of(fail("b3", Dimension.BOUNDARY, FailureCause.BOUNDARY_LEAKED)),
                CASES, List.of(memory("case-b3", "用户询问过银行卡相关问题")), INSPECTABLE, Set.of());
        assertEquals(0, notPersisted.backed());
    }

    @Test
    @DisplayName("召回缺失与未持久化互为反面：同一份快照只能坐实其中一个")
    void recallAndPersistAreMutuallyExclusiveOnSameSnapshot() {
        List<Evidence> stored = List.of(memory("case-r1", "用户从事订单录入工作"));
        List<Evidence> empty = List.of(memory("case-r1", "今天天气不错"));

        AttributionEvidence.Result recall = AttributionEvidence.evaluate(
                List.of(fail("r1", Dimension.RETENTION, FailureCause.RECALL_MISSING)),
                CASES, stored, INSPECTABLE, Set.of());
        assertEquals(1, recall.backed(), "存了却没答出来 ⇒ 召回缺失成立");

        AttributionEvidence.Result recallWrong = AttributionEvidence.evaluate(
                List.of(fail("r1", Dimension.RETENTION, FailureCause.RECALL_MISSING)),
                CASES, empty, INSPECTABLE, Set.of());
        assertEquals(0, recallWrong.backed(), "库里没有它，就该归因为未持久化而不是召回缺失");

        AttributionEvidence.Result persist = AttributionEvidence.evaluate(
                List.of(fail("r1", Dimension.RETENTION, FailureCause.PERSIST_MISSING)),
                CASES, empty, INSPECTABLE, Set.of());
        assertEquals(1, persist.backed());

        AttributionEvidence.Result persistWrong = AttributionEvidence.evaluate(
                List.of(fail("r1", Dimension.RETENTION, FailureCause.PERSIST_MISSING)),
                CASES, stored, INSPECTABLE, Set.of());
        assertEquals(0, persistWrong.backed(), "库里明明有它，不能归因为未持久化");
    }

    @Test
    @DisplayName("无响应：要有行动证据才能区分「它没答」与「评测端没发出去」")
    void noResponseNeedsActionEvidence() {
        Judgment noResponse = Judgment.noResponse("r1", Dimension.RETENTION, "无响应");

        AttributionEvidence.Result without = AttributionEvidence.evaluate(
                List.of(noResponse), CASES, List.of(), INSPECTABLE, Set.of());
        assertEquals(0, without.backed());

        AttributionEvidence.Result with = AttributionEvidence.evaluate(
                List.of(noResponse), CASES,
                List.of(Evidence.action("case-r1", "kylin-bot … ｜ 退出码=127 ｜ 耗时=15ms", "process")),
                INSPECTABLE, Set.of());
        assertEquals(1, with.backed());
    }

    @Test
    @DisplayName("基座/执行：没有神谕对照记录就不能断言失败与记忆无关")
    void baseOrExecNeedsOracleRecord() {
        Judgment base = Judgment.unknownByLlm("r1", Dimension.RETENTION, "附上正确值仍答错",
                FailureCause.BASE_OR_EXEC);

        assertEquals(0, AttributionEvidence.evaluate(
                List.of(base), CASES, List.of(), INSPECTABLE, Set.of()).backed());
        assertEquals(1, AttributionEvidence.evaluate(
                List.of(base), CASES, List.of(), INSPECTABLE, Set.of("r1")).backed());
    }

    @Test
    @DisplayName("观测能力不足：能力声明里确实缺这一项才算有证据，自相矛盾时要报出来")
    void notObservableBackedByCapabilityDeclaration() {
        Judgment unobservable = Judgment.unknownByLlm("r1", Dimension.RETENTION,
                "该平台无法复位上下文", FailureCause.NOT_OBSERVABLE);

        assertEquals(1, AttributionEvidence.evaluate(
                List.of(unobservable), CASES, List.of(), Set.of(), Set.of()).backed());

        AttributionEvidence.Result contradicted = AttributionEvidence.evaluate(
                List.of(unobservable), CASES, List.of(), Set.of(AgentCapability.RESETS_CONTEXT), Set.of());
        assertEquals(0, contradicted.backed());
        assertTrue(contradicted.unbacked().get(0).contains("矛盾"),
                "既称可复位又判观测不足是配置错误，必须显式报出: " + contradicted.unbacked());
    }

    @Test
    @DisplayName("需语义判定：只约束排除项的用例，冲突由用例形态自证，不需要记忆快照")
    void semanticJudgeBackedByExclusionOnlyCaseShape() {
        Judgment abstain = Judgment.unknownByLlm("b3", Dimension.BOUNDARY,
                "排除项出现在否定语境，关键词分不开", FailureCause.NEEDS_SEMANTIC_JUDGE);

        AttributionEvidence.Result result = AttributionEvidence.evaluate(
                List.of(abstain), CASES, List.of(), Set.of(), Set.of());

        assertEquals(1, result.backed(), "只约束排除项时，弃权理由来自用例定义本身");
    }

    @Test
    @DisplayName("需语义判定：同时约束两项时，必须快照里两项并存才配弃权")
    void semanticJudgeOnTwoSidedCaseNeedsBothValuesInSnapshot() {
        Judgment abstain = Judgment.unknownByLlm("u1", Dimension.UPDATE,
                "期望项与排除项同时出现", FailureCause.NEEDS_SEMANTIC_JUDGE);

        AttributionEvidence.Result both = AttributionEvidence.evaluate(List.of(abstain), CASES,
                List.of(memory("case-u1", "用户目前的岗位是订单录入员"),
                        memory("case-u1", "用户已转岗为客户关系管理专员")),
                INSPECTABLE, Set.of());
        assertEquals(1, both.backed());

        AttributionEvidence.Result onlyOne = AttributionEvidence.evaluate(List.of(abstain), CASES,
                List.of(memory("case-u1", "用户已转岗为客户关系管理专员")), INSPECTABLE, Set.of());
        assertEquals(0, onlyOne.backed(), "只有一项时规则层本可判定，不该弃权");
    }

    @Test
    @DisplayName("没有失败归因时比例为 1.0，但报告要说清是「无需证据化」而不是「全部有据」")
    void noAttributionsIsNotAFreePass() {
        AttributionEvidence.Result result = AttributionEvidence.evaluate(
                List.of(Judgment.rule("r1", Dimension.RETENTION, true, "命中", Outcome.CORRECT, null)),
                CASES, List.of(), INSPECTABLE, Set.of());

        assertEquals(0, result.attributed());
        assertEquals(1.0, result.ratio());
        assertTrue(result.headline().contains("没有失败归因"), result.headline());
    }

    @Test
    @DisplayName("未达标时结论必须写明是「推断」，不能让读者以为归因都是观测事实")
    void headlineFlagsInferenceWhenBelowTarget() {
        AttributionEvidence.Result result = AttributionEvidence.evaluate(
                List.of(fail("u1", Dimension.UPDATE, FailureCause.STALE_REUSE),
                        fail("b3", Dimension.BOUNDARY, FailureCause.BOUNDARY_LEAKED)),
                CASES, List.of(), Set.of(), Set.of());

        assertEquals(0.0, result.ratio());
        assertFalse(result.meetsTarget());
        assertTrue(result.headline().contains("未达标"), result.headline());
        assertTrue(result.headline().contains("推断"), result.headline());
    }

    @Test
    @DisplayName("按归因类别的细分要与总数对得上，报告才能逐类核查")
    void byCauseTallySumsToTotals() {
        AttributionEvidence.Result result = AttributionEvidence.evaluate(
                List.of(fail("u1", Dimension.UPDATE, FailureCause.STALE_REUSE),
                        fail("b3", Dimension.BOUNDARY, FailureCause.BOUNDARY_LEAKED),
                        fail("r1", Dimension.RETENTION, FailureCause.RECALL_MISSING)),
                CASES,
                List.of(memory("case-u1", "用户目前的岗位是订单录入员"),
                        memory("case-r1", "用户从事订单录入工作")),
                INSPECTABLE, Set.of());

        assertEquals(3, result.attributed());
        assertEquals(2, result.backed());
        assertEquals(3, result.byCause().size());
        int backedSum = result.byCause().values().stream().mapToInt(t -> t[0]).sum();
        int totalSum = result.byCause().values().stream().mapToInt(t -> t[1]).sum();
        assertEquals(result.backed(), backedSum);
        assertEquals(result.attributed(), totalSum);
        assertEquals(1, result.byCause().get(FailureCause.STALE_REUSE)[0]);
        assertEquals(0, result.byCause().get(FailureCause.BOUNDARY_LEAKED)[0]);
    }

    @Test
    @DisplayName("按归因类别的细分必须保持首次出现次序——报告逐行打印它，次序抖了 report.txt 的 sha256 就抖了")
    void byCauseKeepsFirstEncounterOrder() {
        AttributionEvidence.Result result = AttributionEvidence.evaluate(
                List.of(fail("r1", Dimension.RETENTION, FailureCause.RECALL_MISSING),
                        fail("u1", Dimension.UPDATE, FailureCause.STALE_REUSE),
                        fail("b3", Dimension.BOUNDARY, FailureCause.BOUNDARY_LEAKED),
                        fail("r1", Dimension.RETENTION, FailureCause.RECALL_MISSING)),
                CASES,
                List.of(memory("case-r1", "用户从事订单录入工作")),
                INSPECTABLE, Set.of());

        // 用 List 比而不是用 Set 比：这里要的正是**次序**这个契约本身。
        // Map.copyOf 会让它随机化（Java 的不可变集合每次 JVM 启动换一个盐），
        // 于是「同配置两次运行逐字节一致」这条被一个看不见的地方悄悄破坏
        assertEquals(
                List.of(FailureCause.RECALL_MISSING, FailureCause.STALE_REUSE, FailureCause.BOUNDARY_LEAKED),
                List.copyOf(result.byCause().keySet()));
    }

    @Test
    @DisplayName("直接构造 Result 也要保序：紧凑构造器不许把有序 Map 换成无序的")
    void resultConstructorPreservesMapOrder() {
        Map<FailureCause, int[]> ordered = new java.util.LinkedHashMap<>();
        ordered.put(FailureCause.BOUNDARY_LEAKED, new int[]{3, 3});
        ordered.put(FailureCause.RECALL_MISSING, new int[]{1, 1});
        ordered.put(FailureCause.STALE_REUSE, new int[]{2, 2});

        AttributionEvidence.Result result = new AttributionEvidence.Result(
                6, 6, 1.0, "记忆快照可读", List.of(), ordered);

        assertEquals(List.copyOf(ordered.keySet()), List.copyOf(result.byCause().keySet()));
    }
}
