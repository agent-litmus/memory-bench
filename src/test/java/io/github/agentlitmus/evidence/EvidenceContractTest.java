package io.github.agentlitmus.evidence;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据格式契约测试。
 * <p>
 * 证据流是整个工具的<b>唯一可核对物</b>：分数可以被质疑，但证据要么在、要么不在。
 * 所以这里不只测「能写出证据」，更要测<b>动过手脚一定被发现</b>——
 * 改一个字、删一行、伪造一条，都必须让自检报错。一条只会通过的契约测试等于没有契约。
 */
class EvidenceContractTest {

    private static EvidenceCollector filled() {
        EvidenceCollector collector = new EvidenceCollector();
        collector.collect(Evidence.dialogue("case-r1", "我的项目代号是 沧海-17", Evidence.ROLE_SEED));
        collector.collect(Evidence.dialogue("case-r1", "项目代号是什么？", Evidence.ROLE_QUESTION));
        collector.collect(Evidence.dialogue("case-r1", "沧海-17", Evidence.ROLE_ANSWER));
        collector.collect(Evidence.memory("case-r1", "记忆状态变化：新增 1", "state-delta"));
        collector.collect(Evidence.action("case-r1", "POST /api ｜ 状态码=200 ｜ 耗时=12ms", "process"));
        collector.collect(Evidence.artifact("case-r1", "Judgment[caseId=r1, verdict=PASS]", "judgment"));
        return collector;
    }

    @Test
    void 收集时盖上连续序号与内容哈希() {
        List<Evidence> evidence = filled().all();

        assertEquals(6, evidence.size());
        for (int i = 0; i < evidence.size(); i++) {
            Evidence item = evidence.get(i);
            assertEquals(i + 1, item.seq(), "序号必须从 1 开始连续，断号就意味着删过行");
            assertEquals(Digests.sha256Hex(item.content()), item.sha256(), "哈希必须对得上内容");
        }
        assertTrue(EvidenceIntegrity.check(evidence).ok(), EvidenceIntegrity.check(evidence).toText());
    }

    @Test
    void 落盘再读回后契约依然成立(@TempDir Path dir) throws IOException {
        Path file = filled().exportTo(dir.resolve("evidence").resolve("reference.jsonl"));

        List<Evidence> reloaded = EvidenceCollector.loadFrom(file);
        assertEquals(6, reloaded.size(), "JSON Lines 一行一条，读回不能丢");
        assertTrue(EvidenceIntegrity.checkFile(file).ok(), EvidenceIntegrity.checkFile(file).toText());
        assertEquals("沧海-17", reloaded.get(2).content());
        assertEquals("state-delta", reloaded.get(3).metadata().get("entryId"));
        assertEquals("process", reloaded.get(4).metadata().get("tool"));
    }

    @Test
    void 改掉一个字必须被发现(@TempDir Path dir) throws IOException {
        // 最危险的手脚不是删证据，而是把「未命中」改成「命中」：文件还在、行数还对
        Path file = filled().exportTo(dir.resolve("tampered.jsonl"));
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.set(2, lines.get(2).replace("沧海-17", "白鹭-02"));
        Files.write(file, lines, StandardCharsets.UTF_8);

        EvidenceIntegrity.Report report = EvidenceIntegrity.checkFile(file);
        assertFalse(report.ok(), "内容被改过，自检必须报错");
        assertTrue(report.issues().stream().anyMatch(i -> i.contains("哈希不匹配")), report.toText());
        assertTrue(report.toText().contains("第 3 行"), "要指出是哪一行: " + report.toText());
    }

    @Test
    void 删掉一行必须被发现(@TempDir Path dir) throws IOException {
        Path file = filled().exportTo(dir.resolve("trimmed.jsonl"));
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.remove(3);
        Files.write(file, lines, StandardCharsets.UTF_8);

        EvidenceIntegrity.Report report = EvidenceIntegrity.checkFile(file);
        assertFalse(report.ok(), "少一条证据比改一条更隐蔽，必须查得出来");
        assertTrue(report.issues().stream().anyMatch(i -> i.contains("序号断裂")), report.toText());
    }

    @Test
    void 字段缺失与非法类型必须被发现() {
        List<Evidence> broken = List.of(
                new Evidence("dialogue", "", 1L, "内容", java.util.Map.of(), 1,
                        Digests.sha256Hex("内容")),
                new Evidence("telepathy", "case-x", 1L, "内容", java.util.Map.of(), 2,
                        Digests.sha256Hex("内容")),
                new Evidence("memory", "case-x", 0L, "内容", java.util.Map.of(), 3,
                        Digests.sha256Hex("内容")));

        EvidenceIntegrity.Report report = EvidenceIntegrity.check(broken);
        assertFalse(report.ok());
        assertEquals(3, report.issues().size(), report.toText());
        assertTrue(report.issues().get(0).contains("sessionId"), report.toText());
        assertTrue(report.issues().get(1).contains("type 非法"), report.toText());
        assertTrue(report.issues().get(2).contains("timestamp"), report.toText());
    }

    @Test
    void 空回答也是一条合法证据() {
        // 被测对象没回话不等于没有证据：这条「空」本身就是结论的依据，不能因为空就丢弃
        EvidenceCollector collector = new EvidenceCollector();
        collector.collect(Evidence.dialogue("case-r1", "", Evidence.ROLE_ANSWER));

        List<Evidence> evidence = collector.all();
        assertEquals(1, evidence.size());
        assertEquals(Digests.sha256Hex(""), evidence.get(0).sha256());
        assertTrue(EvidenceIntegrity.check(evidence).ok(), EvidenceIntegrity.check(evidence).toText());
    }

    @Test
    void 一个空行不该让整份证据流变成假篡改(@TempDir Path dir) throws IOException {
        // 自检是门禁：报错误导人相信「证据被动过手脚」，代价和对篡改失明一样大。
        // 空行只可能是编辑/换行符处理留下的，不能被读成「删了一行」的连锁信号。
        Path file = filled().exportTo(dir.resolve("blank-line.jsonl"));
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.add(3, "");
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertTrue(EvidenceIntegrity.checkFile(file).ok(), EvidenceIntegrity.checkFile(file).toText());
    }

    @Test
    void 整份被删空必须自己报出来(@TempDir Path dir) throws IOException {
        // 断号只能发现「少了几行」；一行不剩时没有断号可查，必须靠「空」本身报警
        Path file = dir.resolve("empty.jsonl");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        EvidenceIntegrity.Report report = EvidenceIntegrity.checkFile(file);
        assertFalse(report.ok(), "空证据流不能算通过");
        assertTrue(report.toText().contains("为空"), report.toText());
    }

    @Test
    void 折叠过的会话名不得互相覆盖() {
        // 只在不安全字符上不同的两个会话，折叠后会撞成同一个文件名，后写的把先写的静默吃掉
        assertNotEquals(Transcript.fileNameOf("case-u4"), Transcript.fileNameOf("case-u4:对照"));
        assertNotEquals(Transcript.fileNameOf("case-报销规则"), Transcript.fileNameOf("case-报销流程"));
        assertEquals("case-u4.md", Transcript.fileNameOf("case-u4"), "纯 ASCII 的名字必须保持原样，别动历史产物");
        assertEquals(Transcript.fileNameOf("case-u4:对照"), Transcript.fileNameOf("case-u4:对照"),
                "同一个 ID 两次必须得到同一个名字，否则运行契约对不上");
    }
}
