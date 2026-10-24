package io.github.agentlitmus.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 子进程上的 JSON-RPC 对端（newline-delimited，stdio 双向）。
 * <p>
 * ACP 与 MCP 的 stdio 传输都是「一行一个 JSON-RPC 消息」，本类只做这一层：
 * 发请求、按 id 收响应、把期间到达的通知攒起来随响应返回、带超时、优雅关闭。
 * 协议语义（initialize / session/new / tools/call …）由各适配器自己组织。
 * <p>
 * 三条用血换来的机制（与 {@link HermesAcpAnswerer} 内置实现一致，抽出供通用
 * ACP / MCP 适配器复用；Hermes 侧待有实测环境时再迁移到本类）：
 * <ol>
 *   <li><b>不能直接 readLine()</b>——对端不响应时会无限阻塞（实测挂死过 19 分钟），
 *       因此后台读线程 + 队列超时取行，评测工具绝不能挂死；</li>
 *   <li><b>非 JSON 行直接跳过</b>——子进程常在启动时打 banner / 日志到 stdout；</li>
 *   <li><b>通知与响应同流</b>——流式协议里 {@code agent_thought_chunk} 等通知会
 *       夹在请求响应之间到达，必须攒起来交给上层分辨，不能丢也不能当成响应。</li>
 * </ol>
 */
final class JsonRpcPeer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcPeer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedWriter out;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Thread readerThread;
    private final AtomicLong idSeq = new AtomicLong(1);

    JsonRpcPeer(Process process) {
        this.process = process;
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
        }, "litmus-jsonrpc-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /** 已发出的请求数（诊断用） */
    long requestsSent() {
        return idSeq.get() - 1;
    }

    boolean alive() {
        return process.isAlive();
    }

    /**
     * 发一条 JSON-RPC 请求，读到同 id 响应为止；期间到达的通知挂到结果的 {@code notifications} 字段。
     *
     * @return 响应节点；超时 / IO 失败返回 {@code null}（调用方按「判定未发生」处理，绝不挂死）
     */
    JsonNode request(String method, JsonNode params, long timeoutMillis) {
        try {
            long id = idSeq.getAndIncrement();
            ObjectNode req = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", method);
            req.set("params", params);
            out.write(MAPPER.writeValueAsString(req));
            out.newLine();
            out.flush();

            long deadline = System.currentTimeMillis() + timeoutMillis;
            List<JsonNode> notifications = new ArrayList<>();
            while (System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                String line = queue.poll(remaining, TimeUnit.MILLISECONDS);
                if (line == null) {
                    break;    // 超时：不再无限等待
                }
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = MAPPER.readTree(line);
                } catch (Exception e) {
                    continue;    // 启动横幅等非 JSON 行
                }
                if (log.isDebugEnabled()) {
                    log.debug("RPC <- {}", line.length() > 300 ? line.substring(0, 300) : line);
                }
                if (node.path("id").asLong(-1) == id) {
                    if (!notifications.isEmpty()) {
                        ((ObjectNode) node).set("notifications", MAPPER.valueToTree(notifications));
                    }
                    return node;
                }
                notifications.add(node);
            }
            log.warn("RPC 请求 {} 超时（{} ms）", method, timeoutMillis);
            return null;
        } catch (Exception e) {
            log.warn("RPC 请求 {} 失败：{}", method, e.getMessage());
            return null;
        }
    }

    /** 发一条通知（无 id，不等响应），如 MCP 的 {@code notifications/initialized} */
    void notify(String method, JsonNode params) {
        try {
            ObjectNode note = MAPPER.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("method", method);
            if (params != null) {
                note.set("params", params);
            }
            out.write(MAPPER.writeValueAsString(note));
            out.newLine();
            out.flush();
        } catch (Exception e) {
            log.warn("RPC 通知 {} 失败：{}", method, e.getMessage());
        }
    }

    @Override
    public void close() {
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
