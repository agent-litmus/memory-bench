package io.github.agentlitmus.agent;

import java.util.Locale;

/**
 * 被测智能体的对比角色——用于把「主对比」与「附加案例」分开呈现。
 * <p>
 * 命题评的是<b>通用智能体长期记忆</b>。把垂直领域智能体（如岗位分析 agent）误放进主对比，
 * 会产生「通用记忆基准评垂直 agent」的不公平观感，反而拖累通用性得分。
 * 因此引入角色标记：
 * <ul>
 *   <li>{@link #PRIMARY}——主对比对象（通用智能体 / 内置对照），进入雷达图与横向对比表；</li>
 *   <li>{@link #EXTRA}——附加案例（通常为跨领域垂直 agent），单独成区展示，
 *       作为「尺子能否区分垂直 agent 与通用 agent」的鲁棒性证据，不计入通用记忆评分主体。</li>
 * </ul>
 */
public enum AgentRole {
    PRIMARY,
    EXTRA;

    /** 容错解析：任意非法/空值一律回退为 PRIMARY */
    public static AgentRole parse(String value) {
        if (value == null) {
            return PRIMARY;
        }
        try {
            return AgentRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return PRIMARY;
        }
    }
}
