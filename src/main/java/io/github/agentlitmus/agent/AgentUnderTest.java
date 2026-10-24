package io.github.agentlitmus.agent;

import java.nio.file.Path;
import java.util.List;

/**
 * 被测智能体。
 * <p>
 * 需求「支持导入不同智能体配置进行批量对比评测」，因此被测对象需要
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
 * @param memoryRoots 该被测对象的记忆落盘目录（可为空）。提供后，评测能给出字节级状态证据，
 *                    「没写进去」不再靠回答推断；目录由接入配置或适配器给出
 */
public record AgentUnderTest(String id,
                             String name,
                             String description,
                             RunnerMode mode,
                             AgentRole role,
                             Answerer answerer,
                             List<Path> memoryRoots) {

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
        memoryRoots = memoryRoots == null || memoryRoots.isEmpty()
                ? List.of()
                : List.copyOf(memoryRoots);
    }

    /** 内置智能体：记忆由框架注入（默认主对比） */
    public static AgentUnderTest memory(String id, String name, String description, Answerer answerer) {
        return memory(id, name, description, answerer, AgentRole.PRIMARY);
    }

    public static AgentUnderTest memory(String id, String name, String description,
                                        Answerer answerer, AgentRole role) {
        return memory(id, name, description, answerer, role, List.of());
    }

    /** 内置智能体（可附带记忆落盘目录，用于状态证据） */
    public static AgentUnderTest memory(String id, String name, String description,
                                        Answerer answerer, AgentRole role, List<Path> memoryRoots) {
        return new AgentUnderTest(id, name, description, RunnerMode.MEMORY, role, answerer, memoryRoots);
    }

    /** 真实智能体：记忆由对话建立（默认主对比） */
    public static AgentUnderTest dialogue(String id, String name, String description, Answerer answerer) {
        return dialogue(id, name, description, answerer, AgentRole.PRIMARY);
    }

    public static AgentUnderTest dialogue(String id, String name, String description,
                                         Answerer answerer, AgentRole role) {
        return dialogue(id, name, description, answerer, role, List.of());
    }

    /**
     * 真实智能体（可附带记忆落盘目录）。
     * <p>
     * {@code memoryRoots} 是置信层级从 T1 升到 T2 的唯一外部凭据：给了目录，
     * 每条用例才会附带「记忆状态变化」的字节级证据；不给就只能靠回答推断。
     */
    public static AgentUnderTest dialogue(String id, String name, String description,
                                         Answerer answerer, AgentRole role, List<Path> memoryRoots) {
        return new AgentUnderTest(id, name, description, RunnerMode.DIALOGUE, role, answerer, memoryRoots);
    }
}
