package io.github.agentlitmus.probe;

import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.agent.ConfidenceTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接入探针测试：探针的价值在于<b>如实</b>——能力成立才授予，测不出来就写测不出来。
 * 因此每个断言都盯住「结论强度是否恰好等于证据强度」，多给一格都算失败。
 */
class AgentProberTest {

    @TempDir
    Path tempDir;

    /**
     * 可编程被测对象：长期记忆（可落盘）、上下文窗口（可复位）、可选的文件产出。
     * 它<b>不声明任何能力</b>——所有能力都必须由探针自己观测出来，
     * 否则测试只是在验证「声明被原样抄过去」。
     */
    private static final class ScriptedAgent implements Answerer {
        final Map<String, StringBuilder> longTerm = new ConcurrentHashMap<>();
        final Map<String, StringBuilder> windows = new ConcurrentHashMap<>();
        Path stateDir;
        boolean resettable;
        boolean writesArtifact;

        @Override
        public String answer(String sessionId, String question) {
            if (question.contains("标记词是什么") || question.contains("编号是什么")) {
                if (question.contains("编号")) {
                    return windows.getOrDefault(sessionId, new StringBuilder()).toString();
                }
                return longTerm.getOrDefault(sessionId, new StringBuilder()).toString();
            }
            if (question.startsWith("请把标记词 ")) {
                if (stateDir != null && writesArtifact) {
                    try {
                        Files.writeString(stateDir.resolve("litmus-probe.txt"),
                                grab(question, "请把标记词 ", " 写进"));
                    } catch (IOException e) {
                        return "写入失败";
                    }
                }
                return "已写入文件";
            }
            if (question.startsWith("请记住这个标记词：")) {
                String marker = grab(question, "请记住这个标记词：", "，");
                longTerm.computeIfAbsent(sessionId, k -> new StringBuilder()).append(marker).append(' ');
                if (stateDir != null) {
                    try {
                        Files.writeString(stateDir.resolve("memory.jsonl"),
                                "{\"content\":\"" + marker + "\"}", StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        return "没记住";
                    }
                }
                return "记住了";
            }
            if (question.startsWith("我说一句闲话：")) {
                windows.computeIfAbsent(sessionId, k -> new StringBuilder())
                        .append(grab(question, "我说一句闲话：", "，")).append(' ');
                return "收到";
            }
            return "好的";
        }

        @Override
        public void resetSession(String sessionId) {
            if (resettable) {
                windows.remove(sessionId);
            }
        }

        @Override
        public Set<AgentCapability> capabilities() {
            return Set.of();
        }

        private static String grab(String text, String prefix, String stop) {
            int start = text.indexOf(prefix);
            if (start < 0) {
                return "";
            }
            String rest = text.substring(start + prefix.length());
            int end = rest.indexOf(stop);
            return end < 0 ? rest.trim() : rest.substring(0, end).trim();
        }
    }

    private ScriptedAgent agent(Path stateDir, boolean resettable, boolean writesArtifact) {
        ScriptedAgent agent = new ScriptedAgent();
        agent.stateDir = stateDir;
        agent.resettable = resettable;
        agent.writesArtifact = writesArtifact;
        return agent;
    }

    @Test
    void observableCapabilitiesAreGrantedAndTierFollows() throws IOException {
        Path state = Files.createDirectories(tempDir.resolve("state"));
        ProbeResult result = new AgentProber(agent(state, true, true), Set.of(), List.of(state))
                .probe("full", "全能力被测对象");

        assertEquals(ProbeStep.Result.YES, result.resultOf(AgentCapability.RESETS_CONTEXT),
                "重置前答得出、重置后答不出 ⇒ 上下文确实被清掉");
        assertEquals(ProbeStep.Result.YES, result.resultOf(AgentCapability.MEMORY_INSPECTABLE),
                "标记词能在状态目录里扫到");
        assertEquals(ProbeStep.Result.YES, result.resultOf(AgentCapability.ARTIFACTS_VISIBLE));
        assertEquals(ProbeStep.Result.NOT_TESTABLE, result.resultOf(AgentCapability.TOOL_CALLS_VISIBLE),
                "黑盒看不到工具调用，绝不能假装探测到了");
        assertEquals(ConfidenceTier.T3, result.tier(), "产物可观测 ⇒ T3");
        assertTrue(result.crossSessionUsable());
    }

