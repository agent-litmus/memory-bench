package io.github.zaojiaoci.agentlitmus;

import java.util.Map;

/**
 * 评测证据。
 * <p>
 * 命题要求「将对话、记忆记录、行动轨迹、文件或其他运行产物统一组织为证据」。
 * 这里把它们收敛成同一种结构，评测时无论来源，都能按同一份证据流检查与回放。
 *
 * @param type      证据类型：{@link #DIALOGUE} / {@link #MEMORY} / {@link #ACTION} / {@link #ARTIFACT}
 * @param sessionId 所属会话
 * @param timestamp 产生时间（毫秒）
 * @param content   证据内容
 * @param metadata  附加信息（如工具名、记忆 ID、产物类型）
 */
public record Evidence(String type,
                       String sessionId,
                       long timestamp,
                       String content,
                       Map<String, String> metadata) {

    public static final String DIALOGUE = "dialogue";
    public static final String MEMORY = "memory";
    public static final String ACTION = "action";
    public static final String ARTIFACT = "artifact";

    public static Evidence dialogue(String sessionId, String content) {
        return new Evidence(DIALOGUE, sessionId, System.currentTimeMillis(), content, Map.of());
    }

    public static Evidence memory(String sessionId, String content, String entryId) {
        return new Evidence(MEMORY, sessionId, System.currentTimeMillis(), content,
                entryId == null ? Map.of() : Map.of("entryId", entryId));
    }

    public static Evidence action(String sessionId, String content, String tool) {
        return new Evidence(ACTION, sessionId, System.currentTimeMillis(), content,
                tool == null ? Map.of() : Map.of("tool", tool));
    }

    public static Evidence artifact(String sessionId, String content, String kind) {
        return new Evidence(ARTIFACT, sessionId, System.currentTimeMillis(), content,
                kind == null ? Map.of() : Map.of("kind", kind));
    }
}
