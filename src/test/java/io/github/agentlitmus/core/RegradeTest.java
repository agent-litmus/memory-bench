package io.github.agentlitmus.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.report.RunManifest;
import io.github.agentlitmus.util.Digests;

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
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线重评（{@code litmus regrade}）的行为测试，含<b>负向对照</b>。
 * <p>
 * 要证明的不是"能重算一遍"，而是三件事：同口径重算<b>结果不变</b>（否则重评本身不可信）、
 * 口径变化<b>被逐条列出</b>（否则口径版本号是装饰）、旧格式证据<b>如实拒绝</b>（否则它在猜）。
 * 另外单独钉死一条：源运行目录一个字节都不许变。
 */
class RegradeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一组最小用例：每题一个独占关键词，答里含它就过 */
    private static List<MemoryCase> pool(int n) {
        List<MemoryCase> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(MemoryCase.expect("r" + i, Dimension.RETENTION, List.of("事实" + i),
                    "问题" + i, "关键词" + i));
        }
        return list;
    }

    private static String answerOf(MemoryCase kase, boolean correct) {
        return correct ? "答案是 " + kase.expectedContains() : "我不记得这件事";
    }

    private static void writeManifest(Path dir, String rules, String caseSetSha) throws IOException {
        Files.createDirectories(dir);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("schemaVersion", RunManifest.SCHEMA_VERSION);
        node.put("rulesVersion", rules);
        node.put("caseSetSha256", caseSetSha);
        node.put("suite", "basic");
        ObjectNode params = node.putObject("params");
        params.put("repeat", 1);
        params.put("parallel", 1);
        Files.writeString(dir.resolve(RunManifest.FILE_NAME), MAPPER.writeValueAsString(node),
                StandardCharsets.UTF_8);
    }

    /** 写一份带角色标注的证据流：每题一条 role=answer 与一条裁决产物 */
    private static void writeStream(Path dir, String agent, List<MemoryCase> pool,
                                    Map<String, Boolean> correct, Map<String, Verdict> recorded)
            throws IOException {
        EvidenceCollector collector = new EvidenceCollector();
        for (MemoryCase kase : pool) {
            String session = kase.sessionId();
            collector.collect(Evidence.dialogue(session, kase.question(), Evidence.ROLE_QUESTION));
            collector.collect(Evidence.dialogue(session, answerOf(kase, correct.get(kase.id())),
                    Evidence.ROLE_ANSWER));
            Verdict verdict = recorded.get(kase.id());
            collector.collect(Evidence.judgment(session, "Judgment[caseId=" + kase.id()
                    + ", verdict=" + verdict + "]", verdict.name()));
        }
        collector.exportTo(dir.resolve("evidence").resolve(agent + ".jsonl"));
    }

    private static Map<String, Boolean> allCorrect(List<MemoryCase> pool, boolean correct) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        pool.forEach(kase -> map.put(kase.id(), correct));
        return map;
    }

    private static Map<String, Verdict> verdictsOf(List<MemoryCase> pool, boolean correct) {
        Map<String, Verdict> map = new LinkedHashMap<>();
        pool.forEach(kase -> map.put(kase.id(), correct ? Verdict.PASS : Verdict.FAIL));
        return map;
    }

    @Test
    void 同口径重评不得产生任何变化(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(5);
        Path dir = root.resolve("run");
        writeManifest(dir, CaseJudge.RULES_VERSION, "sha-a");
        writeStream(dir, "agentA", pool, allCorrect(pool, true), verdictsOf(pool, true));

        Regrade.Result result = Regrade.run(dir, pool, new CaseJudge());

        assertTrue(result.usable(), "没有理由不可用: " + result.problems());
        assertEquals(1, result.agents().size());
        assertEquals(0, result.totalChanges(), "同一把尺子重量同一批证据，读数必须一模一样");
        Regrade.AgentResult agent = result.agents().get(0);
        assertEquals(5, agent.total());
        assertEquals(5, agent.beforePass());
        assertEquals(5, agent.afterPass());
        assertEquals(1.0, agent.beforeEffective());
        assertEquals(1.0, agent.afterEffective());
        assertTrue(result.toText().contains("新旧口径相同"), "同口径时必须说明这次重评只是自检");
    }

    @Test
    void 口径变化必须逐条列出并反映到有效分(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(4);
        Path dir = root.resolve("run");
        // 旧口径把 4 题全判成未过（例如归一化之前，全角空格让关键词匹配全灭）；今天的尺子能判对 3 题
        writeManifest(dir, "2026.01-legacy", "sha-a");
        writeStream(dir, "agentA", pool,
                Map.of("r1", true, "r2", true, "r3", true, "r4", false),
                verdictsOf(pool, false));

        Regrade.Result result = Regrade.run(dir, pool, new CaseJudge());

        assertTrue(result.usable());
        assertEquals("2026.01-legacy", result.beforeRules());
        assertEquals(CaseJudge.RULES_VERSION, result.afterRules());
        Regrade.AgentResult agent = result.agents().get(0);
        assertEquals(3, agent.changes().size(), "三条从未过翻成通过，第四条两边都是未过");
        assertEquals(0, agent.beforePass());
        assertEquals(3, agent.afterPass());
        assertTrue(agent.afterEffective() > agent.beforeEffective());
        assertTrue(agent.changes().stream().allMatch(c -> c.before() == Verdict.FAIL
                && c.after() == Verdict.PASS), "变化方向必须是旧→新: " + agent.changes());
        assertTrue(result.toText().contains("2026.01-legacy"), "报告要写清是哪套口径变成哪套");

        ObjectNode json = (ObjectNode) MAPPER.readTree(result.toJson());
        assertEquals("2026.01-legacy", json.get("beforeRules").asText());
        assertEquals(CaseJudge.RULES_VERSION, json.get("afterRules").asText());
        assertEquals(3, json.get("totalChanges").asInt());
        assertTrue(json.get("usable").asBoolean());
        assertEquals("FAIL", json.get("agents").get(0).get("changes").get(0).get("before").asText());
        assertEquals("PASS", json.get("agents").get(0).get("changes").get(0).get("after").asText());
    }

    @Test
    void 旧格式证据流必须如实拒绝而不是猜(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(2);
        Path dir = root.resolve("run");
        writeManifest(dir, CaseJudge.RULES_VERSION, "sha-a");
        // 角色标注之前的格式：只有 dialogue，没有 metadata.role
        EvidenceCollector collector = new EvidenceCollector();
        for (MemoryCase kase : pool) {
            collector.collect(new Evidence(Evidence.DIALOGUE, kase.sessionId(), 1L, kase.question(),
                    Map.of(), 1, "x"));
            collector.collect(new Evidence(Evidence.DIALOGUE, kase.sessionId(), 2L,
                    answerOf(kase, true), Map.of(), 2, "y"));
        }
        collector.exportTo(dir.resolve("evidence").resolve("agentA.jsonl"));

        Regrade.Result result = Regrade.run(dir, pool, new CaseJudge());

        assertFalse(result.usable(), "定位不到回答就不能重评");
        assertTrue(result.agents().isEmpty());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("旧格式") && p.contains("不猜")),
                "要说清为什么拒绝: " + result.problems());
        assertTrue(result.toText().contains("无法完成重评"));
    }

    @Test
    void 源运行目录一个字节都不许变(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(3);
        Path dir = root.resolve("run");
        writeManifest(dir, "2026.01-legacy", "sha-a");
        writeStream(dir, "agentA", pool, allCorrect(pool, true), verdictsOf(pool, false));
        Map<String, String> before = hashAll(dir);

        Regrade.run(dir, pool, new CaseJudge());

        assertEquals(before, hashAll(dir), "重评只读源目录；写回去会让源契约的产物哈希全部失配");
    }

    @Test
    void 没有运行契约时报出旧口径未知(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(2);
        Path dir = root.resolve("run");
        writeStream(dir, "agentA", pool, allCorrect(pool, true), verdictsOf(pool, true));

        Regrade.Result result = Regrade.run(dir, pool, new CaseJudge());

        assertFalse(result.usable());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains(RunManifest.FILE_NAME)));
    }

    @Test
    void 对不上题集的会话要被点名而不是静默丢弃(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(2);
        Path dir = root.resolve("run");
        writeManifest(dir, CaseJudge.RULES_VERSION, "sha-a");
        writeStream(dir, "agentA", pool, allCorrect(pool, true), verdictsOf(pool, true));
        // 追加一条题集里没有的会话
        EvidenceCollector extra = new EvidenceCollector();
        extra.collect(Evidence.dialogue("case-zz9", "答案", Evidence.ROLE_ANSWER));
        extra.collect(Evidence.judgment("case-zz9", "Judgment[verdict=PASS]", Verdict.PASS.name()));
        List<String> lines = new ArrayList<>(Files.readAllLines(
                dir.resolve("evidence").resolve("agentA.jsonl"), StandardCharsets.UTF_8));
        extra.all().forEach(e -> lines.add(MAPPER.valueToTree(e).toString()));
        Files.write(dir.resolve("evidence").resolve("agentA.jsonl"), lines, StandardCharsets.UTF_8);

        Regrade.Result result = Regrade.run(dir, pool, new CaseJudge());

        assertFalse(result.usable(), "题目对不上是数据问题，不能装作重评成功");
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("zz9")),
                "要能指出是哪个会话: " + result.problems());
    }

    @Test
    void 重复评测时读回第一轮裁决(@TempDir Path root) throws IOException {
        Path dir = root.resolve("run");
        EvidenceCollector collector = new EvidenceCollector();
        collector.collect(Evidence.judgment("case-r1", "Judgment[verdict=PASS]", Verdict.PASS.name()));
        collector.collect(Evidence.judgment("case-r1-r2", "Judgment[verdict=FAIL]", Verdict.FAIL.name()));
        collector.exportTo(dir.resolve("evidence").resolve("agentA.jsonl"));

        Map<String, Map<String, Verdict>> table = Regrade.readVerdicts(dir);

        assertEquals(Map.of("r1", Verdict.PASS), table.get("agentA"),
                "逐次翻面是 Consistency 的职责；比较只用第一轮，否则同一题会算两次");
    }

    @Test
    void 裁决优先读结构化字段文本只作兼容(@TempDir Path root) throws IOException {
        Path dir = root.resolve("run");
        EvidenceCollector collector = new EvidenceCollector();
        // 结构化字段与文本故意不一致：必须以结构化字段为准
        collector.collect(Evidence.judgment("case-r1", "Judgment[verdict=FAIL]", Verdict.PASS.name()));
        // 结构化字段缺失的旧证据：退回文本兼容路径
        collector.collect(new Evidence(Evidence.ARTIFACT, "case-r2", 1L,
                "Judgment[caseId=r2, verdict=UNKNOWN]", Map.of("kind", "judgment"), 3, "z"));
        // 两值时代的文本
        collector.collect(new Evidence(Evidence.ARTIFACT, "case-r3", 1L,
                "Judgment[caseId=r3, passed=false]", Map.of("kind", "judgment"), 4, "z"));
        collector.exportTo(dir.resolve("evidence").resolve("agentA.jsonl"));

        Map<String, Verdict> verdicts = Regrade.readVerdicts(dir).get("agentA");

        assertEquals(Verdict.PASS, verdicts.get("r1"));
        assertEquals(Verdict.UNKNOWN, verdicts.get("r2"));
        assertEquals(Verdict.FAIL, verdicts.get("r3"), "两值时代 passed=false 要读成未过");
    }

    @Test
    void 对照臂单独成一项(@TempDir Path root) throws IOException {
        List<MemoryCase> pool = pool(2);
        Path dir = root.resolve("run");
        writeManifest(dir, CaseJudge.RULES_VERSION, "sha-a");
        writeStream(dir, "agentA", pool, allCorrect(pool, true), verdictsOf(pool, true));
        writeStream(dir, "agentA-control", pool, allCorrect(pool, false), verdictsOf(pool, false));

        Map<String, Map<String, Verdict>> table = Regrade.readVerdicts(dir);

        assertEquals(2, table.size(), "对照臂不并进来，Δ 的口径会一半新一半旧");
        assertTrue(table.containsKey("agentA-control"));
        assertEquals(2, table.get("agentA-control").values().stream().filter(v -> v == Verdict.FAIL).count());
    }

    @Test
    void 会话号解析要认重复评测后缀() {
        assertEquals("r1", Regrade.caseIdOf("case-r1"));
        assertEquals("r1", Regrade.caseIdOf("case-r1-r3"));
        assertEquals("u10", Regrade.caseIdOf("case-u10-r2"));
        assertNull(Regrade.caseIdOf("别的会话号"));
        assertNull(Regrade.caseIdOf(null));
    }

    @Test
    void 裁决文本解析认三值也认两值() {
        assertEquals(Verdict.PASS, Regrade.parseVerdict("Judgment[caseId=r1, verdict=PASS]"));
        assertEquals(Verdict.UNKNOWN, Regrade.parseVerdict("... verdict=UNKNOWN ..."));
        assertEquals(Verdict.PASS, Regrade.parseVerdict("Judgment[caseId=r1, passed=true]"));
        assertEquals(Verdict.FAIL, Regrade.parseVerdict("Judgment[caseId=r1, passed=false]"));
        assertNull(Regrade.parseVerdict("这里没有任何裁决字段"));
        assertNull(Regrade.parseVerdict(null));
    }

    private static Map<String, String> hashAll(Path dir) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                hashes.put(dir.relativize(file).toString(),
                        Digests.sha256Hex(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return hashes;
    }
}