    @Test
    void unresettableContextIsReportedAsNoNotMissingEvidence() throws IOException {
        Path state = Files.createDirectories(tempDir.resolve("state2"));

        ProbeResult result = new AgentProber(agent(state, false, false), Set.of(), List.of(state))
                .probe("noreset", "复位不了的平台");

        assertEquals(ProbeStep.Result.NO, result.resultOf(AgentCapability.RESETS_CONTEXT),
                "重置后仍答得出闲话 ⇒ 观测到「复位不成立」，这是真实结论");
        assertEquals(ProbeStep.Result.NO, result.resultOf(AgentCapability.ARTIFACTS_VISIBLE),
                "要求写文件却没写出来，且目录可扫描 ⇒ 判 NO 而非不可探测");
        assertFalse(result.crossSessionUsable());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("跨会话")),
                "必须提示跨会话题将被移出分母: " + result.warnings());
    }

    @Test
    void missingStateDirDowngradesToNotTestable() {
        ProbeResult result = new AgentProber(agent(null, true, false), Set.of(), List.of())
                .probe("blackbox", "只有对话接口的平台");

        assertEquals(ProbeStep.Result.NOT_TESTABLE, result.resultOf(AgentCapability.MEMORY_INSPECTABLE),
                "没有可扫描目录时不能判 NO：那只说明我们看不见，不说明它没写");
        assertEquals(ProbeStep.Result.NOT_TESTABLE, result.resultOf(AgentCapability.STATE_RESETTABLE));
        assertEquals(ProbeStep.Result.NOT_TESTABLE, result.resultOf(AgentCapability.ARTIFACTS_VISIBLE));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("stateDir")),
                "要告诉接入方补什么才能升档: " + result.warnings());
    }

    @Test
    void declaredButUnprovenCapabilityIsKeptWithAWarning() throws IOException {
        Path state = Files.createDirectories(tempDir.resolve("state3"));

        ProbeResult result = new AgentProber(agent(state, true, false),
                Set.of(AgentCapability.TOOL_CALLS_VISIBLE), List.of(state)).probe("declared", "声明了行动可见");

        assertTrue(result.granted().contains(AgentCapability.TOOL_CALLS_VISIBLE),
                "接入方声明的能力应生效（并集，而不是被观测结果抹掉）");
        assertEquals(ProbeStep.Result.NOT_TESTABLE, result.resultOf(AgentCapability.TOOL_CALLS_VISIBLE));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("无法验证")),
                "但必须公示这一格来自声明、没有证据: " + result.warnings());
    }

    @Test
    void draftConfigCarriesDetectedCapabilitiesAndStateDir() throws IOException {
        Path state = Files.createDirectories(tempDir.resolve("state4"));

        ProbeResult result = new AgentProber(agent(state, true, true), Set.of(), List.of(state))
                .probe("draft", "草案对象");
        String draft = result.configDraftJson("http", "http://127.0.0.1:8089",
                null, List.of(), List.of());

        assertTrue(draft.contains("MEMORY_INSPECTABLE"), draft);
        assertTrue(draft.contains("RESETS_CONTEXT"), draft);
        assertTrue(draft.contains(state.toString().replace("\\", "\\\\")), "stateDir 要回填进草案");
        assertFalse(draft.contains("TOOL_CALLS_VISIBLE"), "没观测到也不该写进草案");
    }

    @Test
    void commandDraftOmitsTheEphemeralSandboxPath() throws IOException {
        // 命令行对象的 stateDir 是这一轮临时沙箱的路径，下一轮另开一份；写进草案等于给它一个空目录当证据源
        Path sandboxHome = Files.createDirectories(tempDir.resolve("litmus-sandbox-123/home"));
        ProbeResult result = new AgentProber(agent(sandboxHome, true, true), Set.of(),
                List.of(sandboxHome)).probe("draft2", "草案对象");

        String draft = result.configDraftJson("command", null, "kylin-agent",
                List.of("{question}"), List.of("reset"));
        assertFalse(draft.contains("litmus-sandbox-123"), "临时沙箱路径不应被固化进草案: " + draft);
        assertTrue(draft.contains("note"), "要留下一句为什么没写");
    }
}
