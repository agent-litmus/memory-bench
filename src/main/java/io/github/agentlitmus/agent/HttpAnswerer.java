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
    private final Duration timeout;

    public HttpAnswerer(String baseUrl) {
        this(baseUrl, "/api/analyze/stream", Duration.ofSeconds(120));
    }

    public HttpAnswerer(String baseUrl, String path, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.path = path;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String answer(String sessionId, String question) {
        String url = baseUrl + path + "?sessionId=" + encode(sessionId);
        String body = requestBody(question);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return "";
            }
            return parseSse(response.body());
        } catch (Exception e) {
            // 单个请求失败不应中断整轮评测：返回空串，由判定器判为"遗漏"
            return "";
        }
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
