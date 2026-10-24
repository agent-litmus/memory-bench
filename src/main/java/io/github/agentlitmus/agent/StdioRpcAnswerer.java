package io.github.agentlitmus.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「每会话一个常驻子进程 + stdio JSON-RPC」形态被测对象的公共骨架。
 * <p>
 * ACP 与 MCP 的智能体进程都是这种形态：进程常驻、按会话复用、退出时统一回收。
 * 本类管住三件事，协议差异留给子类：
 * <ul>
 *   <li><b>会话进程生命周期</b>——首次提问拉起，重置/关闭按需销毁；
 *   <li><b>存活上限与淘汰</b>——用例跑完不再复用其 sessionId，按插入顺序回收最旧进程，
 *       避免几百条用例累积几百个进程把机器拖垮。上限必须 ≥ 并行度，否则会误杀在跑的会话；</li>
 *   <li><b>启动失败与调用异常降级</b>——一律返回空回答（按「无响应」归因），
 *       评测工具不因单个被测对象进程问题挂死或崩溃。</li>
 * </ul>
 */
abstract class StdioRpcAnswerer implements Answerer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StdioRpcAnswerer.class);

    /** 同时存活进程上限；必须 ≥ 评测并行度，可用系统属性调整 */
    private static final int MAX_LIVE_SESSIONS =
            Math.max(1, Integer.getInteger("litmus.stdio.maxSessions", 16));

    protected final List<String> command;
    protected final Duration timeout;
    private final Path stateRoot;

    private final Map<String, Session> sessions = new LinkedHashMap<>();

    StdioRpcAnswerer(List<String> command, Duration timeout, Path stateRoot) {
        if (command == null || command.isEmpty() || command.get(0).isBlank()) {
            throw new IllegalArgumentException("stdio 型适配器需要 command（可执行文件），配置里没有");
        }
        this.command = List.copyOf(command);
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.stateRoot = stateRoot;
    }

    /** 本会话的进程与协议状态（remoteSessionId / initialized 旗标由子类解释） */
    protected static final class Session {
        final JsonRpcPeer peer;
        final Path homeDir;
        String remoteSessionId;
        boolean initialized;

        Session(JsonRpcPeer peer, Path homeDir) {
            this.peer = peer;
            this.homeDir = homeDir;
        }
    }

    @Override
    public String answer(String sessionId, String question) {
        Session session;
        synchronized (sessions) {
            session = sessions.get(sessionId);
        }
        if (session == null) {
            // start() 含进程启动等耗时 I/O，刻意不持锁，避免阻塞其它并发用例
            Session created = start(sessionId);
            if (created == null) {
                return "";
            }
            synchronized (sessions) {
                Session existing = sessions.get(sessionId);
                if (existing != null) {
                    created.peer.close();
                    session = existing;
                } else {
                    sessions.put(sessionId, created);
                    session = created;
                }
            }
            evictIdle();
        }
        try {
            return converse(session, sessionId, question);
        } catch (Exception e) {
            log.warn("stdio 调用异常（{}）：{}", sessionId, e.getMessage());
            return "";
        }
    }

    @Override
    public void close() {
        synchronized (sessions) {
            for (Session session : sessions.values()) {
                session.peer.close();
            }
            sessions.clear();
        }
    }

    /** 拉起本会话的专属进程。默认实现：可选的隔离家目录 + 环境变量注入，由 {@link #configureEnvironment} 定制 */
    private Session start(String sessionId) {
        try {
            Path home = stateRoot == null ? null
                    : stateRoot.resolve(sanitize(sessionId));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            if (home != null) {
                Files.createDirectories(home);
            }
            configureEnvironment(pb, sessionId, home);
            Process process = pb.start();
            return new Session(new JsonRpcPeer(process), home);
        } catch (Exception e) {
            log.warn("启动被测进程失败（{}：{}）：{}", sessionId, String.join(" ", command), e.getMessage());
            return null;
        }
    }

    /**
     * 子类钩子：往进程环境里注入隔离变量（如 {@code HERMES_HOME}）、
     * 往隔离家目录里铺模板文件（凭据、配置）。
     */
    protected void configureEnvironment(ProcessBuilder pb, String sessionId, Path home) throws IOException {
        // 默认不注入
    }

    /** 一次问答的协议交互；实现方保证不抛异常之外的挂死（超时由 JsonRpcPeer 兜底） */
    protected abstract String converse(Session session, String sessionId, String question);

    /** 移除并销毁一个会话（重置语义由子类决定是「销毁」还是「留进程丢句柄」） */
    protected void destroySession(String sessionId) {
        Session victim;
        synchronized (sessions) {
            victim = sessions.remove(sessionId);
        }
        if (victim != null) {
            victim.peer.close();
        }
    }

    /** 查会话不销毁（重置类语义用：保留进程与家目录，只动协议层句柄） */
    protected Session lookupSession(String sessionId) {
        synchronized (sessions) {
            return sessions.get(sessionId);
        }
    }

    private void evictIdle() {
        synchronized (sessions) {
            while (sessions.size() > MAX_LIVE_SESSIONS) {
                String oldest = sessions.keySet().iterator().next();
                Session victim = sessions.remove(oldest);
                if (victim != null) {
                    victim.peer.close();
                    log.debug("已回收被测进程（session={}）", oldest);
                }
            }
        }
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
