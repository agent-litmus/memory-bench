package io.github.agentlitmus.agent;

import java.util.List;

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
 * @param path        http 类型的接口路径，默认 {@code /api/analyze/stream}
 * @param command     command 类型的可执行文件，如 {@code kylin-agent}
 * @param args        command 类型的参数模板，支持 {@code {sessionId}} 与 {@code {question}} 占位符
 * @param resetArgs   command 类型的「重置会话」参数模板（跨会话长期保持用例用，通常删除会话状态文件）
 * @param timeoutSeconds 单次调用超时（秒）
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
                          Integer timeoutSeconds) {

    public static final String TYPE_HTTP = "http";
    public static final String TYPE_COMMAND = "command";
    public static final String TYPE_BUILTIN = "builtin";

    /** 会话 ID 占位符 */
    public static final String PH_SESSION = "{sessionId}";
    /** 提问占位符 */
    public static final String PH_QUESTION = "{question}";

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
    }

    /** HTTP 智能体 */
    public static AgentConfig http(String id, String name, String endpoint) {
        return new AgentConfig(id, name, "", TYPE_HTTP, endpoint, "/api/analyze/stream", null,
                null, List.of(), List.of(), 120);
    }

    /** 命令行智能体 */
    public static AgentConfig command(String id, String name, String command, List<String> args) {
        return command(id, name, command, args, List.of());
    }

    /** 命令行智能体（可声明会话重置命令） */
    public static AgentConfig command(String id, String name, String command,
                                      List<String> args, List<String> resetArgs) {
        return new AgentConfig(id, name, "", TYPE_COMMAND, null, null, null, command, args, resetArgs, 120);
    }

    /** 内置智能体（reference / degraded） */
    public static AgentConfig builtin(String id, String name) {
        return new AgentConfig(id, name, "", TYPE_BUILTIN, null, null, null, null, List.of(), List.of(), 120);
    }
}
