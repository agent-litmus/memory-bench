package io.github.agentlitmus.report;
import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.agent.AgentUnderTest;

import java.util.List;

/**
 * 横向对比表：把多个智能体的六维指标并列，便于一眼看出差异。
 * <p>
 * 雷达图适合看形状，对比表适合看精确数值——两者一起构成「多维对比」的完整呈现。
 * 本表仅列<b>主对比（PRIMARY）</b>智能体；附加案例单独成区展示。
 * <p>
 * 表体数值固定为「六维 + 总体」共 7 列，且这一格式被 {@link ReportRates#parse} 依赖
 * （用于不重跑也能重出雷达图），因此口径补充信息一律放在表格结束线之后，不新增列。
 */
public final class CompareTable {

    private CompareTable() {
    }

    /**
     * 生成文本形式的横向对比表（仅主对比智能体）。
     *
     * @param result 批量评测结果
     * @return 对比表文本
     */
    public static String toText(BenchmarkResult result) {
        List<AgentUnderTest> ranked = result.primary();
        StringBuilder sb = new StringBuilder();

        sb.append("========== 多智能体横向对比（主对比） ==========\n");
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
        sb.append("==================================================\n");
        sb.append("口径：表内百分数为<b>有效分 = 通过 / 已判定</b>；不可判（无响应 / 评委故障）不进入分母。\n");
        for (AgentUnderTest agent : ranked) {
            BenchmarkReport report = result.reportOf(agent);
            sb.append(String.format("%-22s 覆盖率 %5.1f%%  不可判 %2d 条  悲观下界 %3.0f%%%n",
                    truncate(agent.name(), 20),
                    report.coverage() * 100,
                    report.unknownCount(),
                    report.lowerBound() * 100));
        }
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
