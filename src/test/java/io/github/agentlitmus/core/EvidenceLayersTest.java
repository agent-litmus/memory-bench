package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.agent.CommandAnswerer;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.evidence.EvidenceCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据分层测试：四类证据里<b>记忆</b>与<b>行动</b>这两层过去只是定义了类型、没有产出点，
 * 于是「统一组织为证据」这句话只有对话与产物撑得住。这里盯住它们真的被写进证据流，
 * 并且盯住<b>不该出现的时候不出现</b>——凭空多出来的证据和缺失的证据一样是假报告。
 */
class EvidenceLayersTest {

    private static MemoryCase recallCase() {
        return MemoryCase.expect("ev1", Dimension.RETENTION,
                List.of("项目代号是 沧海-17"), "项目代号是什么？", "沧海-17");
    }

    /** 会把「记忆」落到指定目录的智能体：状态可读，因此前后差异可被观测 */
    private static final class PersistingAgent implements Answerer {
        private final Path dir;
        private final boolean inspectable;

        PersistingAgent(Path dir, boolean inspectable) {
            this.dir = dir;
            this.inspectable = inspectable;
        }

        @Override
        public String answer(String sessionId, String question) {
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("memory.jsonl"), question + "\n",
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return "沧海-17";
        }

        @Override
        public Set<AgentCapability> capabilities() {
            return inspectable
                    ? Set.of(AgentCapability.MEMORY_INSPECTABLE, AgentCapability.RESETS_CONTEXT)
                    : Set.of(AgentCapability.RESETS_CONTEXT);
        }
    }

    @Test
    void memoryStateDeltaIsCollectedWhenTheAgentIsInspectable(@TempDir Path dir) throws IOException {
        Path state = dir.resolve("state");
        EvidenceCollector collector = new EvidenceCollector();
        DialogueBenchmarkRunner runner = new DialogueBenchmarkRunner(
                new PersistingAgent(state, true), new CaseJudge(), collector, dir.resolve("artifacts"),
                List.of(state));

        runner.run(List.of(recallCase()));

        List<Evidence> memory = collector.byType(Evidence.MEMORY);
        assertFalse(memory.isEmpty(), "记忆可读时，每回合的落盘差异必须进证据流");
        assertTrue(memory.stream().anyMatch(e -> "state-delta".equals(e.metadata().get("entryId"))),
                memory.toString());
        assertTrue(memory.stream().anyMatch(e -> e.content().contains("memory.jsonl")),
                "差异要指名道姓写出是哪个文件变了: " + memory);
        Path transcript = Files.list(dir.resolve("artifacts")).findFirst().orElseThrow();
        assertTrue(Files.readString(transcript, StandardCharsets.UTF_8).contains("记忆状态变化"),
                "逐条产物里也要能看到这段证据，否则报告里的结论无处对账");
    }

    @Test
    void noMemoryEvidenceWhenTheAgentIsOpaque(@TempDir Path dir) throws IOException {
        // 读不到就不许编：没有 MEMORY_INSPECTABLE 时一条记忆证据都不该出现
        Path state = dir.resolve("state");
        EvidenceCollector collector = new EvidenceCollector();
        new DialogueBenchmarkRunner(new PersistingAgent(state, false), new CaseJudge(),
                collector, dir.resolve("artifacts"), List.of(state))
                .run(List.of(recallCase()));

        assertTrue(collector.byType(Evidence.MEMORY).isEmpty(),
                "不可观测的对象被写上记忆证据，等于凭空造了一份没人能核对的记录");
    }

    @Test
    void processExecutionIsCollectedAsActionEvidence(@TempDir Path dir) {
        // 命令行智能体的进程执行是评测方自己看见的事实（命令行/退出码/耗时），不是被测对象自报的工具调用
        CommandAnswerer agent = new CommandAnswerer("echo", List.of("{question}"), List.of(),
                Duration.ofSeconds(30), dir, null);
        EvidenceCollector collector = new EvidenceCollector();
        new DialogueBenchmarkRunner(agent, new CaseJudge(), collector, dir.resolve("artifacts"))
                .run(List.of(recallCase()));

        List<Evidence> actions = collector.byType(Evidence.ACTION);
        assertFalse(actions.isEmpty(), "每次真实执行的子进程都应留下一条行动证据");
        assertTrue(actions.stream().allMatch(a -> "process".equals(a.metadata().get("tool"))),
                actions.toString());
        assertTrue(actions.stream().anyMatch(a -> a.content().contains("退出码=0")),
                "行动证据要带可核对的执行结果: " + actions);
    }
}
