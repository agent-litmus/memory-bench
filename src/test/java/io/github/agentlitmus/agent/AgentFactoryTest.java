package io.github.agentlitmus.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能体配置化接入测试：验证 http / command / builtin 三种类型，
 * 以及从 JSON 加载配置的能力（对应命题"支持导入不同智能体配置"）。
 */
class AgentFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void createsBuiltinAgentsInMemoryMode() {
        AgentUnderTest reference = AgentFactory.create(AgentConfig.builtin("reference", "参考"));
        AgentUnderTest degraded = AgentFactory.create(AgentConfig.builtin("degraded", "缺陷"));

        assertEquals(RunnerMode.MEMORY, reference.mode());
        assertEquals(RunnerMode.MEMORY, degraded.mode());
        assertNotNull(reference.answerer());
    }

    @Test
    void createsHttpAgentInDialogueMode() {
        AgentUnderTest agent = AgentFactory.create(
                AgentConfig.http("kylinbot", "KylinBot", "http://127.0.0.1:8080"));

        assertEquals(RunnerMode.DIALOGUE, agent.mode());
        assertEquals("kylinbot", agent.id());
    }

    @Test
    void createsCommandAgentInDialogueMode() {
        AgentUnderTest agent = AgentFactory.create(AgentConfig.command(
                "hermes", "Hermes", "echo", List.of("{question}")));

        assertEquals(RunnerMode.DIALOGUE, agent.mode());
        // 实际可执行命令：应返回命令输出（echo 回显提问）
        String answer = agent.answerer().answer("s1", "hello");
        assertTrue(answer.contains("hello"), "命令行智能体应回显提问，实际: " + answer);
    }

    @Test
    void loadsAgentsFromJsonConfig() throws IOException {
        Path file = tempDir.resolve("agents.json");
        String json = """
                [
                  {"id":"reference","name":"参考","type":"builtin"},
                  {"id":"degraded","name":"缺陷","type":"builtin"},
                  {"id":"kylinbot","name":"KylinBot","type":"http",
                   "endpoint":"http://127.0.0.1:8080","path":"/v1/chat"}
                ]
                """;
        Files.writeString(file, json, StandardCharsets.UTF_8);

        List<String> warnings = new java.util.ArrayList<>();
        List<AgentUnderTest> agents = AgentFactory.loadFrom(file, warnings);

        assertEquals(3, agents.size(), "应加载 3 个智能体");
        assertTrue(warnings.isEmpty(), "不应有警告");
        assertTrue(agents.stream().anyMatch(a -> "kylinbot".equals(a.id())));
    }

    @Test
    void skipsInvalidConfigWithWarningInsteadOfFailing() throws IOException {
        Path file = tempDir.resolve("bad.json");
        String json = """
                [
                  {"id":"reference","name":"正常","type":"builtin"},
                  {"id":"bad","name":"缺 endpoint","type":"http"}
                ]
                """;
        Files.writeString(file, json, StandardCharsets.UTF_8);

        List<String> warnings = new java.util.ArrayList<>();
        List<AgentUnderTest> agents = AgentFactory.loadFrom(file, warnings);

        assertEquals(1, agents.size(), "无效配置应被跳过而非中断整轮评测");
        assertFalse(warnings.isEmpty(), "应记录警告说明原因");
    }

    @Test
    void rejectsUnknownAgentType() {
        AgentConfig config = new AgentConfig("x", "X", "", "grpc", null, null, null, null,
                List.of(), List.of(), 60, null, null, null, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> AgentFactory.create(config));
    }

    @Test
    void declaredCapabilitiesRaiseConfidenceTier() {
        // 能力来自两处：适配器自证 + 配置声明；并集决定层级，层级决定归因能到什么深度
        AgentConfig plain = AgentConfig.command("cli", "CLI 智能体", "echo", List.of("{question}"));
        assertEquals(ConfidenceTier.T1, AgentFactory.create(plain).answerer().confidenceTier(),
                "只有对话接口时只能是 T1");

        AgentConfig withState = new AgentConfig("cli2", "CLI 智能体（带状态目录）", "", 
                AgentConfig.TYPE_COMMAND, null, null, null, "echo", List.of("{question}"), List.of(),
                60, null, null, System.getProperty("java.io.tmpdir"), List.of("memory_inspectable"),
                List.of());
        var agent = AgentFactory.create(withState);
        assertTrue(agent.answerer().capabilities().contains(AgentCapability.MEMORY_INSPECTABLE),
                "小写声明也应被接受");
        assertEquals(ConfidenceTier.T2, agent.answerer().confidenceTier());
        assertFalse(agent.memoryRoots().isEmpty(), "stateDir 应转成可读的状态目录");
    }

    @Test
    void rejectsMisspelledCapability() {
        // 拼错的能力名必须报错：静默丢弃等于悄悄把该智能体的置信层级降一档
        assertThrows(IllegalArgumentException.class, () -> new AgentConfig("x", "X", "",
                AgentConfig.TYPE_HTTP, "http://localhost:1", null, null, null, List.of(), List.of(),
                60, null, null, null, List.of("MEMORY_INSPEKTABLE"), List.of()));
    }

    @Test
    void rejectsUnknownBuiltinIdWithClearMessage() {
        // 未识别的内置 id 必须报错说明原因，不能静默返回 null 让报告少一行
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentFactory.create(AgentConfig.builtin("nonexistent", "不存在")));
        assertTrue(e.getMessage().contains("非内置智能体") || e.getMessage().contains("未识别"),
                "报错信息应点明未识别的 id，实际: " + e.getMessage());
    }

    @Test
    void builtinAgentsGetIsolatedMemoryDirs() {
        // 同一轮里的两款内置智能体不能共用记忆目录，否则对比分数就是互相污染出来的
        AgentUnderTest reference = AgentFactory.create(AgentConfig.builtin("reference", "参考"));
        AgentUnderTest degraded = AgentFactory.create(AgentConfig.builtin("degraded", "缺陷"));
        assertFalse(reference.memoryRoots().isEmpty());
        assertFalse(degraded.memoryRoots().isEmpty());
        assertFalse(reference.memoryRoots().get(0).equals(degraded.memoryRoots().get(0)),
                "两款内置智能体的记忆目录应相互隔离");
    }
}
