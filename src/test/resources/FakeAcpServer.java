import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试专用的 ACP 假服务器：单文件 Java 程序，被 StdioAdapterTest 以
 * {@code java FakeAcpServer.java} 拉起，在 stdin/stdout 上说 ACP 协议。
 * <p>
 * 行为契约（测试据此断言）：
 * <ul>
 *   <li>initialize → agentInfo；</li>
 *   <li>session/new → 递增的 sessionId（acp-s-1、acp-s-2…），并把标记文件写进
 *       {@code FAKE_HOME} 指向的目录——用于验证适配器的家目录隔离真的生效；</li>
 *   <li>session/prompt → 先推一条 thought 通知（适配器必须忽略），再回
 *       "ANSWER:&lt;正文&gt;@&lt;会话号&gt;"；正文为 {@code NO-CONTENT} 时结果不带 content，
 *       只靠之前的 message 通知出答案——覆盖流式抽取路径。</li>
 * </ul>
 * 刻意只用 JDK 自带能力 + 正则解析：单文件源码启动没有任何第三方类路径。
 */
public class FakeAcpServer {

    private static int sessionSeq = 0;

    public static void main(String[] args) throws Exception {
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            long id = longField(line, "id");
            String method = stringField(line, "method");
            if (method == null) {
                continue;
            }
            switch (method) {
                case "initialize" -> out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"agentInfo\":{\"name\":\"fake-acp\"}}}");
                case "session/new" -> {
                    String sessionId = "acp-s-" + (++sessionSeq);
                    markHome(sessionId);
                    out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"result\":{\"sessionId\":\"" + sessionId + "\"}}");
                }
                case "session/prompt" -> {
                    String text = stringField(line, "text");
                    String sessionId = stringField(line, "sessionId");
                    // 思考过程：适配器必须跳过，混进答案即测试失败
                    out.println("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":"
                            + "{\"sessionId\":\"" + sessionId + "\",\"update\":"
                            + "{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":"
                            + "{\"text\":\"THOUGHT-MUST-NOT-APPEAR\"}}}}");
                    if ("NO-CONTENT".equals(text)) {
                        // 结果不带 content：答案只能来自流式消息块
                        out.println("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":"
                                + "{\"sessionId\":\"" + sessionId + "\",\"update\":"
                                + "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":"
                                + "{\"text\":\"STREAMED:NO-CONTENT\"}}}}");
                        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"stopReason\":\"end_turn\"}}");
                    } else {
                        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                                + ",\"result\":{\"stopReason\":\"end_turn\",\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"ANSWER:" + text + "@" + sessionId + "\"}]}}");
                    }
                }
                default -> out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"unknown method " + method + "\"}}");
            }
        }
    }

    /** 把标记文件写进 FAKE_HOME：证明适配器给每个会话注入了独立家目录 */
    private static void markHome(String sessionId) {
        String home = System.getenv("FAKE_HOME");
        if (home != null) {
            try {
                Files.writeString(Path.of(home, "seen-" + sessionId + ".txt"), "ok");
            } catch (Exception ignored) {
                // 测试断言会失败；这里不能让服务器崩掉掩盖真实原因
            }
        }
    }

    private static long longField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String stringField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
