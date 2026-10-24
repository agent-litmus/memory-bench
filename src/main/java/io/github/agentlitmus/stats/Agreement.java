package io.github.agentlitmus.stats;

import io.github.agentlitmus.core.Verdict;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 判定器与人工标注的一致性统计：一致率 + Cohen's κ + 混淆矩阵。
 * <p>
 * 三值标签下只看「一致率」会被两类完全不同的错误糊在一起：
 * <ul>
 *   <li><b>判错</b>——规则说通过、人工说未过（或反之）。这是尺子刻度不准，最危险，
 *       因为它会把被测对象的正确行为记成缺陷、或把缺陷放过去；</li>
 *   <li><b>弃权</b>——规则说不可判、人工给了确定标签。这不产生错误结论，只损失覆盖率，
 *       代价是「该测的没测到」。</li>
 * </ul>
 * 所以这里同时报三个数：κ（整体一致性，弃权算不一致）、<b>开口即对率</b>（规则给出确定判定时
 * 与人工一致的比例）、<b>弃权率</b>。只报 κ 会掩盖「尺子其实很准但常常不敢下结论」，
 * 只报开口即对率会掩盖「尺子把一半题目都推给评委」。
 * <p>
 * κ 用多类公式 {@code (p_o - p_e) / (1 - p_e)}，其中 {@code p_e} 按行列边际估计。
 * 当某一侧只输出单一类别时 κ 无定义（分母为 0），此时返回 {@link #KAPPA_UNDEFINED} 而不是 0——
 * 「无法计算」和「完全不一致」是两件事，混为一谈会造出一个不存在的缺陷。
 */
public final class Agreement {

    /** κ 无定义（分母为 0，通常是一侧只输出了单一类别） */
    public static final double KAPPA_UNDEFINED = Double.NaN;

    /**
     * 一条对照记录。
     *
     * @param key       可定位的标识（用例 ID + 被测对象），用于把分歧列回人读报告
     * @param predicted 判定器给出的三值结论
     * @param gold      人工标注
     * @param detail    判定理由，分歧时用来判断是尺子错还是标注错
     */
    public record Item(String key, Verdict predicted, Verdict gold, String detail) {
    }

    /** 一条分歧。{@code kind} 说明它属于哪一类错误 */
    public record Disagreement(String key, Verdict predicted, Verdict gold, String kind, String detail) {
    }

    /**
     * 一致性结果。
     *
     * @param total           对照条数
     * @param agreed          标签完全一致的条数
     * @param agreement       一致率 = agreed / total
     * @param kappa           Cohen's κ（多类）；无定义时为 {@link #KAPPA_UNDEFINED}
     * @param spoken          判定器给出确定结论（非弃权）的条数
     * @param spokenCorrect   给出确定结论且与人工一致（人工也给了同一个确定结论）的条数
     * @param spokenRate      开口即对率 = spokenCorrect / spoken；spoken 为 0 时无定义
     * @param abstained       判定器弃权的条数
     * @param abstainRate     弃权率 = abstained / total
     * @param blindSpots      判定器开口、人工却认为「判定不应发生」的条数（例如把排队提示当成回答）
     * @param disagreements   全部分歧，按类别列出
     */
    public record Result(int total,
                         int agreed,
                         double agreement,
                         double kappa,
                         int spoken,
                         int spokenCorrect,
                         double spokenRate,
                         int abstained,
                         double abstainRate,
                         int blindSpots,
                         List<Disagreement> disagreements) {

        public boolean kappaDefined() {
            return !Double.isNaN(kappa);
        }

        public String toText(String rulerName) {
            StringBuilder sb = new StringBuilder();
            sb.append("── 尺子自证: ").append(rulerName).append(" ──\n");
            sb.append(String.format(Locale.ROOT, "对照条数        : %d%n", total));
            sb.append(String.format(Locale.ROOT, "整体一致率      : %.1f%%（%d/%d）%n", agreement * 100, agreed, total));
            sb.append(kappaDefined()
                    ? String.format(Locale.ROOT, "Cohen's κ       : %.3f（%s）%n", kappa, kappaBand(kappa))
                    : "Cohen's κ       : 无定义（一侧只输出单一类别，此时 κ 的分母为 0）\n");
            sb.append(spoken == 0
                    ? "开口即对率      : 无定义（判定器在这批样本上没有给出任何确定结论）\n"
                    : String.format(Locale.ROOT, "开口即对率      : %.1f%%（%d/%d）%n",
                        spokenRate * 100, spokenCorrect, spoken));
            sb.append(String.format(Locale.ROOT, "弃权率          : %.1f%%（%d/%d）——弃权不产生错误结论，只损失覆盖率%n",
                    abstainRate * 100, abstained, total));
            if (blindSpots > 0) {
                sb.append("已知盲点        : ").append(blindSpots)
                        .append(" 条把「根本没有回答」当成了有效回答（判定器开口、人工判不可判）\n");
            }
            if (!disagreements.isEmpty()) {
                sb.append("分歧明细（").append(disagreements.size()).append(" 条）:\n");
                for (Disagreement d : disagreements) {
                    sb.append("  - [").append(d.kind()).append("] ").append(d.key())
                            .append("  判定器=").append(d.predicted().label())
                            .append("  人工=").append(d.gold().label()).append('\n');
                    if (d.detail() != null && !d.detail().isBlank()) {
                        sb.append("      理由: ").append(oneLine(d.detail())).append('\n');
                    }
                }
            }
            return sb.toString();
        }
    }

    private Agreement() {
    }

    public static Result of(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("一致性统计至少需要一条对照");
        }
        int total = items.size();
        int agreed = 0;
        int spoken = 0;
        int spokenCorrect = 0;
        int abstained = 0;
        int blindSpots = 0;
        List<Disagreement> disagreements = new ArrayList<>();
        Map<Verdict, Map<Verdict, Integer>> matrix = new EnumMap<>(Verdict.class);
        for (Verdict p : Verdict.values()) {
            Map<Verdict, Integer> row = new EnumMap<>(Verdict.class);
            for (Verdict g : Verdict.values()) {
                row.put(g, 0);
            }
            matrix.put(p, row);
        }

        for (Item item : items) {
            Verdict p = item.predicted();
            Verdict g = item.gold();
            matrix.get(p).merge(g, 1, Integer::sum);
            boolean same = p == g;
            if (same) {
                agreed++;
            }
            if (p.judged()) {
                spoken++;
                if (g.judged()) {
                    if (same) {
                        spokenCorrect++;
                    } else {
                        disagreements.add(new Disagreement(item.key(), p, g,
                                p == Verdict.PASS ? "假阳(判错)" : "假阴(判错)", item.detail()));
                    }
                } else {
                    blindSpots++;
                    disagreements.add(new Disagreement(item.key(), p, g, "盲点(该弃权却开口)", item.detail()));
                }
            } else {
                abstained++;
                if (!same) {
                    disagreements.add(new Disagreement(item.key(), p, g, "弃权(该判未判)", item.detail()));
                }
            }
        }

        double po = (double) agreed / total;
        double pe = 0;
        for (Verdict v : Verdict.values()) {
            int row = 0;
            int col = 0;
            for (Verdict u : Verdict.values()) {
                row += matrix.get(v).get(u);
                col += matrix.get(u).get(v);
            }
            pe += ((double) row / total) * ((double) col / total);
        }
        double kappa = Math.abs(1 - pe) < 1e-12 ? KAPPA_UNDEFINED : (po - pe) / (1 - pe);
        double spokenRate = spoken == 0 ? Double.NaN : (double) spokenCorrect / spoken;

        return new Result(total, agreed, po, kappa, spoken, spokenCorrect, spokenRate,
                abstained, (double) abstained / total, blindSpots, List.copyOf(disagreements));
    }

    /** 混淆矩阵（行=判定器，列=人工标注），写进 selftest.json 供复核 */
    public static Map<String, Map<String, Integer>> matrixOf(List<Item> items) {
        Map<String, Map<String, Integer>> out = new java.util.LinkedHashMap<>();
        for (Verdict p : Verdict.values()) {
            Map<String, Integer> row = new java.util.LinkedHashMap<>();
            for (Verdict g : Verdict.values()) {
                row.put(g.name(), 0);
            }
            out.put(p.name(), row);
        }
        for (Item item : items) {
            out.get(item.predicted().name()).merge(item.gold().name(), 1, Integer::sum);
        }
        return out;
    }

    /** Landis & Koch 的经验分档，只为给人一个「0.63 算什么水平」的锚，不是标准 */
    public static String kappaBand(double kappa) {
        if (Double.isNaN(kappa)) {
            return "无定义";
        }
        if (kappa < 0) {
            return "比随机还差";
        }
        if (kappa < 0.20) {
            return "极弱";
        }
        if (kappa < 0.40) {
            return "弱";
        }
        if (kappa < 0.60) {
            return "中等";
        }
        if (kappa < 0.80) {
            return "较强";
        }
        return "很强";
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
