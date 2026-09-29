package io.github.zaojiaoci.agentlitmus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 被测智能体与其记忆存储的绑定关系。
 * <p>
 * 批量评测时，每个智能体必须有<b>独立</b>的记忆目录：
 * 若共用一份记忆，前一个智能体写入的内容会被后一个读到，对比结果就不可信了。
 * 这里用一个轻量注册表把「智能体 → 记忆实例」绑定起来，由 {@link Agents} 在构造时登记。
 */
public final class MemoryRegistry {

    private static final Map<String, LongTermMemory> MEMORIES = new ConcurrentHashMap<>();

    private MemoryRegistry() {
    }

    /** 登记智能体的记忆实现（通常在构造被测智能体时调用） */
    public static void bind(String agentId, LongTermMemory memory) {
        if (agentId != null && memory != null) {
            MEMORIES.put(agentId, memory);
        }
    }

    /** 取回智能体绑定的记忆实现；未登记时回退到临时目录，保证流程不中断 */
    public static LongTermMemory resolve(AgentUnderTest agent) {
        LongTermMemory memory = MEMORIES.get(agent.id());
        if (memory != null) {
            return memory;
        }
        FileLongTermMemory fallback = new FileLongTermMemory();
        bind(agent.id(), fallback);
        return fallback;
    }

    /** 清空绑定（测试用） */
    public static void clear() {
        MEMORIES.clear();
    }
}
