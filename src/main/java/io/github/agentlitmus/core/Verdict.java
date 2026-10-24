package io.github.agentlitmus.core;

/**
 * 判定三态：{@code 通过 / 未过 / 不可判}。
 * <p>
 * 只有两态时，「超时、限流、空回答、缺评委模型」这些<b>评测导管故障</b>会被塞进「未过」，
 * 于是通过率同时混合了「被测对象记不住」和「这次没测成」两种完全不同的事实。
 * 三态的作用是把后者从分母里显式移出来：不可判单独公示，不静默丢弃、也不算失败。
 * <p>
 * 注意与 {@link Outcome} 的关系：五类结果（正确记忆 / 遗漏 / 混淆 / 错误持久化 / 错误复用）
 * 是<b>记忆能力</b>的分类，只在 {@code PASS/FAIL} 上有意义；{@code UNKNOWN} 不带结果类别。
 */
public enum Verdict {

    PASS("通过"),
    FAIL("未过"),
    /** 本次未能判定：无有效响应，或需语义判定但无可用评委 */
    UNKNOWN("不可判");

    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 是否构成有效判定（进入评分分母） */
    public boolean judged() {
        return this != UNKNOWN;
    }
}
