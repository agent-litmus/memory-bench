package io.github.zaojiaoci.agentlitmus;

/**
 * 判定结果类别——命题要求评分能区分「正确记忆、遗漏、混淆、错误持久化、错误复用」。
 * <p>
 * 这五类不是笼统的"通过 / 未过"，而是回答了一个更关键的问题：
 * <b>它到底错在哪一种记忆能力上。</b>
 * 只给通过率的话，两个同样 60% 的智能体可能一个是"经常忘"、另一个是"记混了"，
 * 改进方向完全不同。分类的价值就在于把"哪里错了"直接指出来。
 */
public enum Outcome {

    /** 正确记忆：该记住的记住了，不该用的没用 */
    CORRECT("正确记忆", "应保留的信息被正确记住并使用，且未混入不应保留/过时的内容"),

    /** 遗漏：该记住的没记住，或记住了却没用上 */
    OMISSION("遗漏", "应使用的信息没有出现在回答中（未记住或未调用）"),

    /** 混淆：把相近信息搞混了 */
    CONFUSION("混淆", "回答中混入了相近的干扰信息，未能区分差异"),

    /** 错误持久化：不该保留的信息被留下并复用了 */
    WRONG_PERSISTENCE("错误持久化", "不应长期保留的信息（临时/敏感/风险指令）被记住并复用"),

    /** 错误复用：用了已被取代的旧信息 */
    WRONG_REUSE("错误复用", "信息更新后仍在使用过时的旧值");

    private final String label;
    private final String description;

    Outcome(String label, String description) {
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
