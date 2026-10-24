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
 * 通用 ACP（Agent Communication Protocol，JSON-RPC over stdio）适配器：
 * <b>配置即接入任意 ACP 智能体，零代码</b>。
 * <p>
 * ACP 是编辑器（VS Code / Zed / JetBrains）驱动智能体的开放协议——凡是能被编辑器接上的
 * ACP 智能体，都能用本适配器直接评测。协议流程：
 * {@code initialize → session/new → session/prompt}，与 {@link HermesAcpAnswerer}
 * 驱动 Hermes 的方式一致（Hermes 走自己的内置实现，本类是它的通用化，机制同源）。
 * <p>
 * <b>隔离</b>：每个会话一个独立进程；配置了 {@code homeEnvVar} 时再给每个会话一个独立
 * 家目录（从 {@code homeTemplate} 铺模板），被测对象的长期记忆随家目录走、用例间互不污染——
 * 与 kylinbot 适配器 {@code --config-dir}、Hermes {@code HERMES_HOME} 是同一个道理。
 * <p>
 * <b>重置语义</b>：{@link #resetSession} 只丢弃 ACP 会话句柄（下次提问 session/new 新建会话），
 * 进程与家目录保留——长期记忆仍在，这正是跨会话长期保持用例要测的边界。
 * <p>
 * 已知边界：智能体若在回合内要求客户端侧回调（ACP 的 sampling / elicitation），
 * 本适配器不代答，该回合会按超时记「无响应」——如实暴露，不假装测过。
 */
public class AcpAnswerer extends StdioRpcAnswerer {

    private static final Logger log = LoggerFactory.getLogger(AcpAnswerer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String homeEnvVar;
    private final Path homeTemplate;

    /**
     * @param command     可执行命令（第一个元素是可执行文件，其余为固定参数）
     * @param homeEnvVar  家目录环境变量名（如 {@code HERMES_HOME}）；null = 不隔离家目录
     * @param homeTemplate 模板目录，内容在首次启动时拷入每个会话的家目录；null = 空家目录
     * @param stateRoot   各会话家目录的父目录（建议每款智能体一个独立子目录）
     */
    public AcpAnswerer(List<String> command, String homeEnvVar, Path homeTemplate,
                       Path stateRoot, Duration timeout) {
        super(command, timeout, stateRoot);
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
            JsonNode init = session.peer.request("initialize",
                    MAPPER.createObjectNode().put("protocolVersion", 1), timeoutMillis);
            if (init == null) {
                log.warn("ACP initialize 无响应（{}）", sessionId);
                return "";
            }
            session.initialized = true;
        }
        if (session.remoteSessionId == null && !newSession(session, timeoutMillis)) {
            return "";
        }
        JsonNode res = session.peer.request("session/prompt", MAPPER.createObjectNode()
                        .put("sessionId", session.remoteSessionId)
                        .set("prompt", MAPPER.createArrayNode()
                                .add(MAPPER.createObjectNode()
                                        .put("type", "text")
                                        .put("text", question))),
                timeoutMillis);
        return extractText(res);
    }

    @Override
    public void resetSession(String sessionId) {
        // 只丢 ACP 会话句柄，进程与家目录（长期记忆）保留——跨会话用例测的正是这个边界
        StdioRpcAnswerer.Session session = lookupSession(sessionId);
        if (session != null && session.remoteSessionId != null) {
            log.debug("ACP 重置会话 {}（丢弃远端会话 {}，保留记忆目录）", sessionId, session.remoteSessionId);
            session.remoteSessionId = null;
        }
    }

    private boolean newSession(Session session, long timeoutMillis) {
        // mcpServers 是 ACP 的必填字段（缺了会报 -32602 Invalid params），不接外部工具时给空数组
        JsonNode res = session.peer.request("session/new", MAPPER.createObjectNode()
                .put("cwd", System.getProperty("user.dir", "."))
                .set("mcpServers", MAPPER.createArrayNode()), timeoutMillis);
        String id = res == null ? null : res.path("result").path("sessionId").asText(null);
        if (id == null || id.isBlank()) {
            log.warn("ACP 建会话失败：{}", res == null ? "(无响应)" : res);
            return false;
        }
        session.remoteSessionId = id;
        return true;
    }

    /**
     * 抽取正文。ACP 会把「思考过程」（{@code agent_thought_chunk}）也流式推过来，
     * 那不是回答——只取消息块，否则会把模型的内心独白当成答案。
     */
    static String extractText(JsonNode res) {
        if (res == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        collectBlocks(res.path("result"), sb);
        if (sb.length() == 0) {
            for (JsonNode n : res.path("notifications")) {
                JsonNode update = n.path("params").path("update");
                String kind = update.path("sessionUpdate").asText("");
                if (kind.contains("message")) {
                    String t = update.path("content").path("text").asText("");
                    if (!t.isBlank()) {
                        sb.append(t);
                    }
                }
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            log.warn("ACP 响应中未抽取到正文：{}",
                    res.toString().length() > 300 ? res.toString().substring(0, 300) : res);
        }
        return text;
    }

    private static void collectBlocks(JsonNode node, StringBuilder sb) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                sb.append(block.path("text").asText(""));
            }
        } else if (content.isTextual()) {
            sb.append(content.asText());
        } else {
            sb.append(node.path("text").asText(""));
        }
    }
}
