package io.github.agentlitmus.agent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 被测智能体的接入配置（对应命题"支持导入不同智能体配置"）。
 * <p>
 * 设计目标是<b>零代码接入新智能体</b>：无论对方是 HTTP 服务、命令行程序还是内置实现，
 * 都只需在 JSON 里描述，评测流程无需改动。
 *
 * @param id          唯一标识（报告中显示）
 * @param name        展示名称
 * @param description 说明
 * @param type        接入类型：{@code http} / {@code command} / {@code builtin}
 * @param endpoint    http 类型的服务地址，如 {@code http://localhost:8089}
 * @param path        http 类型的接口路径，默认 {@code /api/v1/analyze/stream}
 * @param resetPath   http 类型的会话重置路径（跨会话长期保持用例用）
 * @param command     command 类型的可执行文件，如 {@code kylin-agent}
 * @param args        command 类型的参数模板，支持 {@code {sessionId}} 与 {@code {question}} 占位符
 * @param resetArgs   command 类型的「重置会话」参数模板（跨会话长期保持用例用，通常删除会话状态文件）
 * @param timeoutSeconds 单次调用超时（秒）
 * @param role        对比角色：{@code primary}（主对比）/ {@code extra}（附加案例），默认 primary
 * @param sandbox     command 类型的进程隔离层级：{@code isolated}（默认）/ {@code strict} / {@code none}
 * @param stateDir    被测智能体的记忆落盘目录（可为空）。给了它，评测才能把「没写进去」
 *                    从推断变成观测——{@code litmus probe} 会扫描该目录并据此提升置信层级
 * @param capabilities 接入方显式声明的能力名（如 {@code MEMORY_INSPECTABLE}）；
 *                    与适配器自证能力取并集，非法名称直接报错（由加载器记为警告并跳过该智能体）
 * @param sandboxSeed command 类型专用：需要复制进沙箱家目录的宿主文件/目录（如 {@code ~/.kylinbot}）。
 *                    本地大模型智能体要读自己的凭据才起得来，而沙箱刻意把 HOME 指向空目录；
 *                    注入项会写进隔离说明与运行契约，不做暗中放行。支持 {@code ~} 与相对路径（相对启动目录）
 */
public record AgentConfig(String id,
                          String name,
                          String description,
                          String type,
                          String endpoint,
                          String path,
                          String resetPath,
                          String command,
                          List<String> args,
                          List<String> resetArgs,
                          Integer timeoutSeconds,
                          String role,
                          String sandbox,
                          String stateDir,
                          List<String> capabilities,
                          List<String> sandboxSeed) {

    public static final String TYPE_HTTP = "http";
    public static final String TYPE_COMMAND = "command";
    public static final String TYPE_BUILTIN = "builtin";

    public static final String SANDBOX_ISOLATED = "isolated";
    public static final String SANDBOX_STRICT = "strict";
    public static final String SANDBOX_NONE = "none";

    /** 会话 ID 占位符 */
    public static final String PH_SESSION = "{sessionId}";
    /** 提问占位符 */
    public static final String PH_QUESTION = "{question}";
    /** 沙箱家目录占位符：把智能体的读写显式引到隔离区 */
    public static final String PH_SANDBOX_HOME = "{sandboxHome}";

    public AgentConfig {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("智能体配置必须提供 id");
        }
        type = (type == null || type.isBlank()) ? TYPE_HTTP : type.trim().toLowerCase();
        if (name == null || name.isBlank()) {
            name = id;
        }
        if (description == null) {
            description = "";
        }
        if (args == null) {
            args = List.of();
        } else {
            args = List.copyOf(args);
        }
        if (resetArgs == null) {
            resetArgs = List.of();
        } else {
            resetArgs = List.copyOf(resetArgs);
        }
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = 120;
        }
        // 角色容错：空/非法一律回退 primary
        role = (role == null || role.isBlank()) ? "primary" : role.trim().toLowerCase();
        sandbox = (sandbox == null || sandbox.isBlank()) ? SANDBOX_ISOLATED : sandbox.trim().toLowerCase();
        if (!SANDBOX_ISOLATED.equals(sandbox) && !SANDBOX_STRICT.equals(sandbox)
                && !SANDBOX_NONE.equals(sandbox)) {
            throw new IllegalArgumentException("sandbox 只支持 isolated / strict / none，当前为: " + sandbox);
        }
        capabilities = List.copyOf(normalizeCapabilities(capabilities == null ? List.of() : capabilities));
        sandboxSeed = sandboxSeed == null ? List.of() : List.copyOf(sandboxSeed);
    }

    /**
     * 能力名规范化：大小写与空格容错，但<b>非法名称一律报错</b>。
     * 静默丢掉一个拼错的能力名，等于悄悄把该智能体的置信层级降一档——
     * 报错由加载器收集成警告并跳过该智能体，比让报告里出现没人注意到的降级更诚实。
     */
    private static List<String> normalizeCapabilities(List<String> raw) {
        List<String> names = new ArrayList<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String name = item.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            names.add(parseCapability(name, item).name());
        }
        return names;
    }

    private static AgentCapability parseCapability(String normalizedName, String original) {
        try {
            return AgentCapability.valueOf(normalizedName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("capabilities 只支持 "
                    + java.util.Arrays.toString(AgentCapability.values())
                    + "，当前为: " + original);
        }
    }

    /** 配置里显式声明的能力集合（适配器自证的能力另由 {@link Answerer#capabilities()} 提供） */
    public Set<AgentCapability> declaredCapabilities() {
        Set<AgentCapability> out = EnumSet.noneOf(AgentCapability.class);
        for (String name : capabilities) {
            out.add(parseCapability(name, name));
        }
        return out;
    }

    /** HTTP 智能体 */
    public static AgentConfig http(String id, String name, String endpoint) {
        return new AgentConfig(id, name, "", TYPE_HTTP, endpoint, "/api/v1/analyze/stream", null,
                null, List.of(), List.of(), 120, null, null, null, List.of(), List.of());
    }

    /** 命令行智能体 */
    public static AgentConfig command(String id, String name, String command, List<String> args) {
        return command(id, name, command, args, List.of());
    }

    /** 命令行智能体（可声明会话重置命令） */
    public static AgentConfig command(String id, String name, String command,
                                      List<String> args, List<String> resetArgs) {
        return new AgentConfig(id, name, "", TYPE_COMMAND, null, null, null, command, args, resetArgs,
                120, null, null, null, List.of(), List.of());
    }

    /** 内置智能体（reference / degraded） */
    public static AgentConfig builtin(String id, String name) {
        return new AgentConfig(id, name, "", TYPE_BUILTIN, null, null, null, null, List.of(), List.of(),
                120, null, null, null, List.of(), List.of());
    }
}
