package io.github.agentlitmus.core;

/**
 * 单条用例的判定结果。
 *
 * @param caseId    用例 ID
 * @param dimension 所属维度
 * @param passed    是否通过
 * @param score     得分（0 或 1；LLM 判定时可为 0~1）
 * @param reason    判定理由（可解释：哪一项没做到、为什么不合格）
 * @param mode      判定方式：rule / llm / rule-degraded
 * @param outcome   结果类别：正确记忆 / 遗漏 / 混淆 / 错误持久化 / 错误复用
 */
public record Judgment(String caseId,
                       Dimension dimension,
                       boolean passed,
                       double score,
                       String reason,
                       String mode,
                       Outcome outcome) {

    public static final String MODE_RULE = "rule";
    public static final String MODE_LLM = "llm";
    public static final String MODE_DEGRADED = "rule-degraded";

    public Judgment {
        if (outcome == null) {
            outcome = passed ? Outcome.CORRECT : Outcome.OMISSION;
        }
    }

    public static Judgment rule(String caseId, Dimension dimension, boolean passed,
                                String reason, Outcome outcome) {
        return new Judgment(caseId, dimension, passed, passed ? 1.0 : 0.0, reason, MODE_RULE, outcome);
    }

    public static Judgment llm(String caseId, Dimension dimension, double score,
                               String reason, Outcome outcome) {
        return new Judgment(caseId, dimension, score > 0, score, reason, MODE_LLM, outcome);
    }
}
