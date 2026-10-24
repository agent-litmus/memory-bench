package io.github.agentlitmus.agent;

/**
 * 被测对象的可观测能力声明。
 * <p>
 * 黑盒评测最容易出的假数字，来自「假设所有被测对象都一样可测」：跨会话用例要求能清空上下文而保留长期记忆，
 * 归因分析要求能读到记忆的落盘内容，行动维度要求能看到真实工具调用。<b>做不到这些的平台不是"被测对象能力差"，
 * 而是"我们没法这样测它"</b>——把它算成失败，得到的是一只不存在的缺陷。
 * <p>
 * 因此每个 {@link Answerer} 必须显式声明自己支持哪些能力；不支持的用例会从该对象的<b>分母</b>里移出并公示，
 * 而不是变成 0 分。
 */
public enum AgentCapability {

    /** 能重置对话上下文且保留长期记忆——跨会话用例的前提 */
    RESETS_CONTEXT("可重置会话"),

    /** 记忆落盘内容可读（目录/文件/SQLite/接口），能把「未持久化」从推断变观测 */
    MEMORY_INSPECTABLE("记忆可读"),

    /** 工具调用过程可见（如 ACP 的 tool_call 事件），行动维度与「知而不用」的前提 */
    TOOL_CALLS_VISIBLE("行动可观测"),

    /** 任务产物可取回（文件、目录），任务复用维度的内容断言前提 */
    ARTIFACTS_VISIBLE("产物可取回"),

    /** 会话状态可通过外部手段彻底清空（模板目录还原等） */
    STATE_RESETTABLE("状态可复位");

    private final String label;

    AgentCapability(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
