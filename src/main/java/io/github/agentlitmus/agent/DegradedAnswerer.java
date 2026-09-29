package io.github.agentlitmus.agent;
import io.github.agentlitmus.memory.MemoryEntry;
import io.github.agentlitmus.memory.LongTermMemory;

import java.util.List;

/**
 * 一个<b>故意带有记忆缺陷</b>的被测智能体，用于演示评测能否区分能力差异。
 * <p>
 * 它的缺陷是：不按相关性检索，而是直接取该会话<b>最早写入</b>的一条记忆来回答，
 * 且不区分该条是否已过时、是否属于不应保留的信息。
 * 这模拟了真实智能体中常见的一类问题——记忆管理混乱：
 * <ul>
 *   <li>复用过时信息（动态更新失败）</li>
 *   <li>混淆相近信息（相近区分失败）</li>
 *   <li>泄露不该保留的信息（边界识别失败）</li>
 * </ul>
 * 它在报告里充当"反面样本"：如果评测无法把它与 {@link RecallAnswerer} 区分开，
 * 就说明这把尺子没有分辨力。
 */
public class DegradedAnswerer implements Answerer {

    private static final String UNKNOWN = "我不掌握相关信息。";

    private final LongTermMemory memory;

    public DegradedAnswerer(LongTermMemory memory) {
        this.memory = memory;
    }

    @Override
    public String answer(String sessionId, String question) {
        List<MemoryEntry> all = memory.all(sessionId);
        if (all.isEmpty()) {
            return UNKNOWN;
        }
        // 缺陷所在：永远取最早写入的那条，不看相关性、不看是否已过时、不看是否应保留
        return all.get(0).content();
    }
}
