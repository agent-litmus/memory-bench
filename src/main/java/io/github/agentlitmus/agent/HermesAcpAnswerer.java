package io.github.agentlitmus.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通过 ACP（Agent Communication Protocol，JSON-RPC over stdio）驱动 <b>Hermes Agent</b> 的被测对象。
 * <p>
 * 为什么走 ACP 而不是命令行：
 * <ul>
 *   <li>命令行 {@code chat} 是一次性进程，且实测它不认自定义端点（base_url 被忽略），
 *       无法用已有的 DashScope Key；</li>
 *   <li>ACP 是 Hermes 面向编辑器（VS Code / Zed / JetBrains）暴露的正式集成接口，
 *       跑的是<b>真正的 Hermes 智能体循环</b>——会话与长期记忆都在它内部，
 *       这正是「长期记忆评测」要测的对象；</li>
 *   <li>常驻进程 + 按会话复用，避免每轮重新拉起进程。</li>
 * </ul>
 * <p>
 * <b>隔离策略（关键）</b>：每个 memory-bench 会话分配一个独立的 {@code HERMES_HOME}
 * （从用户主目录拷贝 config.yaml 与 .env），因此各用例的长期记忆互不污染，
 * 与 kylinbot-adapter 用 {@code --config-dir} 隔离的思路一致。
 * 不这么做的话，上一个用例记住的验证码会被下一个用例召回到，评测结果不可信。
 * <p>
 * <b>重置语义</b>：{@link #resetSession(String)} 只丢弃 ACP 会话句柄（下次提问新建会话），
 * 但保留该用例的 {@code HERMES_HOME}——长期记忆仍在，
 * 这正是跨会话长期保持用例要测的边界。
 */
public class HermesAcpAnswerer implements Answerer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HermesAcpAnswerer.class);

    private static final String DEFAULT_BIN =
            System.getProperty("user.home") + "/.kylin-agent-runtime/agent-runtime/venv/bin/hermes-acp";

    private final String binary;
    private final Path templateHome;
    private final Path root;
    private final String cwd;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, AcpSession> sessions = new LinkedHashMap<>();

    public HermesAcpAnswerer() {
        this(DEFAULT_BIN,
                Path.of(System.getProperty("user.home"), ".kylin-agent-runtime"),
                Path.of(System.getProperty("java.io.tmpdir"), "litmus-hermes"),
                System.getProperty("user.dir"),
                Duration.ofSeconds(120));
    }

    public HermesAcpAnswerer(String binary, Path templateHome, Path root, String cwd, Duration timeout) {
        this.binary = binary == null || binary.isBlank() ? DEFAULT_BIN : binary;
        this.templateHome = templateHome;
        this.root = root;
        this.cwd = cwd == null || cwd.isBlank() ? "." : cwd;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
    }

    @Override
    public String answer(String sessionId, String question) {
        AcpSession session = sessions.get(sessionId);
        if (session == null) {
            session = start(sessionId);
            if (session == null) {
                return "";
            }
            sessions.put(sessionId, session);
        }
        try {
            if (session.hermesSessionId == null && !newHermesSession(session)) {
                return "";
            }
            JsonNode res = session.request("session/prompt", mapper.createObjectNode()
                    .put("sessionId", session.hermesSessionId)
                    .set("prompt", mapper.createArrayNode()
                            .add(mapper.createObjectNode()
                                    .put("type", "text")
                                    .put("text", question))), timeout);
            return extractText(res);
        } catch (Exception e) {
            log.warn("ACP 调用异常（{}）：{}", sessionId, e.getMessage());
            return "";
        }
    }

    /** 重置会话上下文：丢弃会话句柄，下次提问新建会话（长期记忆所在的 HOME 保留） */
    @Override
    public void resetSession(String sessionId) {
        AcpSession session = sessions.get(sessionId);
        if (session != null && session.hermesSessionId != null) {
            log.debug("ACP 重置会话 {}（丢弃 Hermes 会话 {}，保留记忆目录）", sessionId, session.hermesSessionId);
            session.hermesSessionId = null;
        }
    }

    private boolean newHermesSession(AcpSession session) {
        // mcpServers 是 ACP 的必填字段（缺了会报 -32602 Invalid params），不接外部工具时给空数组
        JsonNode res = session.request("session/new", mapper.createObjectNode()
                .put("cwd", cwd)
                .set("mcpServers", mapper.createArrayNode()), timeout);
        String id = res == null ? null : res.path("result").path("sessionId").asText(null);
        if (id == null || id.isBlank()) {
            log.warn("ACP 建会话失败：{}", res == null ? "(无响应)" : res.toString());
            return false;
        }
        session.hermesSessionId = id;
        return true;
    }

    /** 启动一个专属进程：独立 HOME（含配置副本）+ initialize */
    private AcpSession start(String sessionId) {
        try {
            Path home = root.resolve(sanitize(sessionId));
            Files.createDirectories(home);
            copyIfAbsent("config.yaml", home);
            copyIfAbsent(".env", home);

            ProcessBuilder pb = new ProcessBuilder(binary);
            pb.environment().put("HERMES_HOME", home.toString());
            Process process = pb.start();
            AcpSession session = new AcpSession(process, home);
            JsonNode res = session.request("initialize", mapper.createObjectNode().put("protocolVersion", 1), timeout);
            if (res == null) {
                log.warn("ACP initialize 无响应（{}）", sessionId);
            } else {
                log.debug("ACP initialize 成功：{}", res.path("result").path("agentInfo").toString());
            }
            return session;
        } catch (Exception e) {
            log.warn("启动 Hermes ACP 进程失败（{}）：{}", sessionId, e.getMessage());
            return null;
        }
    }

    private void copyIfAbsent(String name, Path home) throws IOException {
        Path src = templateHome.resolve(name);
        Path dst = home.resolve(name);
        if (Files.exists(src) && !Files.exists(dst)) {
            Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * 抽取正文。注意 ACP 会把「思考过程」也流式推过来（{@code agent_thought_chunk}），
     * 那不是回答，必须只取消息块（含 message 的 sessionUpdate），否则会把模型的内心独白当成答案。
     */
    private String extractText(JsonNode res) {
        if (res == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // 1) 优先取最终结果里的正文
        collectBlocks(res.path("result"), sb);
        // 2) 否则拼接流式通知里的消息块（跳过思考过程与工具调用）
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

    private void collectBlocks(JsonNode node, StringBuilder sb) {
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

    @Override
    public void close() {
        for (AcpSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    /** 一个 Hermes ACP 进程及其会话状态 */
    private final class AcpSession {
        private final Process process;
        private final Path home;
        private final BufferedWriter out;
        /**
         * 异步读取 Hermes 的 stdout。
         * 不能直接 readLine()——它会无限阻塞（Hermes 不响应时永远等下去），
         * 评测工具绝不能挂死，因此用后台线程 + 队列超时取行。
         */
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final Thread readerThread;
        private final AtomicLong idSeq = new AtomicLong(1);
        private String hermesSessionId;

        AcpSession(Process process, Path home) {
            this.process = process;
            this.home = home;
            this.out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.readerThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        queue.put(line);
                    }
                } catch (Exception ignored) {
                    // 进程退出即结束读取
                }
            }, "hermes-acp-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        /** 发一条 JSON-RPC 请求，读到同 id 响应为止；期间的通知挂到结果的 notifications 字段 */
        JsonNode request(String method, JsonNode params, Duration wait) {
            try {
                long id = idSeq.getAndIncrement();
                ObjectNode req = mapper.createObjectNode()
                        .put("jsonrpc", "2.0")
                        .put("id", id)
                        .put("method", method);
                req.set("params", params);
                out.write(mapper.writeValueAsString(req));
                out.newLine();
                out.flush();

                long deadline = System.currentTimeMillis() + wait.toMillis();
                List<JsonNode> notifications = new ArrayList<>();
                while (System.currentTimeMillis() < deadline) {
                    long remaining = deadline - System.currentTimeMillis();
                    String line = queue.poll(remaining, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        break; // 超时：不再无限等待
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node;
                    try {
                        node = mapper.readTree(line);
                    } catch (Exception e) {
                        continue; // 启动横幅等非 JSON 行
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("ACP <- {}", line.length() > 300 ? line.substring(0, 300) : line);
                    }
                    if (node.path("id").asLong(-1) == id) {
                        if (!notifications.isEmpty()) {
                            ((ObjectNode) node).set("notifications", mapper.valueToTree(notifications));
                        }
                        return node;
                    }
                    notifications.add(node);
                }
                log.warn("ACP 请求 {} 超时", method);
                return null;
            } catch (Exception e) {
                log.warn("ACP 请求 {} 失败：{}", method, e.getMessage());
                return null;
            }
        }

        void close() {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
