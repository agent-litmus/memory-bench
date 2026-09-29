package io.github.zaojiaoci.agentlitmus.agent;
import io.github.zaojiaoci.agentlitmus.memory.MemoryRegistry;
import io.github.zaojiaoci.agentlitmus.memory.FileLongTermMemory;
import io.github.zaojiaoci.agentlitmus.memory.LongTermMemory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置被测智能体注册表。
 * <p>
 * 提供两个开箱即用的被测对象，用于「至少两款智能体对比」：
 * <ul>
 *   <li>{@code reference}——记忆能力健全（基于相关召回），作为对照组；</li>
 *   <li>{@code degraded}——记忆管理混乱（见 {@link DegradedAnswerer}），作为问题样本。</li>
 * </ul>
 * 两者都不依赖任何模型，结果完全可复现，适合在 openKylin 上稳定演示。
 * 接入真实智能体时，调用 {@link #register} 追加即可。
 */
public final class Agents {

    public static final String REFERENCE = "reference";
    public static final String DEGRADED = "degraded";

    private Agents() {
    }

    /** 按 id 构造内置智能体；未识别的 id 返回 null */
    public static AgentUnderTest builtin(String id, LongTermMemory memory) {
        String key = id == null ? "" : id.trim().toLowerCase();
        AgentUnderTest agent = switch (key) {
            case REFERENCE -> AgentUnderTest.of(
                    REFERENCE, "参考智能体（baseline）",
                    "基于相关性召回记忆，忠实使用召回结果；记忆能力健全，作为对照组。",
                    new RecallAnswerer(memory));
            case DEGRADED -> AgentUnderTest.of(
                    DEGRADED, "缺陷智能体（degraded）",
                    "不按相关性检索，总是取最早写入的记忆；会复用过时信息、混淆相近信息、泄露不应保留的信息。",
                    new DegradedAnswerer(memory));
            default -> null;
        };
        if (agent != null) {
            // 绑定独立记忆，保证批量对比时互不污染
            MemoryRegistry.bind(agent.id(), memory);
        }
        return agent;
    }

    /** 默认参与对比的智能体 id */
    public static List<String> defaultIds() {
        return List.of(REFERENCE, DEGRADED);
    }

    /**
     * 按 id 列表解析被测智能体。未知 id 会被跳过并记录到 warnings。
     *
     * @param ids    智能体 id 列表
     * @param dir    每个智能体独占的记忆目录（避免互相污染）
     * @param warnings 收集未知 id 的说明
     */
    public static List<AgentUnderTest> resolve(List<String> ids, Path dir, List<String> warnings) {
        Map<String, AgentUnderTest> resolved = new LinkedHashMap<>();
        for (String raw : ids) {
            String id = raw == null ? "" : raw.trim();
            if (id.isEmpty()) {
                continue;
            }
            // 每个智能体使用独立的记忆目录，保证评测互不干扰
            LongTermMemory memory = new FileLongTermMemory(dir.resolve("memory-" + sanitize(id)));
            AgentUnderTest agent = builtin(id, memory);
            if (agent == null) {
                if (warnings != null) {
                    warnings.add("未识别的智能体 id，已跳过: " + id);
                }
                continue;
            }
            resolved.put(id, agent);
        }
        return new ArrayList<>(resolved.values());
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
