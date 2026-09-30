package io.github.agentlitmus.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * 通过 HTTP 调用真实智能体的回答器。
 * <p>
 * 这是把评测指向<b>真实智能体</b>的桥梁：内置的两款（reference / degraded）用于验证尺子本身有效，
 * 而真正有说服力的评测必须落在真实系统上——评审关心的是"能否在真实智能体的运行证据上完成评测"。
 * <p>
 * 刻意使用 <b>JDK 内置的 {@code java.net.http.HttpClient}</b>，不引入任何 HTTP 库，
 * 以保持核心库零框架依赖。
 * <p>
 * 默认适配本仓库同源的 Spring AI 流式接口（SSE），可通过构造参数适配其他端点。
 */
public class HttpAnswerer implements Answerer {

    /** 流式结果事件的前缀，不属于自然语言正文，解析时跳过 */
    public static final String RESULT_PREFIX = "[[RESULT]]";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String path;
    private final String resetPath;
    private final Duration timeout;

    public HttpAnswerer(String baseUrl) {
        this(baseUrl, "/api/analyze/stream", Duration.ofSeconds(120));
    }

    public HttpAnswerer(String baseUrl, String path, Duration timeout) {
        this(baseUrl, path, null, timeout);
    }

    /**
     * @param resetPath 会话重置路径（跨会话用例用，通常以 DELETE 调用）；为 null 表示不支持重置
     */
    public HttpAnswerer(String baseUrl, String path, String resetPath, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.path = path;
        this.resetPath = (resetPath == null || resetPath.isBlank()) ? null : resetPath;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 重置会话上下文：调用被测服务声明的重置端点（清空对话，保留其长期记忆）。
     * 未配置 resetPath 时不做任何事——跨会话用例会退化为同会话用例，判定时应知悉该差异。
     */
    @Override
    public void resetSession(String sessionId) {
        if (resetPath == null) {
            return;
        }
        try {
            // 兼容两种重置端点形态：
            //   - 路径参数式（如 /api/memory/{sessionId}，部分 HTTP 智能体采用）
            //   - 查询参数式（如 /api/reset?sessionId=xxx，历史默认）
            String placeholder = "{sessionId}";
            String uri = resetPath.contains(placeholder)
                    ? baseUrl + resetPath.replace(placeholder, encode(sessionId))
                    : baseUrl + resetPath + "?sessionId=" + encode(sessionId);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(Duration.ofSeconds(30))
                    .DELETE()
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // 重置失败不应中断评测
        }
    }

    @Override
    public String answer(String sessionId, String question) {
        String url = baseUrl + path + "?sessionId=" + encode(sessionId);
        String body = requestBody(question);
        // 被测服务可能启用了限流（如令牌桶）。429 时尊重并指数退避重试，
        // 而非直接判失败——这样评测结果反映真实记忆能力，而非被测服务的限流策略。
        int maxAttempts = 8;
        long backoffMillis = 2000;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                // 限流 429 与服务端瞬时 5xx：尊重并指数退避重试，而非直接判失败——
                // 这样评测结果反映真实记忆能力，而非被测服务的限流/抖动策略（避免污染分数）。
                if (code == 429 || code / 100 == 5) {
                    if (attempt < maxAttempts) {
                        Thread.sleep(backoffMillis);
                        backoffMillis = Math.min(backoffMillis * 2, 30000);
                        continue;
                    }
                    return "";
                }
                if (code / 100 != 2) {
                    return "";
                }
                return parseSse(response.body());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "";
            } catch (Exception e) {
                // 单个请求失败不应中断整轮评测：返回空串，由判定器判为"遗漏"
                return "";
            }
        }
        return "";
    }

    /**
     * 解析 SSE 文本：拼接普通正文片段，跳过结构化结果事件。
     * 这样拿到的是用户实际看到的自然语言回答，而非 JSON 源码。
     */
    static String parseSse(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String content = line.substring("data:".length()).trim();
            if (content.isEmpty() || content.startsWith(RESULT_PREFIX)) {
                continue;
            }
            sb.append(content);
        }
        return sb.toString();
    }

    private String requestBody(String question) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("jobDescription", question == null ? "" : question);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // 回退到手工拼接（JSON 内容已做最小转义）
            return "{\"jobDescription\":\"" + escape(question) + "\"}";
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
