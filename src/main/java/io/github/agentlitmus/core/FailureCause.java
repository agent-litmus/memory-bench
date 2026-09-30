package io.github.agentlitmus.core;

/**
 * 记忆失败归因——在五类结果（正确记忆 / 遗漏 / 混淆 / 错误持久化 / 错误复用）之上，
 * 再回答一层：<b>这个记忆缺陷发生在记忆生命周期的哪一阶段，由哪类可观测信号暴露。</b>
 * <p>
 * 设计取舍：memory-bench 是<b>黑盒</b>评测，只看到被测智能体的「回答」，看不到其内部
 * 是否写入、是否召回。因此这里的归因不指向被测对象内部实现（如 Prompt/RAG/Tool 三层），
 * 而是指向「可观测证据 + 记忆功能阶段」，并且特意把<b>评测导管故障（无响应）</b>与
 * 被测对象的真实记忆缺陷区分开——否则像限流 429 这类服务问题会污染分数。
 * <p>
 * 举例：同样被判「遗漏」，若回答为空（请求失败 / 限流 / 超时）则应归因为
 * {@link #NO_RESPONSE}，说明问题在评测链路或被测服务，而非智能体没记住；
 * 若有回答但缺内容，才归因为 {@link #RECALL_MISSING}（保持 / 召回缺失）。
 */
public enum FailureCause {

    /**
     * 无有效响应：请求失败 / 限流(429) / 超时 / 空回答。
     * 属评测导管或被测服务故障，<b>非记忆能力缺陷</b>——应单列，避免污染分数。
     */
    NO_RESPONSE("无响应", "被测对象未产出有效回答（请求失败/限流/超时/空回答）——评测导管或服务故障，非记忆能力缺陷"),

    /** 保持/召回缺失：有回答，但应记住/召回的内容没出现 */
    RECALL_MISSING("保持/召回缺失", "有回答但期望的记忆内容未出现：信息未被长期保持，或未被正确召回"),

    /** 相近混淆：把相似的两条信息搞混 */
    SIMILAR_CONFUSED("相近混淆", "相近信息未被区分，回答混入了干扰项"),

    /** 边界泄漏：不应长期保留的信息（敏感/临时/风险指令）被保留并复用 */
    BOUNDARY_LEAKED("边界泄漏", "不应长期保留的信息（敏感/临时/风险指令）被记住并复用"),

    /** 旧值未更新：信息更新后仍在复用过时旧值 */
    STALE_REUSE("旧值未更新", "信息已被新值取代，但仍在使用过时的旧值");

    private final String label;
    private final String description;

    FailureCause(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
