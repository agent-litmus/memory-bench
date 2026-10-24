package io.github.agentlitmus.stats;

import io.github.agentlitmus.core.Judgment;
import io.github.agentlitmus.report.BenchmarkReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逐题一致率：同输入重复 N 次后，<b>每道题</b>自己是稳定通过、稳定失败，还是时过时不过。
 * <p>
 * {@code StabilityReport} 回答的是「总体分数稳不稳」，但那会被平均掩盖：
 * 20 道题反复翻面、130 道题纹丝不动，总体标准差可以几乎不变。翻面的题才是问题——
 * 要么题目对模型输出敏感（期望词选得太窄），要么被测本身不确定。
 * 只有把抖动落到具体题号上，才知道下一轮该修题还是该修被测。
 */
public final class Consistency {

    public enum Pattern {
        /** 每次都过：稳定 */
        STABLE_PASS("每次都过"),
        /** 每次都败（或全不可判）：稳定地不会 */
        STABLE_FAIL("每次都败"),
        /** 有过也有败：这题在翻面 */
        FLAKY("时过时不过"),
        /** 没有任何一次产生判定：本轮无证据 */
        ALL_UNKNOWN("全不可判");

        private final String label;

        Pattern(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param caseId 用例 ID
     * @param runs   参与统计的运行次数
     * @param judged 已判定次数
     * @param passes 通过次数
     */
    public record Item(String caseId, int runs, int judged, int passes, Pattern pattern) {
    }

    private final List<Item> items;

    private Consistency(List<Item> items) {
        this.items = items;
    }

    /** @param reports 同一被测、同一题集、重复 N 次的报告，按用例 ID 对齐 */
    public static Consistency of(List<BenchmarkReport> reports) {
        Map<String, int[]> byCase = new LinkedHashMap<>();   // [runs, judged, passes]
        for (BenchmarkReport run : reports) {
            for (Judgment judgment : run.judgments()) {
                int[] cell = byCase.computeIfAbsent(judgment.caseId(), k -> new int[3]);
                cell[0]++;
                if (judgment.judged()) {
                    cell[1]++;
                    if (judgment.passed()) {
                        cell[2]++;
                    }
                }
            }
        }
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : byCase.entrySet()) {
            int runs = entry.getValue()[0];
            int judged = entry.getValue()[1];
            int passes = entry.getValue()[2];
            Pattern pattern;
            if (judged == 0) {
                pattern = Pattern.ALL_UNKNOWN;
            } else if (passes == judged) {
                pattern = Pattern.STABLE_PASS;
            } else if (passes == 0) {
                pattern = Pattern.STABLE_FAIL;
            } else {
                pattern = Pattern.FLAKY;
            }
            items.add(new Item(entry.getKey(), runs, judged, passes, pattern));
        }
        return new Consistency(items);
    }

    public List<Item> items() {
        return List.copyOf(items);
    }

    public List<Item> flaky() {
        return items.stream().filter(i -> i.pattern() == Pattern.FLAKY).toList();
    }

    /** 同题一致率：稳定（每次都过或每次都败）的题占比 */
    public double agreementRate() {
        if (items.isEmpty()) {
            return 0;
        }
        long stable = items.stream()
                .filter(i -> i.pattern() == Pattern.STABLE_PASS || i.pattern() == Pattern.STABLE_FAIL)
                .count();
        return (double) stable / items.size();
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 逐题一致率（重复运行） ==========\n");
        if (items.isEmpty() || items.get(0).runs() < 2) {
            sb.append("需要 --repeat >= 2 才能统计逐题一致率，本轮跳过。\n");
            sb.append("============================================\n");
            return sb.toString();
        }
        sb.append(String.format("题数: %d ｜ 一致率(稳定题占比): %.1f%%%n",
                items.size(), agreementRate() * 100));
        List<Item> flaky = flaky();
        if (flaky.isEmpty()) {
            sb.append("没有翻面的题：每道题在重复运行里结论一致。\n");
        } else {
            sb.append("翻面的题（").append(flaky.size()).append(" 道）——先判断是题敏感还是被测不确定:\n");
            for (Item item : flaky) {
                sb.append(String.format("  - %-10s 过 %d/%d 次%n", item.caseId(), item.passes(), item.judged()));
            }
        }
        sb.append("============================================\n");
        return sb.toString();
    }
}
