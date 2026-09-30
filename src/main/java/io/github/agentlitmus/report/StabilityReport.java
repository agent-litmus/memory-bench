package io.github.agentlitmus.report;

import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.core.Dimension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 稳定性证据：对同一输入重复评测 N 次，统计各维度与总体通过率的
 * 均值、标准差、最小/最大与抖动（max-min）。
 * <p>
 * 命题「稳定性与可复现性」（15% 权重）明确看「同输入下评测结果是否稳定」，
 * 本类把这种稳定性<b>量化</b>为可报告的指标，避免仅靠口述「结果稳定」。
 */
public record StabilityReport(Map<AgentUnderTest, Map<Dimension, DimStability>> byAgent,
                              Map<AgentUnderTest, OverallStability> overall) {

    /** 单个维度 N 次运行通过率的离散程度 */
    public record DimStability(double mean, double std, double min, double max, double jitter) {
    }

    /** 总体（六维汇总）N 次运行通过率的离散程度 */
    public record OverallStability(double mean, double std, double min, double max, double jitter) {
    }

    /**
     * @param repeated 智能体 → 该智能体的 N 次评测报告
     */
    public static StabilityReport of(Map<AgentUnderTest, List<BenchmarkReport>> repeated) {
        Map<AgentUnderTest, Map<Dimension, DimStability>> byAgent = new LinkedHashMap<>();
        Map<AgentUnderTest, OverallStability> overall = new LinkedHashMap<>();

        for (Map.Entry<AgentUnderTest, List<BenchmarkReport>> entry : repeated.entrySet()) {
            AgentUnderTest agent = entry.getKey();
            List<BenchmarkReport> runs = entry.getValue();

            Map<Dimension, DimStability> dims = new LinkedHashMap<>();
            for (Dimension dimension : Dimension.values()) {
                double[] rates = runs.stream()
                        .mapToDouble(r -> r.byDimension().get(dimension).rate())
                        .toArray();
                dims.put(dimension, dimStatOf(rates));
            }
            byAgent.put(agent, dims);

            double[] overallRates = runs.stream().mapToDouble(BenchmarkReport::passRate).toArray();
            overall.put(agent, statOf(overallRates));
        }
        return new StabilityReport(byAgent, overall);
    }

    private static DimStability dimStatOf(double[] values) {
        OverallStability s = statOf(values);
        return new DimStability(s.mean(), s.std(), s.min(), s.max(), s.jitter());
    }

    private static OverallStability statOf(double[] values) {
        if (values.length == 0) {
            return new OverallStability(0, 0, 0, 0, 0);
        }
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : values) {
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double mean = sum / values.length;
        double variance = 0;
        for (double v : values) {
            variance += (v - mean) * (v - mean);
        }
        variance /= values.length; // 总体标准差
        double std = Math.sqrt(variance);
        return new OverallStability(mean, std, min, max, max - min);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 稳定性证据（同输入重复评测） ==========\n");
        overall.forEach((agent, s) -> sb.append(String.format(
                "  %-22s 总体通过率 均值 %.1f%%  抖动 %.1f%%  (σ=%.1f%%)%n",
                truncate(agent.name(), 20), s.mean() * 100, s.jitter() * 100, s.std() * 100)));
        sb.append("（抖动=各次最大值-最小值；σ=总体标准差。越小越稳定）\n");
        sb.append("==================================================\n");
        return sb.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
