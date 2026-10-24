package io.github.agentlitmus.stats;

import io.github.agentlitmus.core.Judgment;
import io.github.agentlitmus.core.Verdict;
import io.github.agentlitmus.report.BenchmarkReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 同题配对比较 + bootstrap 置信区间：把「A 比 B 高 3 个点」变成一句有统计含义的话。
 * <p>
 * 只有绝对通过率时，两个 61% 与 58% 无法判优——150 道题的抽样误差本来就有好几个点。
 * 本类做三件事：
 * <ol>
 *   <li><b>配对</b>：只在<b>两边都已判定</b>的同一批题上比较，避免「A 有响应的题正好更难」这种假优势；</li>
 *   <li><b>bootstrap</b>：对配对差值重采样 B 次取 95% 分位区间。固定随机种子，
 *       所以同输入必然同输出——第三方能逐字节复现这个区间；</li>
 *   <li><b>符号检验</b>作为不依赖分布的旁证：只数「A 独对」与「B 独对」的题数。</li>
 * </ol>
 * 区间跨 0 时结论写「未见显著差异」，<b>不写成平手</b>，也不许把小数点后的差值当优势。
 */
public final class PairedCompare {

    /** 重采样次数：1000 足够稳定，又不至于让 CLI 变慢 */
    public static final int BOOTSTRAP_SAMPLES = 1000;
    /** 固定种子：可复现性优先于「随机得更像随机」 */
    public static final long SEED = 20261023L;

    public record Result(int casesA, int casesB, int pairs, double rateA, double rateB,
                         double delta, double ciLow, double ciHigh,
                         int aOnlyWins, int bOnlyWins, boolean significant) {

        /** 结论一句话：跨 0 就照实说「未见显著差异」 */
        public String conclusion() {
            if (pairs == 0) {
                return "两侧没有共同已判定的题，无法比较";
            }
            if (significant) {
                return delta > 0 ? "A 显著优于 B（95% 区间不含 0）" : "B 显著优于 A（95% 区间不含 0）";
            }
            return "未见显著差异（95% 区间跨 0；点估计差值不足以判优）";
        }
    }

    private PairedCompare() {
    }

    /**
     * @param a 报告 A（下称 A）
     * @param b 报告 B（下称 B）
     */
    public static Result of(BenchmarkReport a, BenchmarkReport b) {
        Map<String, Boolean> mapA = judged(a);
        Map<String, Boolean> mapB = judged(b);

        List<Double> diffs = new ArrayList<>();
        int aOnly = 0;
        int bOnly = 0;
        for (Map.Entry<String, Boolean> entry : mapA.entrySet()) {
            Boolean other = mapB.get(entry.getKey());
            if (other == null) {
                continue;
            }
            double x = entry.getValue() ? 1 : 0;
            double y = other ? 1 : 0;
            diffs.add(x - y);
            if (x > y) {
                aOnly++;
            } else if (y > x) {
                bOnly++;
            }
        }
        return build(mapA.size(), mapB.size(), rate(mapA), rate(mapB), diffs, aOnly, bOnly);
    }

    /**
     * 同一件事的另一种输入：两次<b>已经跑完</b>的运行各自的逐题三态裁决（{@code compare} 用）。
     * <p>
     * 只配对<b>两侧都已判定</b>的题；一侧不可判则该题退出配对，不算失败也不算通过。
     * 因此「不可判条数变多」不会伪装成能力下降——它会以覆盖率变化的形式另行公示。
     */
    public static Result ofVerdicts(Map<String, Verdict> a, Map<String, Verdict> b) {
        List<Double> diffs = new ArrayList<>();
        int aOnly = 0;
        int bOnly = 0;
        for (Map.Entry<String, Verdict> entry : a.entrySet()) {
            Verdict other = b.get(entry.getKey());
            if (!entry.getValue().judged() || other == null || !other.judged()) {
                continue;
            }
            double x = entry.getValue() == Verdict.PASS ? 1 : 0;
            double y = other == Verdict.PASS ? 1 : 0;
            diffs.add(x - y);
            if (x > y) {
                aOnly++;
            } else if (y > x) {
                bOnly++;
            }
        }
        return build(judgedCount(a), judgedCount(b), passRate(a), passRate(b), diffs, aOnly, bOnly);
    }

    private static Result build(int casesA, int casesB, double rateA, double rateB,
                                List<Double> diffs, int aOnly, int bOnly) {
        double delta = diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double[] ci = bootstrapCI(diffs);
        boolean significant = ci[0] > 0 || ci[1] < 0;
        return new Result(casesA, casesB, diffs.size(), rateA, rateB, delta, ci[0], ci[1],
                aOnly, bOnly, significant);
    }

    private static int judgedCount(Map<String, Verdict> map) {
        return (int) map.values().stream().filter(Verdict::judged).count();
    }

    private static double passRate(Map<String, Verdict> map) {
        int judged = judgedCount(map);
        if (judged == 0) {
            return 0;
        }
        return map.values().stream().filter(v -> v == Verdict.PASS).count() / (double) judged;
    }

    private static Map<String, Boolean> judged(BenchmarkReport report) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (Judgment judgment : report.judgments()) {
            if (judgment.judged()) {
                map.put(judgment.caseId(), judgment.passed());
            }
        }
        return map;
    }

    private static double rate(Map<String, Boolean> map) {
        if (map.isEmpty()) {
            return 0;
        }
        return map.values().stream().filter(Boolean::booleanValue).count() / (double) map.size();
    }

    /** 配对差值的 95% 百分位 bootstrap 区间；空样本返回 [0,0] */
    static double[] bootstrapCI(List<Double> diffs) {
        int n = diffs.size();
        if (n == 0) {
            return new double[]{0, 0};
        }
        Random random = new Random(SEED);
        double[] means = new double[BOOTSTRAP_SAMPLES];
        for (int i = 0; i < BOOTSTRAP_SAMPLES; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) {
                sum += diffs.get(random.nextInt(n));
            }
            means[i] = sum / n;
        }
        java.util.Arrays.sort(means);
        return new double[]{percentile(means, 2.5), percentile(means, 97.5)};
    }

    private static double percentile(double[] sorted, double p) {
        double index = (p / 100.0) * (sorted.length - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sorted[lower];
        }
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower);
    }

    public static String toText(String nameA, String nameB, Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 同题配对比较 ==========\n");
        sb.append(String.format("%s: %.1f%%（已判定 %d 题）%n", nameA, result.rateA() * 100, result.casesA()));
        sb.append(String.format("%s: %.1f%%（已判定 %d 题）%n", nameB, result.rateB() * 100, result.casesB()));
        sb.append(String.format("共同已判定题数: %d%n", result.pairs()));
        sb.append(String.format("差值(A−B): %.1f 个百分点  95%%CI [%.1f, %.1f]（bootstrap B=%d, seed=%d）%n",
                result.delta() * 100, result.ciLow() * 100, result.ciHigh() * 100,
                BOOTSTRAP_SAMPLES, SEED));
        sb.append(String.format("符号检验: A 独对 %d 题 / B 独对 %d 题%n", result.aOnlyWins(), result.bOnlyWins()));
        sb.append("结论: ").append(result.conclusion()).append('\n');
        sb.append("说明：只在两侧都已判定的题上比较；不可判的题不参与，也不得被算作失败。\n");
        sb.append("====================================\n");
        return sb.toString();
    }
}
