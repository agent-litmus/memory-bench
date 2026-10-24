package io.github.agentlitmus.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentlitmus.memory.FileLongTermMemory;
import io.github.agentlitmus.memory.LongTermMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
        return create(config, null);
    }

    /**
     * 由单条配置创建被测智能体。
     *
     * @param sandboxBase 命令行智能体的沙箱根目录；null 用系统临时目录
     */
    public static AgentUnderTest create(AgentConfig config, Path sandboxBase) {
        Duration timeout = Duration.ofSeconds(config.timeoutSeconds());
        AgentRole role = AgentRole.parse(config.role());
        AgentUnderTest agent = switch (config.type()) {
            case AgentConfig.TYPE_HTTP -> AgentUnderTest.dialogue(
                    config.id(), config.name(), config.description(),
                    new HttpAnswerer(config.endpoint(), pathOf(config), config.resetPath(), timeout), role);
            case AgentConfig.TYPE_COMMAND -> AgentUnderTest.dialogue(
                    config.id(), config.name(), config.description(),
                    new CommandAnswerer(config.command(), config.args(), config.resetArgs(),
                            timeout, sandboxBase, config.sandbox(), config.sandboxSeed()), role);
            case AgentConfig.TYPE_BUILTIN -> requireBuiltin(config.id());
            default -> throw new IllegalArgumentException(
                    "不支持的智能体类型: " + config.type() + "（可选 http / command / builtin）");
        };
        // 接入方声明的能力叠加在适配器自证能力之上（并集），置信层级随之重算
        Answerer overlaid = CapabilityOverlay.of(agent.answerer(), config.declaredCapabilities());
        List<Path> roots = memoryRootsOf(agent, config, overlaid);
        return new AgentUnderTest(agent.id(), agent.name(),
                describeWithTier(agent.description(), overlaid, roots),
                agent.mode(), agent.role(), overlaid, roots);
    }

    /**
     * 内置智能体：未识别的 id 必须报错，不能返回 null 静默少一行——
     * 每款用独立记忆目录，避免同一轮里两款内置智能体互相污染。
     */
    private static AgentUnderTest requireBuiltin(String id) {
        String key = id == null ? "" : id.trim().toLowerCase();
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "agentlitmus-memory", "agent-" + key);
        clearDirectory(dir);
        LongTermMemory memory = new FileLongTermMemory(dir.toString());
        AgentUnderTest agent = Agents.builtin(id, memory);
        if (agent == null) {
            throw new IllegalArgumentException("未识别的内置智能体 id: " + id + "（内置可选 "
                    + String.join(", ", Agents.REFERENCE, Agents.DEGRADED, Agents.LLM, Agents.HERMES) + "）");
        }
        return agent;
    }

    /**
     * 清空内置智能体的记忆目录：配置里没地方指定「每轮运行的独立目录」，
     * 若沿用上一次的落盘记忆，同一份配置第二次跑会带上第一轮的旧记忆，分数不可复现。
     */
    private static void clearDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.equals(dir)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法清空内置智能体记忆目录: " + dir + " —— " + e.getMessage(), e);
        }
    }

    /**
     * 该被测对象的记忆落盘目录，三处来源取并集：
     * 适配器已给出的（内置智能体的自家记忆目录）、配置显式给的 {@code stateDir}、
     * 命令行智能体的沙箱家目录（产物与临时状态都在那里，评测方天然可读）。
     */
    private static List<Path> memoryRootsOf(AgentUnderTest agent, AgentConfig config, Answerer answerer) {
        List<Path> roots = new ArrayList<>(agent.memoryRoots());
        addRoot(roots, config.stateDir());
        if (CapabilityOverlay.unwrap(answerer) instanceof CommandAnswerer command) {
            addRoot(roots, command.sandboxHome());
        }
        return List.copyOf(roots);
    }

    /** 追加一个状态目录；空值与重复目录不加入（同一目录被两处声明时只算一次证据） */
    private static void addRoot(List<Path> roots, String dir) {
        if (dir != null && !dir.isBlank()) {
            addRoot(roots, Path.of(dir.trim()));
        }
    }

    private static void addRoot(List<Path> roots, Path dir) {
        if (dir == null) {
            return;
        }
        Path absolute = dir.toAbsolutePath();
        if (roots.stream().noneMatch(existing -> existing.toAbsolutePath().equals(absolute))) {
            roots.add(dir);
        }
    }

    /** 把置信层级与状态目录写进描述：报告里每一行分数都得自带「它凭什么可信」的说明 */
    private static String describeWithTier(String description, Answerer answerer, List<Path> roots) {
        String base = description == null ? "" : description;
        return base + "〔置信层级 " + answerer.confidenceTier().name()
                + "（" + answerer.confidenceTier().label() + "）"
                + "｜能力 " + (answerer.capabilities().isEmpty() ? "仅对话"
                    : answerer.capabilities().stream().map(AgentCapability::label).sorted().toList())
                + "｜状态目录 " + (roots.isEmpty() ? "未提供" : roots.size()) + "〕";
    }

    /** 从 JSON 文件读取原始配置列表（探针需要拿到配置本身，而不只是创建好的被测对象） */
    public static List<AgentConfig> loadConfigs(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("智能体配置文件不存在: " + file);
        }
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            return List.of(mapper.readValue(file.toFile(), AgentConfig[].class));
        } catch (IOException e) {
            throw new IllegalStateException("解析智能体配置失败: " + file + " —— " + e.getMessage(), e);
        }
    }

    /**
     * 从 JSON 文件加载智能体配置列表。
     * <p>
     * 未知配置会被跳过而不中断——个别智能体配置有误时，其余仍能正常评测。
     */
    public static List<AgentUnderTest> loadFrom(Path file, List<String> warnings) {
        List<AgentUnderTest> agents = new ArrayList<>();
        for (AgentConfig config : loadConfigs(file)) {
            try {
                agents.add(create(config));
            } catch (Exception e) {
                if (warnings != null) {
                    warnings.add("智能体配置无效，已跳过 [" + config.id() + "]: " + e.getMessage());
                }
            }
        }
        return agents;
    }

    private static String pathOf(AgentConfig config) {
        return (config.path() == null || config.path().isBlank())
                ? "/api/v1/analyze/stream"
                : config.path();
    }
}
