package io.github.agentlitmus.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.agentlitmus.util.Digests;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * OpenAI 兼容 {@code /chat/completions} 的评委客户端——<b>零新增依赖</b>，只用 JDK 自带的 HttpClient。
 * <p>
 * 走"兼容模式"而不是某家 SDK，是因为评测工具的评委必须<b>可替换</b>：
 * 绑死一家就等于把结论绑死在一家的模型版本上，换评委之后旧结论无法解释。
 * DashScope / DeepSeek / vLLM / Ollama / 任何自建网关都暴露同一套接口，
 * 因此这里只认三个东西：端点、模型名、密钥（密钥从环境变量读，绝不进配置文件与产物）。
 * <p>
 * 判定用的采样参数刻意压到最保守：{@code temperature=0}，能设 {@code seed} 就设。
 * 评委不是创作，同一份证据问两次得到两个结论，等于评测本身不可复现。
 * <p>
 * 失败语义遵循 {@link LlmClient} 的约定：不抛异常，返回 {@code null}。
 * 调用方（{@link io.github.agentlitmus.core.CaseJudge}）会把 null 记成"判定未发生"，
 * 而不是把网络故障记成被测对象的记忆缺陷。
 */
public final class OpenAiCompatClient implements LlmClient {

    /** 判词版本：判词一改，评委指纹就变，旧缓存自动失效 */
    public static final String PROMPT_VERSION = "judge-2026.10-a";

    /** DashScope 兼容模式端点（国内可直连）；可用 --judge-endpoint 换掉 */
    public static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    public static final String DEFAULT_MODEL = "qwen-plus";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final int maxRetries;
    private final HttpClient http;

    private volatile String lastError;

    public OpenAiCompatClient(String endpoint, String model, String apiKey) {
        this(endpoint, model, apiKey, Duration.ofSeconds(60), 2);
    }

    public OpenAiCompatClient(String endpoint, String model, String apiKey, Duration timeout, int maxRetries) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("评委模型需要 API Key（环境变量 LITMUS_LLM_API_KEY 或 AI_DASHSCOPE_API_KEY）");
        }
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.apiKey = apiKey.trim();
        this.timeout = timeout;
        this.maxRetries = Math.max(0, maxRetries);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * 评委指纹：端点 + 模型 + 判词版本。
     * <p>
     * 它进缓存键、也进 selftest 报告——"这批裁决是 qwen-plus 判的还是 gpt-4 判的"必须能查，
     * 否则换一个评委模型分数就变了，而读者无从知道变的是尺子还是被测对象。
     * 刻意不含密钥：指纹会被写进产物文件。
     */
    public String fingerprint() {
        return Digests.shortForm(Digests.sha256Hex(endpoint + "|" + model + "|" + PROMPT_VERSION));
    }

    public String model() {
        return model;
    }

    public String endpoint() {
        return endpoint;
    }

    /** 最近一次失败的原因（用于报告里说明"为什么这批裁决是不可判"） */
    public String lastError() {
        return lastError;
    }

    @Override
    public String complete(String prompt) {
        String body;
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            root.put("temperature", 0);
            root.put("seed", 20261023);
            root.put("stream", false);
            ArrayNode messages = root.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", "你是长期记忆评测的裁判。只依据给定材料判断，不使用你自己的知识补全事实。"
                    + "输出必须以 PASS、FAIL 或 UNKNOWN 三者之一开头。");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", prompt);
            body = MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            lastError = "构造请求失败: " + e.getMessage();
            return null;
        }

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
                    JsonNode root = MAPPER.readTree(response.body());
                    JsonNode content = root.path("choices").path(0).path("message").path("content");
                    String text = content.isMissingNode() || content.isNull() ? null : content.asText();
                    if (text == null || text.isBlank()) {
                        lastError = "评委返回空内容（HTTP 200）";
                        return null;
                    }
                    return text.trim();
                }
                // 429 与 5xx 值得退避重试；4xx 其余（密钥错、模型名错、余额不足）重试也没用
                boolean retryable = status == 429 || status >= 500;
                lastError = "HTTP " + status + " " + firstLine(response.body());
                if (!retryable || attempt == maxRetries) {
                    return null;
                }
                sleepBackoff(attempt, status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "评委调用被中断";
                return null;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (attempt == maxRetries) {
                    return null;
                }
                sleepBackoff(attempt, -1);
            }
        }
        return null;
    }

    private void sleepBackoff(int attempt, int status) {
        long millis = (long) (500L * Math.pow(2, attempt)) + (status == 429 ? 500L : 0L);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 错误响应体可能很长（含 HTML），只取第一行、限长，避免把整页 HTML 塞进报告 */
    private static String firstLine(String body) {
        if (body == null) {
            return "";
        }
        String line = body.split("\\R", 2)[0].trim();
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    /**
     * 从环境变量取密钥。
     * <p>
     * 只走环境变量是刻意的：密钥一旦进配置文件，就会跟着 {@code agents-*.json}、
     * 运行契约和 git 一起被复制出去。评测产物是要归档、要发给别人复核的。
     */
    public static String keyFromEnv() {
        for (String name : new String[]{"LITMUS_LLM_API_KEY", "AI_DASHSCOPE_API_KEY", "DASHSCOPE_API_KEY",
                "OPENAI_API_KEY", "DEEPSEEK_API_KEY"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** 环境变量里密钥的<b>名字</b>（不是值），用于报告说明"评委从哪来的" */
    public static String keySourceName() {
        for (String name : new String[]{"LITMUS_LLM_API_KEY", "AI_DASHSCOPE_API_KEY", "DASHSCOPE_API_KEY",
                "OPENAI_API_KEY", "DEEPSEEK_API_KEY"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return name;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "OpenAiCompatClient[" + model + "@" + endpoint + " fingerprint=" + fingerprint()
                + " prompt=" + PROMPT_VERSION.toLowerCase(Locale.ROOT) + "]";
    }
}
