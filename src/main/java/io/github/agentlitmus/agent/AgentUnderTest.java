package io.github.agentlitmus.agent;

/**
 * 被测智能体。
 * <p>
 * 命题要求「支持导入不同智能体配置进行批量对比评测」，因此被测对象需要
 * 有稳定身份（id / 名称）以便在同一张报告里区分，并声明自己的评测模式
 * （记忆由框架注入，还是由对话建立），以及<b>对比角色</b>
 * （{@link AgentRole#PRIMARY} 主对比 / {@link AgentRole#EXTRA} 附加案例）。
 *
 * @param id          唯一标识（命令行 --agents 使用）
 * @param name        展示名称
 * @param description 说明，会写进报告
 * @param mode        评测模式：{@link RunnerMode#MEMORY} 或 {@link RunnerMode#DIALOGUE}
 * @param role        对比角色：主对比（PRIMARY）或附加案例（EXTRA）
 * @param answerer    回答实现
 */
public record AgentUnderTest(String id,
                             String name,
                             String description,
                             RunnerMode mode,
                             AgentRole role,
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
        if (role == null) {
            role = AgentRole.PRIMARY;
        }
    }

    /** 内置智能体：记忆由框架注入（默认主对比） */
    public static AgentUnderTest memory(String id, String name, String description, Answerer answerer) {
        return memory(id, name, description, answerer, AgentRole.PRIMARY);
    }

    public static AgentUnderTest memory(String id, String name, String description,
                                        Answerer answerer, AgentRole role) {
        return new AgentUnderTest(id, name, description, RunnerMode.MEMORY, role, answerer);
    }

    /** 真实智能体：记忆由对话建立（默认主对比） */
    public static AgentUnderTest dialogue(String id, String name, String description, Answerer answerer) {
        return dialogue(id, name, description, answerer, AgentRole.PRIMARY);
    }

    public static AgentUnderTest dialogue(String id, String name, String description,
                                         Answerer answerer, AgentRole role) {
        return new AgentUnderTest(id, name, description, RunnerMode.DIALOGUE, role, answerer);
    }
}
