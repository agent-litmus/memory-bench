package io.github.agentlitmus.core;

/**
 * 长期记忆能力的六个评测维度。
 */
public enum Dimension {

    /** 应保留的信息是否被稳定记住并在后续使用 */
    RETENTION("长期保持"),

    /** 后续交互中是否能正确调用相关信息 */
    RECALL("记忆调用"),

    /** 新信息出现后是否能够正确覆盖或修正旧信息 */
    UPDATE("动态更新"),

    /** 是否能识别相近信息之间的差异 */
    DISCRIMINATION("相近区分"),

    /** 不应长期保留或不应复用的信息是否被正确识别 */
    BOUNDARY("边界识别"),

    /** 实际任务执行中是否能合理使用历史信息 */
    REUSE("任务复用");

    private final String label;

    Dimension(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
