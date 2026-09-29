package io.github.zaojiaoci.agentlitmus.report;
import io.github.zaojiaoci.agentlitmus.core.Dimension;
import io.github.zaojiaoci.agentlitmus.agent.AgentUnderTest;

import java.util.List;

/**
 * 横向对比表：把多个智能体的六维指标并列，便于一眼看出差异。
 * <p>
 * 雷达图适合看形状，对比表适合看精确数值——两者一起构成「多维对比」的完整呈现。
 */
public final class CompareTable {

    private CompareTable() {
    }

    /**
     * 生成文本形式的横向对比表。
     *
     * @param result 批量评测结果
     * @return 对比表文本
     */
    public static String toText(BenchmarkResult result) {
        List<AgentUnderTest> ranked = result.ranked();
        StringBuilder sb = new StringBuilder();

        sb.append("========== 多智能体横向对比 ==========\n");
        sb.append(String.format("%-22s", "智能体"));
        for (Dimension dimension : Dimension.values()) {
            sb.append(String.format("%-8s", dimension.label()));
        }
        sb.append(String.format("%-8s%n", "总体"));

        for (AgentUnderTest agent : ranked) {
            BenchmarkReport report = result.reportOf(agent);
            sb.append(String.format("%-22s", truncate(agent.name(), 20)));
            for (Dimension dimension : Dimension.values()) {
                BenchmarkReport.DimensionStat stat = report.byDimension().get(dimension);
                sb.append(String.format("%-8s", formatRate(stat.rate())));
            }
            sb.append(String.format("%-8s%n", formatRate(report.passRate())));
        }
        sb.append("=====================================\n");
        return sb.toString();
    }

    private static String formatRate(double rate) {
        return String.format("%.0f%%", rate * 100);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
