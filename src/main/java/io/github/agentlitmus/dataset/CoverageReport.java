package io.github.agentlitmus.dataset;

import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.MemoryCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 数据集治理与覆盖度校验（借鉴 agentbench 的数据治理能力）。
 * <p>
 * 命题「数据设计质量」（25% 权重）要求样本具备区分度、覆盖面和扩展空间。
 * 本类在评测前自动校验数据合法性，并统计覆盖度，输出一段可解释的报告：
 * <ul>
 *   <li>合法性：用例 ID 是否唯一、是否至少存在一个判定约束；</li>
 *   <li>覆盖度：每维度用例数、是否有跨会话用例、是否存在「更新×边界」交叉用例；</li>
 *   <li>区分度：每维度是否同时具备「期望命中」与「期望排除」两类样本。</li>
 * </ul>
 */
public record CoverageReport(List<String> issues,
                             Map<Dimension, Integer> perDimension,
                             int crossSessionCount,
                             boolean hasUpdateBoundaryCross,
                             Map<Dimension, Integer> positiveSamples,
                             Map<Dimension, Integer> negativeSamples) {

    public static CoverageReport of(List<MemoryCase> cases) {
        List<String> issues = new ArrayList<>();
        Map<Dimension, Integer> perDimension = new LinkedHashMap<>();
        Map<Dimension, Integer> positiveSamples = new LinkedHashMap<>();
        Map<Dimension, Integer> negativeSamples = new LinkedHashMap<>();
        Set<String> ids = new TreeSet<>();
        int crossSessionCount = 0;
        boolean hasUpdateBoundaryCross = false;

        for (Dimension d : Dimension.values()) {
            perDimension.put(d, 0);
            positiveSamples.put(d, 0);
            negativeSamples.put(d, 0);
        }

        for (MemoryCase kase : cases) {
            if (!ids.add(kase.id())) {
                issues.add("用例 ID 重复: " + kase.id());
            }
            if ((kase.expectedContains() == null || kase.expectedContains().isBlank())
                    && (kase.expectedNotContains() == null || kase.expectedNotContains().isBlank())) {
                issues.add("用例缺少判定约束（既无包含也无排除）: " + kase.id());
            }
            perDimension.merge(kase.dimension(), 1, Integer::sum);
            if (kase.expectedContains() != null && !kase.expectedContains().isBlank()) {
                positiveSamples.merge(kase.dimension(), 1, Integer::sum);
            }
            if (kase.expectedNotContains() != null && !kase.expectedNotContains().isBlank()) {
                negativeSamples.merge(kase.dimension(), 1, Integer::sum);
            }
            if (kase.crossSession()) {
                crossSessionCount++;
            }
            // 更新×边界交叉：既涉及更新语义又涉及边界排除（当前数据集尚未覆盖，作为缺口提示）
            if (kase.dimension() == Dimension.UPDATE
                    && kase.expectedNotContains() != null && !kase.expectedNotContains().isBlank()) {
                hasUpdateBoundaryCross = true;
            }
        }

        return new CoverageReport(issues, perDimension, crossSessionCount,
                hasUpdateBoundaryCross, positiveSamples, negativeSamples);
    }

    public boolean valid() {
        return issues.isEmpty();
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 数据集覆盖度校验 ==========\n");
        if (issues.isEmpty()) {
            sb.append("合法性: 通过（无重复 ID、无无约束用例）\n");
        } else {
            sb.append("合法性: 发现问题（").append(issues.size()).append("）\n");
            for (String issue : issues) {
                sb.append("  - ").append(issue).append("\n");
            }
        }
        sb.append("每维度用例数:\n");
        perDimension.forEach((d, c) -> {
            boolean discrim = positiveSamples.get(d) > 0 && negativeSamples.get(d) > 0;
            sb.append(String.format("  %-8s %2d  命中样本 %d / 排除样本 %d  %s%n",
                    d.label(), c, positiveSamples.get(d), negativeSamples.get(d),
                    discrim ? "（区分度✓）" : "（区分度不足）"));
        });
        sb.append("跨会话用例: ").append(crossSessionCount).append(" 条\n");
        sb.append("更新×边界交叉用例: ")
                .append(hasUpdateBoundaryCross ? "已覆盖" : "缺失（建议增补：更新后验证旧敏感信息是否被一并清除）")
                .append("\n");
        sb.append("=====================================\n");
        return sb.toString();
    }
}
