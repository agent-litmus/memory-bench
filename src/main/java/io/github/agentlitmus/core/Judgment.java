package io.github.agentlitmus.core;

/**
 * 单条用例的判定结果。
 *
 * @param caseId    用例 ID
 * @param dimension 所属维度
 * @param verdict   三态判定：通过 / 未过 / 不可判（见 {@link Verdict}）
 * @param score     得分（通过为 1，其余为 0；{@code UNKNOWN} 不参与统计，不要读此值）
 * @param reason    判定理由（可解释：哪一项没做到、为什么不合格、为什么不可判）
 * @param mode      判定方式：rule / llm / rule-degraded / no-response
 * @param outcome   五类结果类别；{@code UNKNOWN} 时为 null（不可判不属于任何一种记忆缺陷）
 * @param cause     记忆失败归因：在结果类别之上，指向记忆生命周期阶段 / 可观测信号
 */
public record Judgment(String caseId,
                       Dimension dimension,
                       Verdict verdict,
                       double score,
                       String reason,
                       String mode,
                       Outcome outcome,
                       FailureCause cause) {

    public static final String MODE_RULE = "rule";
    public static final String MODE_LLM = "llm";
    public static final String MODE_DEGRADED = "rule-degraded";
    public static final String MODE_NO_RESPONSE = "no-response";

    public Judgment {
        if (verdict == null) {
            throw new IllegalArgumentException("判定结果必须给出三态 verdict: " + caseId);
        }
        if (!verdict.judged()) {
            // 不可判不归属任何记忆能力类别
            outcome = null;
        } else if (outcome == null) {
            outcome = verdict == Verdict.PASS ? Outcome.CORRECT : Outcome.OMISSION;
        }
    }

    public boolean passed() {
        return verdict == Verdict.PASS;
    }

    /** 是否构成有效判定（进入评分分母） */
    public boolean judged() {
        return verdict.judged();
    }

    public static Judgment rule(String caseId, Dimension dimension, boolean passed,
                                String reason, Outcome outcome, FailureCause cause) {
        return rule(caseId, dimension, passed ? Verdict.PASS : Verdict.FAIL, reason, outcome, cause);
    }

    public static Judgment rule(String caseId, Dimension dimension, Verdict verdict,
                                String reason, Outcome outcome, FailureCause cause) {
        return new Judgment(caseId, dimension, verdict, verdict == Verdict.PASS ? 1.0 : 0.0,
                reason, MODE_RULE, outcome, cause);
    }

    /**
     * 无有效响应：请求失败 / 限流 / 超时 / 空回答。
     * 属评测导管或被测服务故障，<b>单列为不可判</b>——既不算记忆缺陷，也不静默丢弃。
     */
    public static Judgment noResponse(String caseId, Dimension dimension, String reason) {
        return new Judgment(caseId, dimension, Verdict.UNKNOWN, 0.0, reason, MODE_NO_RESPONSE,
                null, FailureCause.NO_RESPONSE);
    }

    /** 需语义判定但评委不可用：判定未发生，不可当作失败 */
    public static Judgment degraded(String caseId, Dimension dimension, String reason, FailureCause cause) {
        return new Judgment(caseId, dimension, Verdict.UNKNOWN, 0.0, reason, MODE_DEGRADED, null, cause);
    }

    /**
     * 评委自己也判「不可判」：例如被测端只回了排队提示或空转输出。
     * <p>
     * 与 {@link #degraded} 分开记，因为两者的含义相反——degraded 是<b>评委没到位</b>（评测侧的缺失），
     * 这里是<b>评委到位了并认定这条不该判</b>（被测侧没给出可判的回答）。混在一起会让
     * "缺 API Key 导致的不可判"和"被测对象没回答导致的不可判"看起来是同一件事。
     */
    public static Judgment unknownByLlm(String caseId, Dimension dimension, String reason, FailureCause cause) {
        return new Judgment(caseId, dimension, Verdict.UNKNOWN, 0.0, reason, MODE_LLM, null, cause);
    }

    public static Judgment llm(String caseId, Dimension dimension, double score,
                               String reason, Outcome outcome, FailureCause cause) {
        return new Judgment(caseId, dimension, score > 0 ? Verdict.PASS : Verdict.FAIL,
                score, reason, MODE_LLM, outcome, cause);
    }
}
