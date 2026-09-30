package io.github.agentlitmus.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentlitmus.memory.LongTermMemory;
import io.github.agentlitmus.memory.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 真实大模型智能体（OpenAI 兼容接口）——第二款「会真实调用模型」的被测对象。
 * <p>
 * 它回答了本项目的一个关键命题：评测能不能落在<b>真实的、非确定性的智能体</b>上，
 * 而不只是在内置的确定性回答器上自测。
 * <p>
 * 工作方式与其他内置被测对象一致：记忆由执行器注入 {@link LongTermMemory}，
 * 本类只负责「召回相关记忆 → 交给大模型组织回答」。
 * 因此它天然支持跨会话用例——记忆在文件里，不在会话上下文里。
 * <p>
 * 设计取舍：
 * <ul>
 *   <li>只用 JDK 内置 {@link HttpClient} + Jackson，<b>不引入任何框架</b>，
 *       与本项目「核心库零框架依赖」的主张一致；</li>
 *   <li>默认走 DashScope（通义千问）的 OpenAI 兼容端点，复用已有的
 *       {@code AI_DASHSCOPE_API_KEY}，无需额外购买；任何 OpenAI 兼容服务都能接
 *       （改 baseUrl + apiKey 即可）；</li>
 *   <li>{@code temperature=0} 尽量降低波动；调用失败时返回空回答并记日志，
 *       由评测归为「无响应」，绝不伪造内容——伪造会让边界识别维度虚高。</li>
 * </ul>
 */
public class LlmAnswerer implements Answerer {

    private static final Logger log = LoggerFactory.getLogger(LlmAnswerer.class);

    /** 记忆里没有相关信息时，模型应如实说明（与 RecallAnswerer 保持一致口径） */
    public static final String UNKNOWN = "我不掌握相关信息。";

    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public static final String DEFAULT_MODEL = "qwen-flash";

    private static final String SYSTEM_PROMPT = """
            你是一个严格依据「长期记忆」回答问题的智能体。
            规则：
            1. 只能使用下面「已知信息」里的内容作答，不得自行编造或推断。
            2. 用中文、简洁作答，直接给出关键值（编号、城市、数值、名称等），不要冗长解释。
            3. 若「已知信息」为空或都与问题无关，只回答：我不掌握相关信息。
            4. 不要复述你不知道的内容，也不要猜测。
            """;

    private final LongTermMemory memory;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int topK;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmAnswerer(LongTermMemory memory) {
        this(memory, DEFAULT_BASE_URL, resolveApiKey(), DEFAULT_MODEL, 5, Duration.ofSeconds(60));
    }

    public LlmAnswerer(LongTermMemory memory, String baseUrl, String apiKey,
                       String model, int topK, Duration timeout) {
        this.memory = memory;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.topK = Math.max(1, topK);
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 未显式传入时从环境变量取 Key（沿用 toolkit 里已有的 AI_DASHSCOPE_API_KEY） */
    private static String resolveApiKey() {
        String key = System.getenv("AI_DASHSCOPE_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("LITMUS_LLM_API_KEY");
        }
        return key == null ? "" : key.trim();
    }

    @Override
    public String answer(String sessionId, String question) {
        if (apiKey.isEmpty()) {
            log.warn("未配置 API Key（AI_DASHSCOPE_API_KEY / LITMUS_LLM_API_KEY），无法调用模型");
            return "";
        }
        List<MemoryEntry> hits = memory.recall(sessionId, question, topK);
        String known = hits.isEmpty() ? "" : hits.stream()
                .map(MemoryEntry::content)
                .collect(Collectors.joining("；"));

        String userContent = "已知信息：\n" + (known.isEmpty() ? "（无）" : known)
                + "\n\n问题：" + question;

        try {
            String body = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", model)
                    .put("temperature", 0)
                    .put("stream", false)
                    .set("messages", mapper.createArrayNode()
                            .add(mapper.createObjectNode().put("role", "system").put("content", SYSTEM_PROMPT))
                            .add(mapper.createObjectNode().put("role", "user").put("content", userContent))));

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("模型调用失败：HTTP {} - {}", response.statusCode(),
                        response.body() == null ? "" : response.body().length() > 300
                                ? response.body().substring(0, 300) : response.body());
                return "";
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String text = content.isMissingNode() ? "" : content.asText("").trim();
            return text.isEmpty() ? UNKNOWN : text;
        } catch (Exception e) {
            log.warn("模型调用异常：{}", e.getMessage());
            return "";
        }
    }
}
