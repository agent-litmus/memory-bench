package io.github.agentlitmus.report;

import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.FailureCause;
import io.github.agentlitmus.core.Judgment;
import io.github.agentlitmus.core.Outcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆评测报告：按维度汇总多维指标，并支持输出为可读文本。
 * <p>
 * 每个维度报<b>三个数</b>而不是一个：
 * <ul>
 *   <li><b>有效分</b> = 通过 / 已判定 —— 只在真正发生判定的用例上算，不被导管故障污染；</li>
 *   <li><b>覆盖率</b> = 已判定 / 计划 —— 有效分有多少证据支撑；</li>
 *   <li><b>悲观下界</b> = 通过 / 计划 —— 假设所有不可判都算失败时的分数。</li>
 * </ul>
 * 单看有效分会被"跳过难题"钻空子，单看下界又会把服务故障算成能力缺陷，所以三者同时给。
 *
 * @param judgments 全部用例的判定结果
 */
public record BenchmarkReport(List<Judgment> judgments) {

    /**
     * 单个维度的统计。
     *
     * @param rate    有效分 = passed / judged
     */
    public record DimensionStat(Dimension dimension, int total, int judged, int passed, int unknown, double rate) {

        /** 覆盖率 = 已判定 / 计划 */
        public double coverage() {
            return total == 0 ? 0.0 : (double) judged / total;
        }

        /** 悲观下界 = 通过 / 计划 */
        public double lowerBound() {
            return total == 0 ? 0.0 : (double) passed / total;
        }
    }

    public BenchmarkReport {
        judgments = judgments == null ? List.of() : List.copyOf(judgments);
    }

    /** 计划用例数（含不可判） */
    public int total() {
        return judgments.size();
    }

    /** 真正发生判定的用例数——有效分的分母 */
    public int judgedCount() {
        return (int) judgments.stream().filter(Judgment::judged).count();
    }

    public int unknownCount() {
        return total() - judgedCount();
    }

    public int passedCount() {
        return (int) judgments.stream().filter(Judgment::passed).count();
    }

    /** 总体有效分 = 通过 / 已判定 */
    public double passRate() {
        int judged = judgedCount();
        return judged == 0 ? 0.0 : (double) passedCount() / judged;
    }

    /** 总体覆盖率 = 已判定 / 计划 */
    public double coverage() {
        return total() == 0 ? 0.0 : (double) judgedCount() / total();
    }

    /** 总体悲观下界 = 通过 / 计划 */
    public double lowerBound() {
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
            int judged = (int) list.stream().filter(Judgment::judged).count();
            int passed = (int) list.stream().filter(Judgment::passed).count();
            double rate = judged == 0 ? 0.0 : (double) passed / judged;
            stats.put(dimension, new DimensionStat(dimension, list.size(), judged, passed, list.size() - judged, rate));
        });
        return stats;
    }

    /**
     * 按结果类别统计（正确记忆 / 遗漏 / 混淆 / 错误持久化 / 错误复用）。
     * <p>
     * 通过率只说明"错得多不多"，分类统计说明"错在哪一种能力上"——后者才直接指向改进方向。
     * 不可判的用例没有结果类别（{@code outcome == null}），不计入此处。
     */
    public Map<Outcome, Long> outcomeCounts() {
        Map<Outcome, Long> counts = new LinkedHashMap<>();
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, 0L);
        }
        for (Judgment judgment : judgments) {
            if (judgment.outcome() != null) {
                counts.merge(judgment.outcome(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /**
     * 按记忆失败归因统计。
     * <p>
     * 「无响应」代表评测导管或被测服务故障，与其余几类（被测对象真实记忆缺陷）分属两件事，
     * 因此这里单列，且它对应的用例已不进入有效分分母。
     */
    public Map<FailureCause, Long> causeCounts() {
        Map<FailureCause, Long> counts = new LinkedHashMap<>();
        for (FailureCause cause : FailureCause.values()) {
            counts.put(cause, 0L);
        }
        for (Judgment judgment : judgments) {
            FailureCause cause = judgment.cause();
            if (cause != null) {
                counts.merge(cause, 1L, Long::sum);
            }
        }
        return counts;
    }

    /** 不可判用例清单——每个数字都能被追溯到"少了哪几条证据" */
    public List<Judgment> unknowns() {
        List<Judgment> list = new ArrayList<>();
        for (Judgment judgment : judgments) {
            if (!judgment.judged()) {
                list.add(judgment);
            }
        }
        return list;
    }

    /** 输出可读报告，便于在终端查看或直接引用 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 长期记忆评测报告 ==========\n");
        sb.append(String.format("用例总数: %d    已判定: %d    通过: %d    不可判: %d%n",
                total(), judgedCount(), passedCount(), unknownCount()));
        sb.append(String.format("有效分(通过/已判定): %.1f%%    覆盖率(已判定/计划): %.1f%%    悲观下界(通过/计划): %.1f%%%n",
                passRate() * 100, coverage() * 100, lowerBound() * 100));
        sb.append("-------------------------------------\n");
        byDimension().forEach((dimension, stat) ->
                sb.append(String.format("%-8s %2d/%-2d   %5.1f%%   判定 %d/%d%n",
                        dimension.label(), stat.passed(), stat.judged(), stat.rate() * 100,
                        stat.judged(), stat.total())));
        sb.append("-------------------------------------\n");
        sb.append("结果分类:\n");
        outcomeCounts().forEach((outcome, count) -> {
            if (count > 0) {
                sb.append(String.format("  %-8s %d%n", outcome.label(), count));
            }
        });
        sb.append("-------------------------------------\n");
        sb.append("失败归因（记忆生命周期阶段 / 可观测信号）:\n");
        causeCounts().forEach((cause, count) -> {
            if (count > 0) {
                sb.append(String.format("  %-10s %d   %s%n", cause.label(), count, cause.description()));
            }
        });
        sb.append("-------------------------------------\n");
        sb.append("明细:\n");
        for (Judgment judgment : judgments) {
            sb.append(String.format("  [%-3s] %-6s %-6s %-8s %-10s %s%n",
                    judgment.verdict().label(),
                    judgment.caseId(),
                    judgment.dimension().label(),
                    judgment.outcome() == null ? "—" : judgment.outcome().label(),
                    judgment.cause() == null ? "-" : judgment.cause().label(),
                    judgment.reason()));
        }
        sb.append("=====================================\n");
        return sb.toString();
    }
}
