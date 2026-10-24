package io.github.agentlitmus.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.report.RunManifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本回归门禁（{@code litmus compare}）的行为测试，含<b>负向对照</b>。
 * <p>
 * 门禁有两个方向的失败模式，两边都要测：太松（真回归放过，CI 变装饰）与太紧
 * （单题抖动就拦人，于是所有人开始加 {@code || true}）。所以这里既断言
 * 「显著回归 / 覆盖率下降必须拦」，也断言「一题翻面只列明细不拦」。
 */
class RunCompareTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<MemoryCase> pool(int n) {
        List<MemoryCase> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(MemoryCase.expect("r" + i, Dimension.RETENTION, List.of("事实" + i),
                    "问题" + i, "关键词" + i));
        }
        return list;
    }

    private static Map<String, Verdict> uniform(List<MemoryCase> pool, Verdict verdict) {
        Map<String, Verdict> map = new LinkedHashMap<>();
        pool.forEach(kase -> map.put(kase.id(), verdict));
        return map;
    }

    /** 造一个运行目录：契约 + 一份只含裁决产物的证据流（compare 只读裁决，不读回答） */
    private static Path build(Path dir, String rules, String caseSet, String agent,
                              Map<String, Verdict> verdicts) throws IOException {
        Files.createDirectories(dir);
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("schemaVersion", RunManifest.SCHEMA_VERSION);
        manifest.put("rulesVersion", rules);
        manifest.put("caseSetSha256", caseSet);
        manifest.put("suite", "basic");
        ObjectNode params = manifest.putObject("params");
        params.put("repeat", 1);
        params.put("parallel", 1);
        Files.writeString(dir.resolve(RunManifest.FILE_NAME), MAPPER.writeValueAsString(manifest),
                StandardCharsets.UTF_8);

        EvidenceCollector collector = new EvidenceCollector();
        verdicts.forEach((caseId, verdict) -> collector.collect(Evidence.judgment("case-" + caseId,
                "Judgment[caseId=" + caseId + ", verdict=" + verdict + "]", verdict.name())));
        collector.exportTo(dir.resolve("evidence").resolve(agent + ".jsonl"));
        return dir;
    }

    private static Path pair(Path root, String rulesA, String rulesB, String caseSetA, String caseSetB,
                             Map<String, Verdict> a, Map<String, Verdict> b) throws IOException {
        build(root.resolve("A"), rulesA, caseSetA, "agentA", a);
        return build(root.resolve("B"), rulesB, caseSetB, "agentA", b);
    }

    @Test
    void 两次相同运行必须通过门禁(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(20);
        Map<String, Verdict> same = uniform(pool, Verdict.PASS);
        pair(root, CaseJudge.RULES_VERSION, CaseJudge.RULES_VERSION, "sha-1", "sha-1", same, same);

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertFalse(result.blocked(), String.join(" / ", result.redLines()));
        assertEquals(0, result.regressions());
        assertEquals(0, result.improvements());
        assertEquals(20, result.agents().get(0).shared());
        assertEquals(0.0, result.agents().get(0).paired().delta());
        assertTrue(result.toText().contains("未见显著回归"));
    }

    @Test
    void 显著回归必须拦下(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(30);
        pair(root, CaseJudge.RULES_VERSION, CaseJudge.RULES_VERSION, "sha-1", "sha-1",
                uniform(pool, Verdict.PASS), uniform(pool, Verdict.FAIL));

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertTrue(result.blocked());
        assertEquals(30, result.regressions());
        assertTrue(result.redLines().stream().anyMatch(r -> r.contains("显著回归")),
                String.join(" / ", result.redLines()));
        assertTrue(result.agents().get(0).paired().significant());
    }

    @Test
    void 单题翻面只列明细不拦人(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(30);
        Map<String, Verdict> before = uniform(pool, Verdict.PASS);
        Map<String, Verdict> after = new LinkedHashMap<>(before);
        after.put("r7", Verdict.FAIL);
        pair(root, CaseJudge.RULES_VERSION, CaseJudge.RULES_VERSION, "sha-1", "sha-1", before, after);

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertFalse(result.blocked(), "真实智能体本身有抖动，一题翻面不足以判回归: " + result.redLines());
        assertEquals(1, result.regressions());
        assertEquals("r7", result.agents().get(0).changes().get(0).caseId());
        assertEquals(RunCompare.Kind.REGRESSION, result.agents().get(0).changes().get(0).kind());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("抖动")),
                "要照实说有回归，只是没到显著: " + result.notes());
    }

    @Test
    void 改进不得被当成红线(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(30);
        pair(root, CaseJudge.RULES_VERSION, CaseJudge.RULES_VERSION, "sha-1", "sha-1",
                uniform(pool, Verdict.FAIL), uniform(pool, Verdict.PASS));

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertFalse(result.blocked(), String.join(" / ", result.redLines()));
        assertEquals(30, result.improvements());
        assertEquals(0, result.regressions());
    }

    @Test
    void 新增不可判是红线因为分母被移动过(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(30);
        Map<String, Verdict> after = new LinkedHashMap<>(uniform(pool, Verdict.PASS));
        after.put("r3", Verdict.UNKNOWN);
        pair(root, CaseJudge.RULES_VERSION, CaseJudge.RULES_VERSION, "sha-1", "sha-1",
                uniform(pool, Verdict.PASS), after);

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertTrue(result.blocked());
        assertEquals(1, result.lostJudgments());
        assertTrue(result.redLines().stream().anyMatch(r -> r.contains("新增不可判")),
                String.join(" / ", result.redLines()));
        assertEquals(29, result.agents().get(0).paired().pairs(), "不可判的题退出配对，不算失败");
    }

    @Test
    void 不可判消解是好事(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(30);
        Map<String, Verdict> before = new LinkedHashMap<>(uniform(pool, Verdict.PASS));
        before.put("r3", Verdict.UNKNOWN);
        pair(root, CaseJudge.RULES_VERSION, CaseJudge.RULES_VERSION, "sha-1", "sha-1",
                before, uniform(pool, Verdict.PASS));

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertFalse(result.blocked(), String.join(" / ", result.redLines()));
        assertEquals(1, result.agents().get(0).countOf(RunCompare.Kind.GAINED_JUDGMENT));
        assertTrue(result.agents().get(0).coverageAfter() > result.agents().get(0).coverageBefore());
    }

    @Test
    void 口径不同必须先重评而不是直接比(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(10);
        pair(root, "2026.01-legacy", CaseJudge.RULES_VERSION, "sha-1", "sha-1",
                uniform(pool, Verdict.PASS), uniform(pool, Verdict.PASS));

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertTrue(result.blocked());
        assertTrue(result.redLines().stream().anyMatch(r -> r.contains("判定口径不同") && r.contains("regrade")),
                "要指出解法是先把口径对齐: " + result.redLines());
        assertTrue(result.toText().contains("不同！"));
    }

    @Test
    void 题集不同等于比的不是同一套题(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(10);
        pair(root, CaseJudge.RULES_VERSION, CaseJudge.RULES_VERSION, "sha-1", "sha-2",
                uniform(pool, Verdict.PASS), uniform(pool, Verdict.PASS));

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertTrue(result.blocked());
        assertTrue(result.redLines().stream().anyMatch(r -> r.contains("题集不同")),
                String.join(" / ", result.redLines()));
    }

    @Test
    void 没有契约的目录不能当基线(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(5);
        build(root.resolve("A"), CaseJudge.RULES_VERSION, "sha-1", "agentA", uniform(pool, Verdict.PASS));
        Path b = build(root.resolve("B"), CaseJudge.RULES_VERSION, "sha-1", "agentA",
                uniform(pool, Verdict.PASS));
        Files.delete(b.resolve(RunManifest.FILE_NAME));

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertTrue(result.blocked());
        assertTrue(result.redLines().stream().anyMatch(r -> r.contains(RunManifest.FILE_NAME)),
                String.join(" / ", result.redLines()));
        assertEquals("无契约", result.rulesB());
    }

    @Test
    void 只在一侧存在的对象要被点名(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(5);
        build(root.resolve("A"), CaseJudge.RULES_VERSION, "sha-1", "agentA", uniform(pool, Verdict.PASS));
        build(root.resolve("B"), CaseJudge.RULES_VERSION, "sha-1", "agentA", uniform(pool, Verdict.PASS));
        EvidenceCollector extra = new EvidenceCollector();
        pool.forEach(kase -> extra.collect(Evidence.judgment(kase.sessionId(),
                "Judgment[verdict=PASS]", Verdict.PASS.name())));
        extra.exportTo(root.resolve("B").resolve("evidence").resolve("agentB.jsonl"));

        RunCompare.Result result = RunCompare.of(root.resolve("A"), root.resolve("B"));

        assertEquals(1, result.agents().size(), "无法配对的对象不进比较表");
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("agentB") && n.contains("只在")),
                String.join(" / ", result.notes()));
    }

    @Test
    void 主臂与对照臂各自成一项(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(8);
        Path a = build(root.resolve("A"), CaseJudge.RULES_VERSION, "sha-1", "agentA",
                uniform(pool, Verdict.PASS));
        build(a, CaseJudge.RULES_VERSION, "sha-1", "agentA-control", uniform(pool, Verdict.FAIL));
        Path b = build(root.resolve("B"), CaseJudge.RULES_VERSION, "sha-1", "agentA",
                uniform(pool, Verdict.PASS));
        build(b, CaseJudge.RULES_VERSION, "sha-1", "agentA-control", uniform(pool, Verdict.PASS));

        RunCompare.Result result = RunCompare.of(a, b);

        assertEquals(2, result.agents().size());
        assertTrue(result.agents().stream().anyMatch(x -> x.agent().equals("agentA-control")));
        assertFalse(result.blocked(), String.join(" / ", result.redLines()));
    }

    @Test
    void 结论要能被脚本消费(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(12);
        pair(root, CaseJudge.RULES_VERSION, CaseJudge.RULES_VERSION, "sha-1", "sha-1",
                uniform(pool, Verdict.PASS), uniform(pool, Verdict.FAIL));

        ObjectNode json = (ObjectNode) MAPPER.readTree(
                RunCompare.of(root.resolve("A"), root.resolve("B")).toJson());

        assertTrue(json.get("blocked").asBoolean());
        assertTrue(json.get("sameRules").asBoolean());
        assertEquals(12, json.get("regressions").asInt());
        assertEquals("REGRESSION", json.get("agents").get(0).get("changes").get(0).get("kind").asText());
        assertFalse(json.get("redLines").isEmpty());
    }
}
