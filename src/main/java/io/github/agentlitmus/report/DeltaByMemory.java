package io.github.agentlitmus.report;

import io.github.agentlitmus.core.ControlArms;
import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.Judgment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆增量 Δ：把同一个智能体的「主臂」与「对照臂」分数相减。
 * <p>
 * 对照臂没有注入任何事实，它答对只能靠常识、上下文残留或预训练知识。因此
 * {@code Δ = 主臂有效分 − 对照臂有效分} 才是<b>记忆真正贡献的那一段</b>。
 * Δ 接近 0 说明这道题压根没在测记忆；Δ 为负通常不是"记忆有害"，而是噪声或题目缺陷，
 * 报告里要照实写出来而不是抹平。
 */
public final class DeltaByMemory {

    private record Pair(String base, Dimension dimension,
                        int mainJudged, int mainPassed, int controlJudged, int controlPassed) {
        double mainRate() {
            return mainJudged == 0 ? 0 : (double) mainPassed / mainJudged;
        }

        double controlRate() {
            return controlJudged == 0 ? 0 : (double) controlPassed / controlJudged;
        }

        double delta() {
            return mainRate() - controlRate();
        }
    }

    private final List<Pair> pairs;
    private final Map<Dimension, double[]> byDimension;

    private DeltaByMemory(List<Pair> pairs, Map<Dimension, double[]> byDimension) {
        this.pairs = pairs;
        this.byDimension = byDimension;
    }

    /** 逐维度与总体的 Δ；只统计同时存在主臂与对照臂的题 */
    public static DeltaByMemory of(BenchmarkReport report) {
        Map<String, int[]> main = counts(report, false);
        Map<String, int[]> control = counts(report, true);
        Map<String, Dimension> dims = dimensions(report);
        List<Pair> pairs = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : main.entrySet()) {
            int[] base = control.get(entry.getKey());
            if (base == null) {
                continue;
            }
            pairs.add(new Pair(entry.getKey(), dims.get(entry.getKey()),
                    entry.getValue()[0], entry.getValue()[1], base[0], base[1]));
        }
        Map<Dimension, double[]> byDimension = new LinkedHashMap<>();
        for (Dimension dimension : Dimension.values()) {
            double sum = 0;
            int n = 0;
            for (Pair pair : pairs) {
                if (pair.dimension() == dimension) {
                    sum += pair.delta();
                    n++;
                }
            }
            if (n > 0) {
                byDimension.put(dimension, new double[]{sum / n, n});
            }
        }
        return new DeltaByMemory(pairs, byDimension);
    }

    private static Map<String, Dimension> dimensions(BenchmarkReport report) {
        Map<String, Dimension> map = new LinkedHashMap<>();
        for (Judgment judgment : report.judgments()) {
            map.putIfAbsent(ControlArms.baseId(judgment.caseId()), judgment.dimension());
        }
        return map;
    }

    private static Map<String, int[]> counts(BenchmarkReport report, boolean controlOnly) {
        Map<String, int[]> map = new LinkedHashMap<>();
        for (Judgment judgment : report.judgments()) {
            boolean isControl = ControlArms.isControl(judgment.caseId());
            if (isControl != controlOnly) {
                continue;
            }
            int[] cell = map.computeIfAbsent(ControlArms.baseId(judgment.caseId()), k -> new int[2]);
            if (judgment.judged()) {
                cell[0]++;
                if (judgment.passed()) {
                    cell[1]++;
                }
            }
        }
        return map;
    }

    public boolean hasControls() {
        return !pairs.isEmpty();
    }

    /** 总体 Δ：逐题平均，避免各臂题数不等时失真 */
    public double overallDelta() {
        double sum = 0;
        for (Pair pair : pairs) {
            sum += pair.delta();
        }
        return pairs.isEmpty() ? 0 : sum / pairs.size();
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 记忆增量 Δ（同题双臂） ==========\n");
        if (!hasControls()) {
            sb.append("未跑对照臂：加 --with-control 后，超出对照组的部分才会被归因于记忆。\n");
            sb.append("=============================================\n");
            return sb.toString();
        }
        int mainHit = 0;
        int mainJudged = 0;
        int controlHit = 0;
        int controlJudged = 0;
        for (Pair pair : pairs) {
            mainJudged += pair.mainJudged();
            mainHit += pair.mainPassed();
            controlJudged += pair.controlJudged();
            controlHit += pair.controlPassed();
        }
        sb.append(String.format("配对题数: %d%n", pairs.size()));
        sb.append(String.format("主臂有效分: %.1f%% (%d/%d)%n",
                100.0 * mainHit / safe(mainJudged), mainHit, mainJudged));
        sb.append(String.format("对照臂有效分: %.1f%% (%d/%d)%n",
                100.0 * controlHit / safe(controlJudged), controlHit, controlJudged));
        sb.append(String.format("Δ(记忆增量, 逐题平均): %.1f 个百分点%n", overallDelta() * 100));
        for (Map.Entry<Dimension, double[]> entry : byDimension.entrySet()) {
            sb.append(String.format("  %s: Δ %.1f 个百分点（%d 题）%n",
                    entry.getKey().label(), entry.getValue()[0] * 100, (int) entry.getValue()[1]));
        }
        sb.append("说明：Δ≈0 的题不依赖记忆即可答对，应在准入环节复核；Δ<0 多为噪声或题目缺陷，照实保留。\n");
        sb.append("=============================================\n");
        return sb.toString();
    }

    private static double safe(int n) {
        return n == 0 ? 1 : n;
    }
}
