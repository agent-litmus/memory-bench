package io.github.zaojiaoci.agentlitmus.core;

import java.util.List;

/**
 * 长期记忆评测用例。
 * <p>
 * 统一模式：<b>注入事实 →（可选更新）→ 提问 → 判定回答</b>。
 * 判定优先用确定性规则（关键词包含 / 排除），只有无法用关键词表达的标准才交给 LLM。
 * 这样绝大多数用例在无 API Key 的环境下也能跑，符合「可复现」的要求。
 *
 * @param id                  用例 ID
 * @param dimension           所属维度
 * @param sessionId           会话 ID（每个用例独立，避免互相污染）
 * @param seeds               注入的记忆事实
 * @param seedsRetainable     注入的事实是否应长期保留（边界识别维度用 false）
 * @param updateTo            非空时表示用该内容更新第一条 seed（动态更新维度）
 * @param question            后续提问
 * @param expectedContains    期望回答包含的内容（null 表示不检查）
 * @param expectedNotContains 期望回答不包含的内容（null 表示不检查）
 * @param requiresLlmJudge    是否需要 LLM 语义判定
 * @param llmRubric           LLM 判定标准（requiresLlmJudge 为 true 时必填）
 */
public record MemoryCase(String id,
                         Dimension dimension,
                         String sessionId,
                         List<String> seeds,
                         boolean seedsRetainable,
                         String updateTo,
                         String question,
                         String expectedContains,
                         String expectedNotContains,
                         boolean requiresLlmJudge,
                         String llmRubric) {

    public MemoryCase {
        if (seeds == null || seeds.isEmpty()) {
            throw new IllegalArgumentException("用例必须至少注入一条记忆: " + id);
        }
        seeds = List.copyOf(seeds);
        if (requiresLlmJudge && (llmRubric == null || llmRubric.isBlank())) {
            throw new IllegalArgumentException("启用 LLM 判定的用例必须提供 rubric: " + id);
        }
    }

    /** 注入事实后提问，期望回答包含某内容——用于长期保持 / 记忆调用 / 任务复用 */
    public static MemoryCase expect(String id, Dimension dimension, List<String> seeds,
                                    String question, String expectedContains) {
        return new MemoryCase(id, dimension, sessionOf(id), seeds, true, null,
                question, expectedContains, null, false, null);
    }

    /** 期望回答<b>不包含</b>某内容——用于边界识别（不应保留的信息不能被复用） */
    public static MemoryCase expectAbsent(String id, Dimension dimension, List<String> seeds,
                                          boolean retainable, String question, String expectedNotContains) {
        return new MemoryCase(id, dimension, sessionOf(id), seeds, retainable, null,
                question, null, expectedNotContains, false, null);
    }

    /** 动态更新：先注入旧事实，再更新为新事实，期望含新且不含旧 */
    public static MemoryCase expectUpdate(String id, String oldFact, String newFact,
                                          String question, String expectedNew, String unexpectedOld) {
        return new MemoryCase(id, Dimension.UPDATE, sessionOf(id), List.of(oldFact), true, newFact,
                question, expectedNew, unexpectedOld, false, null);
    }

    /** 相近区分：注入两条相近事实，期望答中目标且不混淆另一条 */
    public static MemoryCase expectDiscriminate(String id, String factA, String factB,
                                                String question, String expected, String unexpected) {
        return new MemoryCase(id, Dimension.DISCRIMINATION, sessionOf(id), List.of(factA, factB), true, null,
                question, expected, unexpected, false, null);
    }

    /** 需要 LLM 语义判定的用例（关键词无法表达的标准，如「语气是否得体」） */
    public static MemoryCase llmJudged(String id, Dimension dimension, List<String> seeds,
                                       String question, String rubric) {
        return new MemoryCase(id, dimension, sessionOf(id), seeds, true, null,
                question, null, null, true, rubric);
    }

    private static String sessionOf(String id) {
        return "case-" + id;
    }
}
