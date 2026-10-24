package io.github.agentlitmus.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACP / MCP 通用适配器的端到端测试：拉起<b>真实子进程</b>（单文件 Java 假服务器），
 * 走完整的「进程启动 → JSON-RPC 握手 → 会话 → 提问 → 抽取正文」链路。
 * <p>
 * 不 mock 传输层——这两个适配器的价值恰恰在进程与协议边界上：读线程不死等、
 * 思考块不混入答案、家目录隔离真的注入、重置语义真的保留记忆。
 * 用 {@code java FakeXxxServer.java} 单文件源码启动，跨平台且零第三方依赖。
 */
class StdioAdapterTest {

    @TempDir
    Path tmp;

    private static String javaBin() {
        String bin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? bin + ".exe" : bin;
    }

    /** 把测试资源里的假服务器源码落到磁盘（单文件源码启动需要文件路径） */
    private Path materialize(String resource) throws IOException {
        Path file = tmp.resolve(resource);
        try (InputStream in = StdioAdapterTest.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("测试资源缺失: " + resource);
            }
            Files.copy(in, file);
        }
        return file;
    }

    @Test
    void acpFullRoundTripIgnoresThoughtAndReportsSession() throws Exception {
        Path server = materialize("FakeAcpServer.java");
        Path template = Files.createDirectories(tmp.resolve("home-template"));
        Files.writeString(template.resolve("creds.txt"), "fake-key");
        Path stateRoot = tmp.resolve("acp-state");

        try (AcpAnswerer acp = new AcpAnswerer(List.of(javaBin(), server.toString()),
                "FAKE_HOME", template, stateRoot, Duration.ofSeconds(30))) {

            // 1) 常规问答：答案来自 result.content，思考块不得混入，且带会话号
            String first = acp.answer("case-a1", "hello acp");
            assertEquals("ANSWER:hello acp@acp-s-1", first);

            // 2) 家目录隔离：模板被铺进会话目录，环境变量真的注入（服务器在 FAKE_HOME 落了标记）
            assertTrue(Files.exists(stateRoot.resolve("case-a1").resolve("creds.txt")),
                    "homeTemplate 应被拷入会话家目录");
            assertTrue(Files.exists(stateRoot.resolve("case-a1").resolve("seen-acp-s-1.txt")),
                    "FAKE_HOME 应指向会话家目录（服务器标记文件缺失）");

            // 3) 重置语义：丢会话句柄不杀进程——下次提问拿到新会话号，记忆目录不动
            acp.resetSession("case-a1");
            String afterReset = acp.answer("case-a1", "again");
            assertEquals("ANSWER:again@acp-s-2", afterReset);
            assertTrue(Files.exists(stateRoot.resolve("case-a1").resolve("seen-acp-s-2.txt")));
        }
    }

    @Test
    void acpStreamedAnswerPathFallsBackToMessageChunks() throws Exception {
        Path server = materialize("FakeAcpServer.java");
        try (AcpAnswerer acp = new AcpAnswerer(List.of(javaBin(), server.toString()),
                null, null, tmp.resolve("acp-stream"), Duration.ofSeconds(30))) {
            // result 无 content：答案只能从流式 message 通知里抽
            assertEquals("STREAMED:NO-CONTENT", acp.answer("case-a2", "NO-CONTENT"));
        }
    }

    @Test
    void mcpFullRoundTripAndErrorPath() throws Exception {
        Path server = materialize("FakeMcpServer.java");
        try (McpAnswerer mcp = new McpAnswerer(List.of(javaBin(), server.toString()),
                null, null, null, null, null,
                tmp.resolve("mcp-state"), Duration.ofSeconds(30))) {

            assertEquals("MCP:hello mcp", mcp.answer("case-m1", "hello mcp"));

            // isError=true：按「无正文」处理，不能把错误说明当成答案
            assertEquals("", mcp.answer("case-m1", "ERR"));

            // 重置 = 进程重启：下次提问重新握手后仍能正常回答
            mcp.resetSession("case-m1");
            assertEquals("MCP:after restart", mcp.answer("case-m1", "after restart"));
        }
    }

    @Test
    void factoryWiresAcpAndMcpFromConfig() {
        // 工厂只组装不连进程（首问才拉起），因此这两条不需要假服务器在场；
        // 工厂会给 answerer 叠能力层，断言前先剥掉
        AgentUnderTest acp = AgentFactory.create(new AgentConfig("fake-acp", "Fake ACP", "",
                AgentConfig.TYPE_ACP, null, null, null, "echo", List.of(), List.of(),
                30, null, null, null, List.of(), List.of(), null, null, null, null, null));
        assertTrue(CapabilityOverlay.unwrap(acp.answerer()) instanceof AcpAnswerer);

        AgentUnderTest mcp = AgentFactory.create(new AgentConfig("fake-mcp", "Fake MCP", "",
                AgentConfig.TYPE_MCP, null, null, null, "echo", List.of(), List.of(),
                30, null, null, null, List.of(), List.of(),
                "chat", "text", null, null, null));
        assertTrue(CapabilityOverlay.unwrap(mcp.answerer()) instanceof McpAnswerer);
    }
}
