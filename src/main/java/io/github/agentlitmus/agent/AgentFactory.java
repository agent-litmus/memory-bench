package io.github.agentlitmus.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体工厂：由配置创建被测对象，实现「零代码接入新智能体」。
 * <p>
 * 接入一个新智能体只需在 JSON 里写一段配置，无需改动评测流程：
 * <ul>
 *   <li>{@code http}——调用 HTTP 服务（SSE 或普通响应），适用于提供接口的智能体；</li>
 *   <li>{@code command}——调用命令行程序，适用于 CLI 形态的本地智能体；</li>
 *   <li>{@code builtin}——内置参考/缺陷智能体，用于验证评测本身有效。</li>
 * </ul>
 * 配置中的 {@code role} 字段（primary/extra）决定该智能体进入「主对比」还是「附加案例」。
 */
public final class AgentFactory {

    private AgentFactory() {
    }

    /** 由单条配置创建被测智能体 */
    public static AgentUnderTest create(AgentConfig config) {
        Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
        AgentRole role = AgentRole.parse(config.role());
        return switch (config.type()) {
            case AgentConfig.TYPE_HTTP -> AgentUnderTest.dialogue(
                    config.id(), config.name(), config.description(),
                    new HttpAnswerer(config.endpoint(), pathOf(config), config.resetPath(), timeout), role);
            case AgentConfig.TYPE_COMMAND -> AgentUnderTest.dialogue(
                    config.id(), config.name(), config.description(),
                    new CommandAnswerer(config.command(), config.args(), config.resetArgs(), timeout), role);
            case AgentConfig.TYPE_BUILTIN -> Agents.builtin(config.id(),
                    new io.github.agentlitmus.memory.FileLongTermMemory());
            default -> throw new IllegalArgumentException(
                    "不支持的智能体类型: " + config.type() + "（可选 http / command / builtin）");
        };
    }

    /**
     * 从 JSON 文件加载智能体配置列表。
     * <p>
     * 未知配置会被跳过而不中断——个别智能体配置有误时，其余仍能正常评测。
     */
    public static List<AgentUnderTest> loadFrom(Path file, List<String> warnings) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("智能体配置文件不存在: " + file);
        }
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        List<AgentUnderTest> agents = new ArrayList<>();
        try {
            AgentConfig[] configs = mapper.readValue(file.toFile(), AgentConfig[].class);
            for (AgentConfig config : configs) {
                try {
                    agents.add(create(config));
                } catch (Exception e) {
                    if (warnings != null) {
                        warnings.add("智能体配置无效，已跳过 [" + config.id() + "]: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("解析智能体配置失败: " + file + " —— " + e.getMessage(), e);
        }
        return agents;
    }

    private static String pathOf(AgentConfig config) {
        return (config.path() == null || config.path().isBlank())
                ? "/api/analyze/stream"
                : config.path();
    }
}
