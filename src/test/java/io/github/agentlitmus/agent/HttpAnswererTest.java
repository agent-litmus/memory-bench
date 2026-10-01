package io.github.agentlitmus.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 回答器测试：用 JDK 内置 HttpServer 起一个模拟智能体，验证 SSE 解析与请求链路。
 * <p>
 * 不依赖外部服务、不需要 API Key，离线可跑。
 */
class HttpAnswererTest {

    private HttpServer server;
    private final List<String> receivedBodies = new ArrayList<>();
    private String responseBody = "";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/analyze/stream", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            receivedBodies.add(new String(body, StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpAnswerer answerer() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpAnswerer(base, "/api/v1/analyze/stream", java.time.Duration.ofSeconds(10));
    }

    @Test
    void parsesSseAndSkipsResultEvent() {
        responseBody = "data:该岗位的核心任务\n"
                + "data:可以拆成三类。\n"
                + "data:[[RESULT]]{\"automationRatio\":0.6}\n";

        String answer = answerer().answer("s1", "订单录入");

        assertEquals("该岗位的核心任务可以拆成三类。", answer);
    }

    @Test
    void sendsQuestionAsJsonBody() {
        responseBody = "data:好的\n";

        answerer().answer("s1", "我的岗位是什么？");

        assertEquals(1, receivedBodies.size());
        assertTrue(receivedBodies.get(0).contains("我的岗位是什么？"),
                "提问应以 JSON 字段发送");
    }

    @Test
    void returnsEmptyOnNonSuccessStatus() {
        // 未配置 context 的路径会返回 404
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpAnswerer wrong = new HttpAnswerer(base, "/no-such-path", java.time.Duration.ofSeconds(5));

        assertEquals("", wrong.answer("s1", "x"),
                "非 2xx 响应应返回空串，由判定器记为遗漏，而不是抛异常中断评测");
    }

    @Test
    void parseSseHandlesEmptyAndBlank() {
        assertEquals("", HttpAnswerer.parseSse(null));
        assertEquals("", HttpAnswerer.parseSse("   "));
        assertEquals("正文", HttpAnswerer.parseSse("data:正文\n"));
    }
}
