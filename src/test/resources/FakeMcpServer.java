import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 测试专用的 MCP 假服务器：单文件 Java 程序，被 StdioAdapterTest 以
 * {@code java FakeMcpServer.java} 拉起，在 stdin/stdout 上说 MCP stdio 传输。
 * <p>
 * 行为契约（测试据此断言）：
 * <ul>
 *   <li>initialize → 回协议版本与 capabilities；</li>
 *   <li>tools/call（name=chat，text 参数）→ content text "MCP:&lt;text&gt;"；
 *       text 为 {@code ERR} 时回 {@code isError:true}——适配器必须按无正文处理。</li>
 * </ul>
 */
public class FakeMcpServer {

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
                continue;    // notifications/initialized 等无 method 字段的消息直接忽略
            }
            switch (method) {
                case "initialize" -> out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}");
                case "tools/call" -> {
                    String text = stringField(line, "text");
                    if ("ERR".equals(text)) {
                        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                                + ",\"result\":{\"isError\":true,\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"boom\"}]}}");
                    } else {
                        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                                + ",\"result\":{\"isError\":false,\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"MCP:" + text + "\"}]}}");
                    }
                }
                default -> out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"error\":{\"code\":-32601,\"message\":\"unknown method " + method + "\"}}");
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
