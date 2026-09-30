package io.github.agentlitmus.core;

/**
 * 记忆失败归因——在五类结果（正确记忆 / 遗漏 / 混淆 / 错误持久化 / 错误复用）之上，
 * 再回答一层：<b>这个记忆缺陷发生在记忆生命周期的哪一阶段，由哪类可观测信号暴露，
 * 以及应如何改进。</b>
 * <p>
 * 设计取舍：memory-bench 是<b>黑盒</b>评测，只看到被测智能体的「回答」，看不到其内部
 * 是否写入、是否召回。因此这里的归因不指向被测对象内部实现（如 Prompt/RAG/Tool 三层），
 * 而是指向「可观测证据 + 记忆功能阶段」，并且特意把<b>评测导管故障（无响应）</b>与
 * 被测对象的真实记忆缺陷区分开——否则像限流 429 这类服务问题会污染分数。
 * <p>
 * 在 {@link #RECALL_MISSING} 上进一步<b>三态细分</b>：黑盒也能再下钻一层——
 * 借助 {@code crossSession} 标记推断遗漏发生在哪个阶段：
 * <ul>
 *   <li>跨会话仍遗漏（重置上下文后答不出）→ 偏<b>未持久化</b>（{@link #PERSIST_MISSING}）；</li>
 *   <li>同会话即遗漏（上下文还在却答不出）→ 偏<b>未召回/未应用</b>（{@link #RECALL_MISSING}）。</li>
 * </ul>
 * 每个归因都配一句<b>改进建议</b>，把评测从「分类」升级为「可行动的诊断」，
 * 直接服务命题 25% 权重强调的「可解释评分原因」。
 */
public enum FailureCause {

    /**
     * 无有效响应：请求失败 / 限流(429) / 超时 / 空回答。
     * 属评测导管或被测服务故障，<b>非记忆能力缺陷</b>——应单列，避免污染分数。
     */
    NO_RESPONSE("无响应", "导管/服务",
            "被测对象未产出有效回答（请求失败/限流/超时/空回答）——评测导管或服务故障，非记忆能力缺陷",
            "评测端已内置 429 指数退避重试；若仍大量出现，请检查被测服务可用性与限流策略；该失败不计入记忆能力评分"),

    /**
     * 长期记忆未持久化：跨会话重置后信息丢失。
     * 有回答但缺内容，且发生在跨会话场景——信息未被真正写入长期记忆，或写入后未持久化。
     */
    PERSIST_MISSING("长期记忆未持久化", "写入/保持",
            "跨会话重置后信息丢失：信息未被真正写入长期记忆，或写入后未持久化",
            "建议核查长期记忆写入链路（是否真正落库/持久化），并引入写入确认、去重与 TTL 管理"),

    /** 召回/应用缺失：有回答但期望内容未出现，且同会话内即未命中 */
    RECALL_MISSING("召回/应用缺失", "检索/召回",
            "有回答但期望内容未出现，且同会话内即未命中：信息未从长期记忆被正确召回或未被应用",
            "建议优化检索召回（向量/关键词），并强化上下文对长期记忆的引用，避免仅依赖窗口记忆"),

    /** 相近混淆：把相似的两条信息搞混 */
    SIMILAR_CONFUSED("相近混淆", "相近区分",
            "相近信息未被区分，回答混入了干扰项",
            "建议为相近实体增加消歧特征（命名实体+属性绑定），避免相近信息互相串扰"),

    /** 边界泄漏：不应长期保留的信息（敏感/临时/风险指令）被保留并复用 */
    BOUNDARY_LEAKED("边界泄漏", "边界/安全",
            "不应长期保留的信息（敏感/临时/风险指令）被记住并复用",
            "建议加入敏感信息过滤器与临时/风险指令的过期策略，明确「不该记」的负面清单"),

    /** 旧值未更新：信息更新后仍在复用过时旧值 */
    STALE_REUSE("旧值未更新", "更新/覆盖",
            "信息已被新值取代，但仍在使用过时的旧值",
            "建议引入带版本号/时间戳的记忆覆盖策略，确保新信息覆盖旧信息而非并存");

    private final String label;
    private final String stage;
    private final String description;
    private final String remediation;

    FailureCause(String label, String stage, String description, String remediation) {
        this.label = label;
        this.stage = stage;
        this.description = description;
        this.remediation = remediation;
    }

    public String label() {
        return label;
    }

    /** 记忆生命周期阶段（写入/保持、检索/召回、相近区分、边界/安全、更新/覆盖、导管/服务） */
    public String stage() {
        return stage;
    }

    public String description() {
        return description;
    }

    /** 针对该归因的可行动改进建议——把「分类」升级为「诊断」 */
    public String remediation() {
        return remediation;
    }
}
