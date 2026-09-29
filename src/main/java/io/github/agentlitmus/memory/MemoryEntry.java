package io.github.agentlitmus.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Locale;

/**
 * 长期记忆条目。
 * <p>
 * 与短期会话记忆（按轮存储原始消息）不同，这里存的是「被提炼出来的、可长期复用的事实」，
 * 带两个关键属性：
 * <ul>
 *   <li>{@code credibility}——信息该不该被信任（可信度治理）。</li>
 *   <li>{@code retainable}——策略上是否应长期保留：信息该不该被留下。
 *       它直接对应长期记忆评测中的「边界识别」维度：不应保留的信息是否被正确遗忘。</li>
 * </ul>
 * 两者合起来，是同一治理哲学的两个切面：数据层「该不该信」，记忆层「该不该留」。
 *
 * @param id           条目 ID，写入时生成
 * @param sessionId    所属会话
 * @param content      记忆内容（已提炼的事实/偏好/约束）
 * @param category     类别，如 fact / preference / constraint / temporary
 * @param credibility  可信度等级，未标注时按最低等级处理
 * @param retainable   是否应长期保留；false 表示属于「不应保留」的信息
 * @param createdAt    创建时间（毫秒）
 * @param updatedAt    更新时间（毫秒）
 * @param supersededBy 被哪条新记忆取代；非 null 表示该条已过时（动态更新维度）
 */
public record MemoryEntry(String id,
                          String sessionId,
                          String content,
                          String category,
                          String credibility,
                          boolean retainable,
                          long createdAt,
                          long updatedAt,
                          String supersededBy) {

    /** 未标注可信度时的兜底等级——宁可低估，不让未标注内容被当成可靠事实 */
    public static final String DEFAULT_CREDIBILITY = "illustrative";

    public MemoryEntry {
        if (credibility == null || credibility.isBlank()) {
            credibility = DEFAULT_CREDIBILITY;
        }
        credibility = credibility.toLowerCase(Locale.ROOT);
    }

    /** 创建一条尚未分配 ID 的记忆 */
    public static MemoryEntry of(String sessionId, String content, String category,
                                 String credibility, boolean retainable) {
        long now = System.currentTimeMillis();
        return new MemoryEntry(null, sessionId, content, category, credibility,
                retainable, now, now, null);
    }

    /** 便捷构造：默认可保留、类别为 fact */
    public static MemoryEntry fact(String sessionId, String content) {
        return of(sessionId, content, "fact", DEFAULT_CREDIBILITY, true);
    }

    /** 便捷构造：标记「不应长期保留」的记忆，用于边界识别评测 */
    public static MemoryEntry temporary(String sessionId, String content) {
        return of(sessionId, content, "temporary", DEFAULT_CREDIBILITY, false);
    }

    /**
     * 该条是否仍然有效（未被新记忆取代）。
     * <p>
     * 标注 {@code @JsonIgnore}：Jackson 会把 {@code isActive()} 当作 boolean 属性序列化，
     * 但反序列化时 record 构造器没有该参数，会导致整条记忆解析失败。
     */
    @JsonIgnore
    public boolean isActive() {
        return supersededBy == null;
    }

    public MemoryEntry withId(String id) {
        return new MemoryEntry(id, sessionId, content, category, credibility,
                retainable, createdAt, updatedAt, supersededBy);
    }

    /** 返回一条被 {@code newId} 取代的副本（保留原内容用于审计） */
    public MemoryEntry supersededBy(String newId) {
        return new MemoryEntry(id, sessionId, content, category, credibility,
                retainable, createdAt, System.currentTimeMillis(), newId);
    }
}
