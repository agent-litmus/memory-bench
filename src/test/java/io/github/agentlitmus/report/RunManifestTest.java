package io.github.agentlitmus.report;

import io.github.agentlitmus.agent.Agents;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.core.CaseJudge;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.core.MultiAgentBenchmark;
import io.github.agentlitmus.dataset.MemoryCases;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.util.Digests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行契约（manifest）的行为测试，含<b>负向对照</b>。
 * <p>
 * 光测"能写出 manifest"没有意义——真正要证明的是<b>篡改会被发现</b>。
 * 因此这里刻意改一份证据、加一个野文件，断言对账必须报错。
 * 一条只会通过的测试不叫测试，叫装饰。
 */
class RunManifestTest {

    private record Run(Path dir, BenchmarkResult result, List<MemoryCase> cases, List<AgentUnderTest> agents) {
    }

    /** 跑一次离线评测并落盘全部产物（不含 manifest） */
    private static Run prepare(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<MemoryCase> cases = MemoryCases.defaultCases().subList(0, 12);
        List<String> warnings = new ArrayList<>();
        List<AgentUnderTest> agents = Agents.resolve(List.of("reference"), dir.resolve("memory"), warnings);
        BenchmarkResult result = MultiAgentBenchmark.run(cases, agents, new CaseJudge(),
                dir.resolve("artifacts"), 1);

        Files.writeString(dir.resolve("report.txt"),
                result.reportOf(agents.get(0)).toText(), StandardCharsets.UTF_8);
        Path evidenceDir = dir.resolve("evidence");
        for (AgentUnderTest agent : agents) {
            EvidenceCollector collector = result.evidence().get(agent);
            if (collector != null) {
                collector.exportTo(evidenceDir.resolve(agent.id() + ".jsonl"));
            }
        }
        return new Run(dir, result, cases, agents);
    }

    @Test
    void 契约记录题集档位以便按同一档对账(@TempDir Path dir) throws IOException {
        // 档位不入档，verify 就只能拿默认档去对一个 full 档的目录，得到的是假的「题集不一致」
        Run run = prepare(dir);
        RunManifest.of(run.cases(), run.agents(), run.result(), dir, 1, 1,
                CaseJudge.RULES_VERSION, "full").writeTo(dir);

        RunManifest manifest = RunManifest.read(dir.resolve(RunManifest.FILE_NAME));
        assertEquals("full", manifest.suite());
        assertEquals(run.cases().size(), manifest.caseCount());
    }

    @Test
    void 正常跑完后对账必须全绿(@TempDir Path dir) throws IOException {
        Run run = prepare(dir);
        RunManifest.of(run.cases(), run.agents(), run.result(), dir, 1, 1, CaseJudge.RULES_VERSION).writeTo(dir);

        RunManifest manifest = RunManifest.read(dir.resolve(RunManifest.FILE_NAME));
        assertTrue(manifest.verify(dir).isEmpty(), "未被改动过的目录必须对账通过");
        assertFalse(manifest.files().isEmpty(), "至少要给结论提供产物哈希");
        assertEquals(CaseJudge.RULES_VERSION, manifest.rulesVersion());
        assertEquals(RunManifest.SCHEMA_VERSION, manifest.schemaVersion());
    }

    @Test
    void 题集哈希与用例集一一对应(@TempDir Path dir) throws IOException {
        Run run = prepare(dir);
        RunManifest.of(run.cases(), run.agents(), run.result(), dir, 1, 1, CaseJudge.RULES_VERSION).writeTo(dir);
        RunManifest manifest = RunManifest.read(dir.resolve(RunManifest.FILE_NAME));

        assertEquals(12, manifest.caseCount());
        assertEquals(Digests.sha256Hex(MemoryCases.canonicalJson(run.cases())), manifest.caseSetSha256());
    }

    @Test
    void 契约必须记录工具版本而不是未过滤的占位符(@TempDir Path dir) throws IOException {
        Run run = prepare(dir);
        RunManifest.of(run.cases(), run.agents(), run.result(), dir, 1, 1,
                CaseJudge.RULES_VERSION, "basic").writeTo(dir);
        RunManifest manifest = RunManifest.read(dir.resolve(RunManifest.FILE_NAME));

        // 「这批数字出自哪个版本」必须有答案，否则跨版本回归比较无从对齐。
        // 从 IDE 跑时资源可能没被 Maven 过滤，那时如实退回 unknown 也比留下 ${...} 好。
        assertNotNull(manifest.litmusVersion());
        assertFalse(manifest.litmusVersion().startsWith("${"),
                "版本戳没被过滤，检查 pom.xml 的 filtered-resources: " + manifest.litmusVersion());
        assertFalse(manifest.gitCommit().startsWith("${"),
                "commit 戳没被过滤: " + manifest.gitCommit());
    }

    @Test
    void 篡改证据必须被对账发现(@TempDir Path dir) throws IOException {
        Run run = prepare(dir);
        RunManifest.of(run.cases(), run.agents(), run.result(), dir, 1, 1, CaseJudge.RULES_VERSION).writeTo(dir);
        RunManifest manifest = RunManifest.read(dir.resolve(RunManifest.FILE_NAME));

        Path evidence = dir.resolve("evidence").resolve("reference.jsonl");
        List<String> lines = Files.readAllLines(evidence, StandardCharsets.UTF_8);
        lines.set(0, lines.get(0) + " ");   // 哪怕只改一个空格，也必须被哈希对账抓住
        Files.write(evidence, lines, StandardCharsets.UTF_8);

        List<String> issues = manifest.verify(dir);
        assertFalse(issues.isEmpty(), "改动过的证据必须被发现，否则可复现声明是空的");
        assertTrue(issues.stream().anyMatch(i -> i.contains("被改动") && i.contains("evidence")),
                "要能指出是哪一份产物被改动: " + issues);
    }

    @Test
    void 混入未登记文件也必须被发现(@TempDir Path dir) throws IOException {
        Run run = prepare(dir);
        RunManifest.of(run.cases(), run.agents(), run.result(), dir, 1, 1, CaseJudge.RULES_VERSION).writeTo(dir);
        RunManifest manifest = RunManifest.read(dir.resolve(RunManifest.FILE_NAME));

        Files.writeString(dir.resolve("外来结果.txt"), "来自别的一次运行", StandardCharsets.UTF_8);

        assertTrue(manifest.verify(dir).stream().anyMatch(i -> i.contains("未登记")),
                "目录里混进契约之外的产物同样是不一致");
    }

    @Test
    void 产物缺失必须被发现(@TempDir Path dir) throws IOException {
        Run run = prepare(dir);
        RunManifest.of(run.cases(), run.agents(), run.result(), dir, 1, 1, CaseJudge.RULES_VERSION).writeTo(dir);
        RunManifest manifest = RunManifest.read(dir.resolve(RunManifest.FILE_NAME));

        Files.delete(dir.resolve("report.txt"));

        assertTrue(manifest.verify(dir).stream().anyMatch(i -> i.contains("缺失")));
    }
}
