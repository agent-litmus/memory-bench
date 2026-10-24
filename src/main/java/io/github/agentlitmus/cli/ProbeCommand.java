package io.github.agentlitmus.cli;

import io.github.agentlitmus.agent.AgentConfig;
import io.github.agentlitmus.agent.AgentFactory;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.probe.AgentProber;
import io.github.agentlitmus.probe.ProbeResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code litmus probe}——接入探测：跑评测<b>之前</b>先探清这个平台能被测到什么程度。
 * <p>
 * 输入是一份智能体配置（与 {@code run --agents-config} 同一格式），输出三样东西：
 * <ul>
 *   <li>{@code probe.json}——逐条观测结论 + 生效能力 + 置信层级；</li>
 *   <li>{@code agents-<id>.draft.json}——回填好 capabilities/stateDir 的配置草案，可直接用于 run；</li>
 *   <li>{@code probe.md}——人读的结论与「哪些用例在这个平台上测不了」。</li>
 * </ul>
 * 探测只花极小的对话成本（每个对象不到十回合）且全部使用虚构标记词，
 * 目的是让后面几百回合的评测建立在<b>正确的可测边界</b>上。
 */
public final class ProbeCommand implements Command {

    @Override
    public String name() {
        return "probe";
    }

    @Override
    public void run(CliOptions options) throws IOException {
        if (options.agentsConfig == null || options.agentsConfig.isBlank()) {
            throw new IllegalArgumentException("litmus probe 需要 --agents-config <file>："
                    + "探针按配置逐个探测被测对象（http / command / builtin）");
        }
        Path outDir = Path.of(options.out);
        Files.createDirectories(outDir);
        List<String> warnings = new ArrayList<>();
        List<AgentConfig> configs = AgentFactory.loadConfigs(Path.of(options.agentsConfig));
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("配置文件里没有可用的智能体: " + options.agentsConfig);
        }
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 接入探测结论\n\n配置: ").append(options.agentsConfig).append("\n\n");
        List<Map<String, Object>> jsonResults = new ArrayList<>();
        for (AgentConfig config : configs) {
            AgentUnderTest agent;
            try {
                agent = AgentFactory.create(config);
            } catch (Exception e) {
                warnings.add("配置无效，已跳过 [" + config.id() + "]: " + e.getMessage());
                continue;
            }
            List<Path> roots = new ArrayList<>(agent.memoryRoots());
            if (options.stateDir != null && !options.stateDir.isBlank()) {
                Path override = Path.of(options.stateDir).toAbsolutePath();
                if (roots.stream().noneMatch(r -> r.toAbsolutePath().equals(override))) {
                    roots.add(0, override);
                }
            }
            ProbeResult result = new AgentProber(agent.answerer(), config.declaredCapabilities(), roots)
                    .probe(config.id(), config.name());
            System.out.println(result.toText());
            jsonResults.add(result.toMap());
            markdown.append("## ").append(result.agentName()).append("\n\n")
                    .append("- 置信层级：**").append(result.tier().name())
                    .append("（").append(result.tier().label()).append("）**\n")
                    .append("- 跨会话用例可用：").append(result.crossSessionUsable()
                            ? "是" : "否（跨会话题将判为不可判、移出分母）").append('\n')
                    .append("- 生效能力：").append(result.granted().isEmpty() ? "仅对话"
                            : result.granted().stream().map(c -> c.label()).toList()).append("\n\n")
                    .append("| 观测 | 结论 | 依据 |\n| --- | --- | --- |\n");
            result.steps().forEach(step -> markdown.append("| ").append(mdEscape(step.name()))
                    .append(" | ").append(step.result().label())
                    .append(" | ").append(mdEscape(step.detail())).append(" |\n"));
            markdown.append('\n');
            result.warnings().forEach(w -> markdown.append("> 提示：").append(mdEscape(w)).append("\n"));
            markdown.append('\n');
            Path draft = outDir.resolve("agents-" + config.id() + ".draft.json");
            Files.writeString(draft, result.configDraftJson(config.type(), config.endpoint(),
                    config.command(), config.args(), config.resetArgs()), StandardCharsets.UTF_8);
        }
        Files.writeString(outDir.resolve("probe.md"), markdown.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("probe.json"), Runs.pretty(jsonResults),
                StandardCharsets.UTF_8);
        warnings.forEach(w -> System.out.println("警告: " + w));
        System.out.println("探测结论已输出到: " + outDir.toAbsolutePath()
                + "\n  ├─ probe.md               逐条观测与提示（人读）"
                + "\n  ├─ probe.json             结构化结论（脚本消费）"
                + "\n  └─ agents-<id>.draft.json 回填能力后的配置草案，可直接用于 litmus run --agents-config");
    }

    /** Markdown 表格里不能出现裸竖线或换行；观测依据里两者都可能有 */
    private static String mdEscape(String text) {
        return text == null ? "" : text.replace("|", "／").replaceAll("\\s+", " ").trim();
    }
}
