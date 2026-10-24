package io.github.agentlitmus.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * 通用 MCP（Model Context Protocol，stdio 传输）适配器：把<b>以 MCP server 形态
 * 暴露对话工具的智能体</b>接入评测，配置即接入、零代码。
 * <p>
 * 2026 年的智能体应用（桌面助理、IDE 副驾、本地 agent 运行时）大量以 MCP server 形态
 * 对外提供服务。本适配器按 MCP stdio 传输驱动它们：
 * {@code initialize → notifications/initialized → tools/call}，一次 {@code tools/call}
 * 即一个评测回合；工具名与参数名可配，默认 {@code chat(text)}。
 * <p>
 * <b>会话与重置语义（重要边界）</b>：MCP 没有会话概念，本适配器用「每会话一个常驻进程」
 * 表达会话；{@link #resetSession} 直接<b>销毁进程</b>（下次提问重新握手）。
 * 因此：被测对象必须把长期记忆持久化在<b>进程之外</b>（文件 / 数据库，随家目录走），
 * 否则跨会话用例测到的是「重启即失忆」——那是一个真结论，但要在报告里当作该智能体的
 * 实况来读，而不是适配器的缺陷。
 * <p>
 * <b>已知边界</b>：服务端在回合中发起的反向请求（sampling / elicitation / roots）
 * 本适配器不代答，该回合按超时记「无响应」——如实暴露，不假装测过。
 */
public class McpAnswerer extends StdioRpcAnswerer {

    private static final Logger log = LoggerFactory.getLogger(McpAnswerer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** MCP 协议版本：握手时声明；服务端不兼容会在 initialize 响应里如实暴露 */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final String tool;
    private final String messageArg;
    private final String sessionArg;
    private final String homeEnvVar;
    private final Path homeTemplate;

    /**
     * @param command    可执行命令（第一个元素是可执行文件，其余为固定参数）
     * @param tool       对话工具名，默认 {@code chat}
     * @param messageArg 承载用户消息的参数名，默认 {@code text}
     * @param sessionArg 会话 ID 参数名（部分智能体要求）；null = 不传
     * @param homeEnvVar 家目录环境变量名（记忆隔离用）；null = 不隔离
     * @param homeTemplate 家目录模板；null = 空家目录
     */
    public McpAnswerer(List<String> command, String tool, String messageArg, String sessionArg,
                       String homeEnvVar, Path homeTemplate, Path stateRoot, Duration timeout) {
        super(command, timeout, stateRoot);
        this.tool = tool == null || tool.isBlank() ? "chat" : tool.trim();
        this.messageArg = messageArg == null || messageArg.isBlank() ? "text" : messageArg.trim();
        this.sessionArg = sessionArg == null || sessionArg.isBlank() ? null : sessionArg.trim();
        this.homeEnvVar = homeEnvVar == null || homeEnvVar.isBlank() ? null : homeEnvVar.trim();
        this.homeTemplate = homeTemplate;
    }

    @Override
    protected void configureEnvironment(ProcessBuilder pb, String sessionId, Path home) throws IOException {
        if (homeEnvVar == null || home == null) {
            return;
        }
        if (homeTemplate != null && Files.isDirectory(homeTemplate)) {
            try (var files = Files.walk(homeTemplate)) {
                for (Path src : files.toList()) {
                    if (src.equals(homeTemplate)) {
                        continue;
                    }
                    Path dst = home.resolve(homeTemplate.relativize(src).toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else if (Files.notExists(dst)) {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        }
        pb.environment().put(homeEnvVar, home.toString());
    }

    @Override
    protected String converse(Session session, String sessionId, String question) {
        long timeoutMillis = timeout.toMillis();
        if (!session.initialized) {
            var initParams = MAPPER.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
            initParams.set("capabilities", MAPPER.createObjectNode());
            initParams.set("clientInfo", MAPPER.createObjectNode()
                    .put("name", "agentlitmus")
                    .put("version", "1.0.0"));
            JsonNode init = session.peer.request("initialize", initParams, timeoutMillis);
            if (init == null) {
                log.warn("MCP initialize 无响应（{}）", sessionId);
                return "";
            }
            session.peer.notify("notifications/initialized", null);
            session.initialized = true;
        }
        var arguments = MAPPER.createObjectNode().put(messageArg, question);
        if (sessionArg != null) {
            arguments.put(sessionArg, sessionId);
        }
        JsonNode res = session.peer.request("tools/call", MAPPER.createObjectNode()
                .put("name", tool)
                .set("arguments", arguments), timeoutMillis);
        return extractText(res);
    }

    /**
     * MCP 的重置语义 = 销毁进程重启：MCP 没有会话概念，这是唯一诚实的实现。
     * 长期记忆必须由被测方持久化在进程外，否则测到的就是「重启即失忆」——见类注释。
     */
    @Override
    public void resetSession(String sessionId) {
        destroySession(sessionId);
    }

    /** 抽取正文：{@code result.content[]} 里的 text 块拼接；isError 与 null 如实返回空 */
    static String extractText(JsonNode res) {
        if (res == null) {
            return "";
        }
        JsonNode result = res.path("result");
        if (result.path("isError").asBoolean(false)) {
            log.warn("MCP tools/call 返回 isError：{}", truncate(result));
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText("text"))) {
                sb.append(block.path("text").asText(""));
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            log.warn("MCP 响应中未抽取到正文：{}", truncate(res));
        }
        return text;
    }

    private static String truncate(JsonNode node) {
        String s = node.toString();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
