package io.github.zaojiaoci.agentlitmus.agent;
import io.github.zaojiaoci.agentlitmus.memory.MemoryEntry;
import io.github.zaojiaoci.agentlitmus.memory.LongTermMemory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于长期记忆召回结果的确定性回答器。
 * <p>
 * 它模拟一个「忠实使用记忆」的被测对象：召回得到什么就说什么，召回不到就明确表示不知道。
 * <b>不调用任何模型</b>，因此：
 * <ul>
 *   <li>无需 API Key，CI 与 openKylin 上都能跑完整套评测；</li>
 *   <li>结果完全可复现——同一套用例与记忆，答案永远一致；</li>
 *   <li>用它跑出的指标衡量的是<b>记忆系统本身</b>的能力（能不能召回正确的记忆）。</li>
 * </ul>
 * 它同时充当<b>内置参考智能体（baseline）</b>：与真实智能体对比时，它是「记忆能力健全」的对照组。
 */
public class RecallAnswerer implements Answerer {

    private static final String UNKNOWN = "我不掌握相关信息。";

    private final LongTermMemory memory;
    private final int topK;

    public RecallAnswerer(LongTermMemory memory) {
        this(memory, 5);
    }

    public RecallAnswerer(LongTermMemory memory, int topK) {
        this.memory = memory;
        this.topK = Math.max(1, topK);
    }

    @Override
    public String answer(String sessionId, String question) {
        List<MemoryEntry> hits = memory.recall(sessionId, question, topK);
        if (hits.isEmpty()) {
            return UNKNOWN;
        }
        return hits.stream()
                .map(MemoryEntry::content)
                .collect(Collectors.joining("；"));
    }
}
