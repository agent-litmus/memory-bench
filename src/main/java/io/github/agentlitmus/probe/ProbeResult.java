package io.github.agentlitmus.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.agent.AgentConfig;
import io.github.agentlitmus.agent.ConfidenceTier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 探针结论：这个平台<b>能被测到什么程度</b>，以及据此生成的接入配置草案。
 *
 * @param agentId   被测对象 id
 * @param agentName 展示名
 * @param steps     逐条观测（写入 probe.md，便于人核对探针的判断）
 * @param observed  由探针<b>观测</b>到的能力
 * @param declared  接入方在配置里<b>声明</b>的能力
 * @param granted   实际生效的能力（observed ∪ declared ∪ 适配器自证）——置信层级由它推出
 * @param stateDir  扫描过的记忆目录；null 表示没有可扫描目录
 * @param warnings  需要接入方注意的事（尤其是「测不了」的部分）
 * @param markers   本次探测使用的虚构标记词（可复查、可复现）
 */
public record ProbeResult(String agentId,
                          String agentName,
                          List<ProbeStep> steps,
                          Set<AgentCapability> observed,
                          Set<AgentCapability> declared,
                          Set<AgentCapability> granted,
                          Path stateDir,
                          List<String> warnings,
                          Map<String, String> markers) {

    public ProbeResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        observed = copySorted(observed);
        declared = copySorted(declared);
        granted = copySorted(granted);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        markers = markers == null ? Map.of() : Map.copyOf(markers);
    }

    /** 置信层级：判据只看「实际能观测到什么」，不接受任何自称的等级 */
    public ConfidenceTier tier() {
        return ConfidenceTier.of(granted);
    }

    private static Set<AgentCapability> copySorted(Set<AgentCapability> caps) {
        return caps == null ? Set.of() : Set.copyOf(new TreeSet<>(caps));
    }

    /**
     * 某能力的观测结论；没有对应步骤时返回 {@link ProbeStep.Result#NOT_TESTABLE} */
    public ProbeStep.Result resultOf(AgentCapability capability) {
        return steps.stream()
                .filter(s -> s.capability() == capability)
                .findFirst()
                .map(ProbeStep::result)
                .orElse(ProbeStep.Result.NOT_TESTABLE);
    }

    /**
     * 跨会话用例能否在该平台成立。
     * <p>
     * 只看 {@link AgentCapability#RESETS_CONTEXT}：能重置上下文才有「长期记忆」可言，
     * 否则答对可能只是靠窗口里的原文，用例测的不是它声称的东西。
     */
    public boolean crossSessionUsable() {
        return granted.contains(AgentCapability.RESETS_CONTEXT);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 接入探测: ").append(agentName).append(" ==========\n");
        sb.append("置信层级: ").append(tier()).append("（").append(tier().label()).append("）")
                .append("    生效能力: ").append(granted.isEmpty() ? "（仅对话）" : labels(granted)).append('\n');
        for (ProbeStep step : steps) {
            sb.append("  ").append(step.result().label())
                    .append("  ").append(step.name());
            if (step.capability() != null) {
                sb.append("  [").append(step.capability().label()).append(']');
            }
            sb.append('\n').append("        依据: ").append(step.detail()).append('\n');
        }
        sb.append("标记词: ").append(markers).append('\n');
        if (!warnings.isEmpty()) {
            sb.append("提示:\n");
            warnings.forEach(w -> sb.append("  - ").append(w).append('\n'));
        }
        return sb.toString();
    }

    /** 结构化输出（probe.json）——字段与 toText 一致，便于脚本消费 */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentId", agentId);
        out.put("agentName", agentName);
        out.put("confidenceTier", tier().name());
        out.put("observedCapabilities", names(observed));
        out.put("declaredCapabilities", names(declared));
        out.put("grantedCapabilities", names(granted));
        out.put("stateDir", stateDir == null ? null : stateDir.toString());
        out.put("markers", markers);
        out.put("warnings", warnings);
        List<Map<String, Object>> stepList = new ArrayList<>();
        for (ProbeStep step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", step.name());
            item.put("capability", step.capability() == null ? null : step.capability().name());
            item.put("result", step.result().name());
            item.put("detail", step.detail());
            stepList.add(item);
        }
        out.put("steps", stepList);
        return out;
    }

    /**
     * 接入配置草案：把探测到的能力回填成 {@code agents-<id>.draft.json} 可直接使用的形态。
     * <p>
     * 只补<b>有证据</b>的字段：探测不到的能力留空（保守），让接入方自己决定要不要手写声明。
     */
    public String configDraftJson(String type, String endpoint, String command,
                                  List<String> args, List<String> resetArgs) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("id", agentId);
        draft.put("name", agentName);
        draft.put("type", type);
        if (endpoint != null && !endpoint.isBlank()) {
            draft.put("endpoint", endpoint);
        }
        if (command != null && !command.isBlank()) {
            draft.put("command", command);
        }
        if (args != null && !args.isEmpty()) {
            draft.put("args", args);
        }
        if (resetArgs != null && !resetArgs.isEmpty()) {
            draft.put("resetArgs", resetArgs);
        }
        if (stateDir != null && !isCommandSandbox(type)) {
            draft.put("stateDir", stateDir.toString());
        }
        draft.put("capabilities", names(granted));
        // 命令行智能体的 stateDir 指向本次探测那一份临时沙箱，下一轮运行会另开一份，
        // 写进草案等于埋一个指向不存在目录的假证据源；运行时适配器会自己带上沙箱家目录，无需声明。
        if (stateDir != null && isCommandSandbox(type)) {
            draft.put("note", "stateDir 未写入草案：本次探测到的是这一轮临时沙箱里的路径，"
                    + "下一轮运行会自动重新创建并由适配器提供；手写 stateDir 只会指向空目录。");
        }
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(List.of(draft));
        } catch (Exception e) {
            throw new IllegalStateException("生成配置草案失败: " + e.getMessage(), e);
        }
    }

    private static boolean isCommandSandbox(String type) {
        return AgentConfig.TYPE_COMMAND.equalsIgnoreCase(type == null ? "" : type.trim());
    }

    private static List<String> names(Set<AgentCapability> caps) {
        return caps.stream().map(Enum::name).toList();
    }

    private static String labels(Set<AgentCapability> caps) {
        return caps.stream().map(AgentCapability::label).reduce((a, b) -> a + "、" + b).orElse("");
    }
}
