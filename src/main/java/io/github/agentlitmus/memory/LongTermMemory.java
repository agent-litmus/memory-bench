package io.github.agentlitmus.memory;

import java.util.List;

/**
 * 长期记忆存储抽象。
 * <p>
 * 与短期会话记忆（滑动窗口 + TTL）职责不同：这里存的是跨会话长期保留、
 * 可被后续任务复用的事实，是长期记忆评测的载体。
 * <p>
 * 抽象为接口，是为了让「存储方式」可替换（文件 / SQLite / 向量库），
 * 评测逻辑不依赖具体实现——这也保证了「可选能力有降级路径」。
 */
public interface LongTermMemory {

    /** 写入一条记忆，返回生成的 ID */
    String write(String sessionId, MemoryEntry entry);

    /**
     * 检索与 query 相关的记忆。
     * <p>
     * 只返回「有效且策略上应保留」的条目：被取代的（{@code supersededBy != null}）和
     * 标记为不应保留的（{@code retainable == false}）都不会出现在这里。
     * 这正是「边界识别」维度的判定依据——不应保留的信息是否被正确排除。
     *
     * @param topK 最多返回条数
     */
    List<MemoryEntry> recall(String sessionId, String query, int topK);

    /**
     * 用新记忆更新（取代）旧记忆。
     * <p>
     * 旧条目不会被物理删除，而是标记 {@code supersededBy}，以便评测时检查
     * 「模型是否还在错误地复用旧信息」——对应「动态更新」维度。
     *
     * @return 新记忆的 ID
     */
    String update(String sessionId, String targetId, MemoryEntry replacement);

    /** 主动遗忘一条记忆（物理移除） */
    boolean forget(String sessionId, String id);

    /** 该会话的全部条目（含已过时、含不应保留的），供评测与审计使用 */
    List<MemoryEntry> all(String sessionId);

    /**
     * 该会话中仍然有效的条目（未被取代），含 {@code retainable == false} 的条目。
     * 用于边界识别评测：检查系统是否错误地留下了不该留的信息。
     */
    List<MemoryEntry> active(String sessionId);

    /** 清空该会话的长期记忆 */
    void clear(String sessionId);
}
