package io.github.agentlitmus.agent;

import java.util.Set;

/**
 * 置信层级：同样一个分数，背后的证据强度可以差很多，报告必须说清是哪一种。
 * <p>
 * 只按「能不能跑通」给分，会让只有对话接口的平台与能观测记忆落盘的平台站在同一张表里比较，
 * 而前者的归因只能是猜的。分层的意义是把<b>结论的强度</b>和<b>结论的数值</b>一起交出去。
 */
public enum ConfidenceTier {

    /** 只有对话证据：能出通过率，但失败归因只能靠用例标记推断 */
    T1("仅对话"),

    /** 加上记忆状态可读：能定位「没写进去」还是「写进去没答出来」 */
    T2("记忆可观测"),

    /** 再加上行动/产物可观测：能定位「记住了但没调用」这类知而不用 */
    T3("行动可观测");

    private final String label;

    ConfidenceTier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 由能力集合推出层级——判据只看能观测到什么，不看被测对象自称什么 */
    public static ConfidenceTier of(Set<AgentCapability> capabilities) {
        // 不用 EnumSet.copyOf：它对空集合直接抛异常，而「一项能力都没有」正是最常见的输入
        Set<AgentCapability> caps = capabilities == null ? Set.of() : capabilities;
        if (caps.contains(AgentCapability.TOOL_CALLS_VISIBLE) || caps.contains(AgentCapability.ARTIFACTS_VISIBLE)) {
            return T3;
        }
        if (caps.contains(AgentCapability.MEMORY_INSPECTABLE)) {
            return T2;
        }
        return T1;
    }
}
