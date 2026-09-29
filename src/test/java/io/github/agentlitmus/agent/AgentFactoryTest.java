package io.github.zaojiaoci.agentlitmus.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                  {"id":"good","name":"正常","type":"builtin"},
                  {"id":"bad","name":"缺 endpoint","type":"http"}
                ]
                """;
        Files.writeString(file, json, StandardCharsets.UTF_8);

        List<String> warnings = new java.util.ArrayList<>();
        List<AgentUnderTest> agents = AgentFactory.loadFrom(file, warnings);

        assertEquals(1, agents.size(), "无效配置应被跳过而非中断整轮评测");
        assertFalse2(warnings.isEmpty(), "应记录警告说明原因");
    }

    @Test
    void rejectsUnknownAgentType() {
        AgentConfig config = new AgentConfig("x", "X", "", "grpc", null, null, null, List.of(), 60);
        assertThrows(IllegalArgumentException.class, () -> AgentFactory.create(config));
    }

    private static void assertFalse2(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }
}
