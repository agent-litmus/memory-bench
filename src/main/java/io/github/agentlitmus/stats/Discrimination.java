package io.github.agentlitmus.stats;

import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.Judgment;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.report.BenchmarkResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 题目区分度：一道题如果<b>所有被测都过</b>或<b>所有被测都不过</b>，它就没有传递任何信息。
 * <p>
 * 通过率本身是「题 × 被测」的联合产物：一份 60% 的报告，可能来自 150 道有梯度的题，
 * 也可能来自 90 道白送 + 60 道无人能做。前者能排序，后者只是把难度分布抄了一遍。
 * <p>
 * 本类只<b>标记</b>不<b>删除</b>：区分度低是这一批被测的性质的函数，换一批被测可能就有效了；
 * 直接删题会让题集随样本漂移，破坏跨版本可比性。是否纳入评分由人决定，决定过程留痕。
 */
public final class Discrimination {

    public enum Flag {
        /** 有被测过、有被测不过：题目在排序上有贡献 */
        OK("有效"),
        /** 全部通过：对白送的被测无区分力，通常是题目过易或期望词过宽 */
        LOW_DISCRIMINATION("全过·区分度低"),
        /** 全部失败：可能是题目过难、期望不可达，或被测普遍缺能力 */
        LOW_DISCRIMINATION_FAIL("全败·区分度低"),
        /** 全部不可判：判定根本没发生，这道题本轮没有证据 */
        ALL_UNKNOWN("全不可判·无证据");

        private final String label;

        Flag(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param caseId   用例 ID
     * @param dimension 维度
     * @param samples  参与统计的判定次数（= 被测数 × 重复数）
     * @param passes   通过次数
     * @param judged   已判定次数（不含不可判）
     * @param flag     区分度标记
     */
    public record Item(String caseId, Dimension dimension, int samples, int judged, int passes,
                       double rate, Flag flag) {
        public boolean discriminative() {
            return flag == Flag.OK;
        }
    }

    private final List<Item> items;

    private Discrimination(List<Item> items) {
        this.items = items;
    }

    /** 由批量对比统计：同一道题跨所有主对比被测汇总样本 */
    public static Discrimination of(BenchmarkResult result) {
        List<BenchmarkReport> reports = new ArrayList<>();
        for (var agent : result.primary()) {
            BenchmarkReport report = result.reportOf(agent);
            if (report != null) {
                reports.add(report);
            }
        }
        return of(reports);
    }

    /** 由多份报告统计（多被测、或多重复），按用例 ID 对齐 */
    public static Discrimination of(List<BenchmarkReport> reports) {
        Map<String, List<Judgment>> byCase = new LinkedHashMap<>();
        Map<String, Dimension> dims = new LinkedHashMap<>();
        for (BenchmarkReport report : reports) {
            for (Judgment judgment : report.judgments()) {
                byCase.computeIfAbsent(judgment.caseId(), k -> new ArrayList<>()).add(judgment);
                dims.putIfAbsent(judgment.caseId(), judgment.dimension());
            }
        }
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, List<Judgment>> entry : byCase.entrySet()) {
            int samples = entry.getValue().size();
            int judged = 0;
            int passes = 0;
            for (Judgment judgment : entry.getValue()) {
                if (judgment.judged()) {
                    judged++;
                    if (judgment.passed()) {
                        passes++;
                    }
                }
            }
            Flag flag;
            if (judged == 0) {
                flag = Flag.ALL_UNKNOWN;
            } else if (judged == passes) {
                flag = Flag.LOW_DISCRIMINATION;
            } else if (passes == 0) {
                flag = Flag.LOW_DISCRIMINATION_FAIL;
            } else {
                flag = Flag.OK;
            }
            items.add(new Item(entry.getKey(), dims.get(entry.getKey()), samples, judged, passes,
                    judged == 0 ? 0 : (double) passes / judged, flag));
        }
        return new Discrimination(items);
    }

    public List<Item> items() {
        return List.copyOf(items);
    }

    public List<Item> flagged() {
        return items.stream().filter(item -> !item.discriminative()).toList();
    }

    /** 有区分力的题占比：这是题集质量的分数，不是被测的分数 */
    public double effectiveRatio() {
        return items.isEmpty() ? 0 : (double) items.stream().filter(Item::discriminative).count() / items.size();
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 题目区分度准入检查 ==========\n");
        if (items.isEmpty()) {
            sb.append("（无用例）\n");
            sb.append("=========================================\n");
            return sb.toString();
        }
        sb.append(String.format("题目数: %d ｜ 有区分力: %.1f%%%n", items.size(), effectiveRatio() * 100));
        int partial = 0;
        int partialJudged = 0;
        int partialSamples = 0;
        for (Item item : items) {
            if (item.judged() < item.samples()) {
                partial++;
                partialJudged += item.judged();
                partialSamples += item.samples();
            }
        }
        if (partial > 0) {
            sb.append(String.format("注意: %d 道题存在不可判运行（已判定 %d 次 / 计划 %d 次），"
                    + "分母已剔除不可判，通过率只描述已判定的那部分。%n", partial, partialJudged, partialSamples));
        }
        List<Item> flagged = flagged();
        if (flagged.isEmpty()) {
            sb.append("全部题目在本轮样本上都有区分力。\n");
        } else {
            sb.append("以下 ").append(flagged.size()).append(" 题在本轮样本上无区分力（只标记，不自动删除）:\n");
            for (Item item : flagged) {
                sb.append(String.format("  - %-10s %-6s %s  %d/%d%n",
                        item.caseId(), item.dimension() == null ? "-" : item.dimension().label(),
                        item.flag().label(), item.passes(), item.judged()));
            }
            sb.append("处理建议：全过的题加难期望或换更冷僻的事实；全败的题核对期望可达性；全不可判先修接入。\n");
        }
        sb.append("=========================================\n");
        return sb.toString();
    }
}
