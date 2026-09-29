package io.github.zaojiaoci.agentlitmus.agent;

/**
 * 被测智能体。
 * <p>
 * 命题要求「支持导入不同智能体配置进行批量对比评测」，因此被测对象需要
 * 有稳定身份（id / 名称）以便在同一张报告里区分，并声明自己的评测模式
 * （记忆由框架注入，还是由对话建立）。
 *
 * @param id          唯一标识（命令行 --agents 使用）
 * @param name        展示名称
 * @param description 说明，会写进报告
 * @param mode        评测模式：{@link RunnerMode#MEMORY} 或 {@link RunnerMode#DIALOGUE}
 * @param answerer    回答实现
 */
public record AgentUnderTest(String id,
                             String name,
                             String description,
                             RunnerMode mode,
                             Answerer answerer) {

    public AgentUnderTest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("被测智能体必须有 id");
        }
        if (answerer == null) {
            throw new IllegalArgumentException("被测智能体必须有回答实现: " + id);
        }
        if (name == null || name.isBlank()) {
            name = id;
        }
        if (description == null) {
            description = "";
        }
        if (mode == null) {
            mode = RunnerMode.DIALOGUE;
        }
    }

    /** 内置智能体：记忆由框架注入 */
    public static AgentUnderTest memory(String id, String name, String description, Answerer answerer) {
        return new AgentUnderTest(id, name, description, RunnerMode.MEMORY, answerer);
    }

    /** 真实智能体：记忆由对话建立 */
    public static AgentUnderTest dialogue(String id, String name, String description, Answerer answerer) {
        return new AgentUnderTest(id, name, description, RunnerMode.DIALOGUE, answerer);
    }
}
