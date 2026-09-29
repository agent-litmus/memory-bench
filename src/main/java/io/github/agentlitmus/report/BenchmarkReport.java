package io.github.agentlitmus.report;
import io.github.agentlitmus.core.Outcome;
import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.Judgment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆评测报告：按维度汇总多维指标，并支持输出为可读文本。
 * <p>
 * 命题要求「自动产出多维指标」，这里的「多维」即六个能力维度各自的通过率，
 * 外加一个总体通过率——既能看整体，也能定位到具体是哪一维能力薄弱。
 *
 * @param judgments 全部用例的判定结果
 */
public record BenchmarkReport(List<Judgment> judgments) {

    /** 单个维度的统计 */
    public record DimensionStat(Dimension dimension, int total, int passed, double rate) {
    }

    public BenchmarkReport {
        judgments = judgments == null ? List.of() : List.copyOf(judgments);
    }

    public int total() {
        return judgments.size();
    }

    public int passedCount() {
        return (int) judgments.stream().filter(Judgment::passed).count();
    }

    public double passRate() {
        return total() == 0 ? 0.0 : (double) passedCount() / total();
    }

    /** 按维度聚合，维度顺序与 {@link Dimension} 声明顺序一致 */
    public Map<Dimension, DimensionStat> byDimension() {
        Map<Dimension, List<Judgment>> grouped = new LinkedHashMap<>();
        for (Dimension dimension : Dimension.values()) {
            grouped.put(dimension, new ArrayList<>());
        }
        for (Judgment judgment : judgments) {
            grouped.computeIfAbsent(judgment.dimension(), k -> new ArrayList<>()).add(judgment);
        }
        Map<Dimension, DimensionStat> stats = new LinkedHashMap<>();
        grouped.forEach((dimension, list) -> {
            int passed = (int) list.stream().filter(Judgment::passed).count();
            double rate = list.isEmpty() ? 0.0 : (double) passed / list.size();
            stats.put(dimension, new DimensionStat(dimension, list.size(), passed, rate));
        });
        return stats;
    }

    /**
     * 按结果类别统计（正确记忆 / 遗漏 / 混淆 / 错误持久化 / 错误复用）。
     * <p>
     * 通过率只说明"错得多不多"，分类统计说明"错在哪一种能力上"——
     * 后者才直接指向改进方向。
     */
    public Map<Outcome, Long> outcomeCounts() {
        Map<Outcome, Long> counts = new LinkedHashMap<>();
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, 0L);
        }
        for (Judgment judgment : judgments) {
            counts.merge(judgment.outcome(), 1L, Long::sum);
        }
        return counts;
    }

    /** 输出可读报告，便于在终端查看或直接贴进申报材料 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 长期记忆评测报告 ==========\n");
        sb.append(String.format("用例总数: %d    通过: %d    通过率: %.1f%%%n",
                total(), passedCount(), passRate() * 100));
        sb.append("-------------------------------------\n");
        byDimension().forEach((dimension, stat) ->
                sb.append(String.format("%-8s %2d/%-2d   %5.1f%%%n",
                        dimension.label(), stat.passed(), stat.total(), stat.rate() * 100)));
        sb.append("-------------------------------------\n");
        sb.append("结果分类:\n");
        outcomeCounts().forEach((outcome, count) -> {
            if (count > 0) {
                sb.append(String.format("  %-8s %d%n", outcome.label(), count));
            }
        });
        sb.append("-------------------------------------\n");
        sb.append("明细:\n");
        for (Judgment judgment : judgments) {
            sb.append(String.format("  [%s] %-6s %-6s %-8s %s%n",
                    judgment.passed() ? "通过" : "未过",
                    judgment.caseId(),
                    judgment.dimension().label(),
                    judgment.outcome().label(),
                    judgment.reason()));
        }
        sb.append("=====================================\n");
        return sb.toString();
    }
}
