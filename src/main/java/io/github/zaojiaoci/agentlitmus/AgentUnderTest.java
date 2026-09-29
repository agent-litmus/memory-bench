package io.github.zaojiaoci.agentlitmus;

/**
 * 被测智能体。
 * <p>
 * 命题要求「支持导入不同智能体配置进行批量对比评测」，因此被测对象需要
 * 有稳定身份（id / 名称）以便在同一张报告里区分，而不只是一个回答函数。
 *
 * @param id          唯一标识（命令行 --agents 使用）
 * @param name        展示名称
 * @param description 说明，会写进报告
 * @param answerer    回答实现
 */
public record AgentUnderTest(String id,
                             String name,
                             String description,
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
    }

    /** 便捷构造：用 id 同时作为名称 */
    public static AgentUnderTest of(String id, Answerer answerer) {
        return new AgentUnderTest(id, id, "", answerer);
    }

    public static AgentUnderTest of(String id, String name, String description, Answerer answerer) {
        return new AgentUnderTest(id, name, description, answerer);
    }
}
