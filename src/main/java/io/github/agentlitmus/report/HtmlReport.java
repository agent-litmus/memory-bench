package io.github.agentlitmus.report;
import io.github.agentlitmus.core.Judgment;
import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.FailureCause;
import io.github.agentlitmus.dataset.CoverageReport;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.AgentRole;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTML 报告生成器（自包含、零依赖）。
 * <p>
 * 把雷达图（内嵌 SVG）、横向对比表、各智能体明细、失败归因与改进建议、
 * 稳定性证据、数据集覆盖度整合成单个 HTML 文件，浏览器直接打开即可查看——
 * 不需要任何前端依赖，适合在 openKylin 桌面环境录屏演示。
 */
public final class HtmlReport {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private HtmlReport() {
    }

    /** 该被测对象的置信层级与生效能力——分数旁边必须写清它凭什么可信 */
    private static String tierCell(AgentUnderTest agent) {
        var tier = agent.answerer().confidenceTier();
        String caps = agent.answerer().capabilities().stream()
                .map(c -> c.label()).sorted().reduce((a, b) -> a + "、" + b).orElse("仅对话");
        return "<b>" + tier.name() + "</b> <span class=\"tag\">" + escape(tier.label())
                + "</span><div class=\"sub\">" + escape(caps) + "</div>";
    }

    /** 兼容旧调用：无稳定性/覆盖度信息 */
    public static String html(BenchmarkResult result) {
        return html(result, null, null, null);
    }

    /** 兼容旧调用：无记忆增量信息 */
    public static String html(BenchmarkResult result, StabilityReport stability, CoverageReport coverage) {
        return html(result, stability, coverage, null);
    }

    /**
     * 生成完整 HTML 报告。
     *
     * @param stability 稳定性证据（同输入重复评测），可为 null
     * @param coverage  数据集覆盖度校验，可为 null
     * @param delta     记忆增量 Δ（同题双臂），可为 null；已含说明文字
     */
    public static String html(BenchmarkResult result, StabilityReport stability, CoverageReport coverage,
                              String delta) {
        return html(result, stability, coverage, delta, null);
    }

    /**
     * @param oracle 神谕对照结论（可为 null）：失败里多少是记忆成因、多少不是
     */
    public static String html(BenchmarkResult result, StabilityReport stability, CoverageReport coverage,
                              String delta, String oracle) {
        return html(result, stability, coverage, delta, oracle, null);
    }

    /**
     * @param attribution 归因证据化结论（可为 null）：报告里的归因有多少条由独立证据支撑，
     *                    有多少条只是从回答文本推断——见 {@code AttributionEvidence}
     */
    public static String html(BenchmarkResult result, StabilityReport stability, CoverageReport coverage,
                              String delta, String oracle, String attribution) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <title>AgentLitmus 长期记忆评测报告</title>
                <style>
                  body { font-family: -apple-system, "Segoe UI", "Noto Sans CJK SC", sans-serif;
                         margin: 32px; color: #222; background: #fafafa; }
                  h1 { font-size: 24px; margin-bottom: 4px; }
                  .sub { color: #666; font-size: 14px; margin-bottom: 24px; }
                  .card { background: #fff; border: 1px solid #e0e0e0; border-radius: 8px;
                          padding: 20px; margin-bottom: 20px; }
                  h2 { font-size: 18px; margin-top: 0; }
                  h3 { font-size: 15px; margin: 16px 0 8px; color: #333; }
                  table { border-collapse: collapse; width: 100%; font-size: 14px; }
                  th, td { border: 1px solid #e0e0e0; padding: 8px 10px; text-align: left; }
                  th { background: #f0f0f0; }
                  td.num { text-align: right; }
                  .pass { color: #2E7D32; font-weight: bold; }
                  .fail { color: #C62828; font-weight: bold; }
                  .unknown { color: #ef6c00; font-weight: bold; }
                  .tag { display: inline-block; font-size: 12px; padding: 1px 8px; border-radius: 10px;
                         background: #eee; color: #555; margin-left: 8px; }
                  .tag.extra { background: #fff3e0; color: #ef6c00; }
                  pre { background: #f5f5f5; padding: 12px; border-radius: 6px;
                        overflow-x: auto; font-size: 13px; }
                </style>
                </head>
                <body>
                """);

        int primaryCount = result.primary().size();
        int extraCount = result.extras().size();
        sb.append("<h1>AgentLitmus · 长期记忆评测报告</h1>");
        sb.append("<div class=\"sub\">生成时间：").append(LocalDateTime.now().format(FMT))
                .append(" ｜ 主对比智能体：").append(primaryCount).append(" 款 ｜ 附加案例：").append(extraCount).append(" 款</div>");

        // 雷达图（仅主对比）
        sb.append("<div class=\"card\"><h2>六维能力对比（主对比）</h2>");
        sb.append(RadarChart.svg(primaryRatesOf(result)));
        sb.append("</div>");

        // 对比表（仅主对比）
        sb.append("<div class=\"card\"><h2>横向对比（主对比）</h2>");
        sb.append("<div class=\"sub\">表内为<b>有效分（通过 / 已判定）</b>；不可判（被测无响应或评委故障）"
                + "意味着判定并未发生，不计入分母，改由覆盖率与悲观下界两个数说明证据强度。</div><table>");
        sb.append("<tr><th>智能体</th>");
        for (Dimension dimension : Dimension.values()) {
            sb.append("<th>").append(dimension.label()).append("</th>");
        }
        sb.append("<th>总体</th><th>覆盖率</th><th>不可判</th><th>悲观下界</th><th>置信层级</th></tr>");
        for (AgentUnderTest agent : result.primary()) {
            BenchmarkReport report = result.reportOf(agent);
            sb.append("<tr><td>").append(escape(agent.name())).append("</td>");
            for (Dimension dimension : Dimension.values()) {
                double rate = report.byDimension().get(dimension).rate();
                sb.append("<td class=\"num\">").append(pct(rate)).append("</td>");
            }
            sb.append("<td class=\"num\"><b>").append(pct(report.passRate())).append("</b></td>");
            sb.append("<td class=\"num\">").append(pct(report.coverage())).append("</td>");
            sb.append("<td class=\"num\">").append(report.unknownCount()).append("</td>");
            sb.append("<td class=\"num\">").append(pct(report.lowerBound())).append("</td>");
            sb.append("<td>").append(tierCell(agent)).append("</td></tr>");
        }
        sb.append("</table>");
        sb.append("<div class=\"sub\">置信层级：<b>T1</b> 仅对话证据（失败归因靠用例标记推断）／"
                + "<b>T2</b> 记忆状态可观测（能区分「没写进去」与「写进去没答出来」）／"
                + "<b>T3</b> 行动或产物可观测（能定位「记住了但没用」）。"
                + "同一列的分数含义不同，跨行比较前先比较层级。</div></div>");

        if (oracle != null && !oracle.isBlank()) {
            sb.append("<div class=\"card\"><h2>神谕对照（失败用例附正确答案重问）</h2>")
                    .append("<div class=\"sub\">附上正确事实仍答错的用例<b>改判不可判</b>并标注「基座/执行」："
                            + "那不是记忆缺陷，混进分母会把模型能力问题记成记忆模块的账。</div>")
                    .append("<pre>").append(escape(oracle)).append("</pre></div>");
        }

        if (attribution != null && !attribution.isBlank()) {
            sb.append("<div class=\"card\"><h2>归因证据化（归因有多少条由独立证据支撑）</h2>")
                    .append("<div class=\"sub\">三个口径管的是<b>分数</b>可不可信，这一节管的是<b>结论</b>可不可信。"
                            + "记忆可读时「旧值仍在库里」是观测事实；记忆在被测对象内部读不到时，"
                            + "同一句归因只是从回答文本做的推断。<b>未证据化的归因引用时需注明是推断。</b>"
                            + "完整清单见 <code>attribution.json</code>。</div>")
                    .append("<pre>").append(escape(attribution)).append("</pre></div>");
        }

        if (delta != null && !delta.isBlank()) {
            sb.append("<div class=\"card\"><h2>记忆增量 Δ（同题双臂）</h2>")
                    .append("<pre>").append(escape(delta)).append("</pre></div>");
        }

        // 各主对比智能体明细
        for (AgentUnderTest agent : result.primary()) {
            sb.append(agentCard(result, agent, false));
        }

        // 附加案例（跨领域垂直 agent）：单独成区，不计入主对比
        if (!result.extras().isEmpty()) {
            sb.append("<div class=\"card\"><h2>附加案例（跨领域鲁棒性边界）</h2>");
            sb.append("<div class=\"sub\">以下智能体为垂直领域 agent，与通用个人助理记忆场景不匹配，"
                    + "作为「尺子能否区分垂直 agent 与通用 agent」的鲁棒性证据单独展示，不计入主对比评分。</div>");
            for (AgentUnderTest agent : result.extras()) {
                sb.append(agentCard(result, agent, true));
            }
            sb.append("</div>");
        }

        // 失败归因与改进建议（可行动诊断）：覆盖全部智能体
        sb.append("<div class=\"card\"><h2>失败归因与改进建议（可行动诊断）</h2>");
        sb.append("<div class=\"sub\">在五类结果之上叠加记忆生命周期阶段归因，并为每条归因给出可行动的改进建议——"
                + "把评测从「分类」升级为「诊断」，对应命题自动评分能力（25%）强调的「可解释评分原因」。</div>");
        for (AgentUnderTest agent : result.ranked()) {
            BenchmarkReport report = result.reportOf(agent);
            sb.append("<h3>").append(escape(agent.name())).append("</h3>");
            final boolean[] any = {false};
            report.causeCounts().forEach((cause, count) -> {
                if (count > 0) {
                    any[0] = true;
                    sb.append("<p><b>").append(escape(cause.label())).append("</b> ×").append(count)
                            .append(" <span class=\"sub\">（阶段：").append(escape(cause.stage())).append("）</span><br>")
                            .append("诊断：").append(escape(cause.description())).append("<br>")
                            .append("<b>改进建议：</b>").append(escape(cause.remediation())).append("</p>");
                }
            });
            if (!any[0]) {
                sb.append("<p class=\"sub\">无失败归因（全部通过或仅正确记忆）。</p>");
            }
        }
        sb.append("</div>");

        // 稳定性证据
        if (stability != null) {
            sb.append("<div class=\"card\"><h2>稳定性证据（同输入重复评测）</h2>");
            sb.append("<div class=\"sub\">命题「稳定性与可复现性」（15%）明确看同输入下结果是否稳定。"
                    + "下表量化各智能体的抖动（max-min）与总体标准差 σ，数值越小越稳定。</div>");
            sb.append("<table><tr><th>智能体</th><th>总体均值</th><th>抖动</th><th>σ</th></tr>");
            stability.overall().forEach((agent, s) -> sb.append("<tr><td>").append(escape(agent.name())).append("</td>")
                    .append("<td class=\"num\">").append(pct(s.mean())).append("</td>")
                    .append("<td class=\"num\">").append(pct(s.jitter())).append("</td>")
                    .append("<td class=\"num\">").append(pct(s.std())).append("</td></tr>"));
            sb.append("</table></div>");
        }

        // 数据集覆盖度校验
        if (coverage != null) {
            sb.append("<div class=\"card\"><h2>数据集覆盖度校验</h2>");
            sb.append("<div class=\"sub\">命题「数据设计质量」（25%）要求样本具备区分度、覆盖面和扩展空间。"
                    + "下表给出每维度用例数、正/负样本与区分度。</div>");
            sb.append(coverage.valid() ? "<p>合法性：通过（无重复 ID、无无约束用例）</p>"
                    : "<p>合法性：发现问题 " + coverage.issues().size() + " 项</p>");
            sb.append("<table><tr><th>维度</th><th>用例数</th><th>命中样本</th><th>排除样本</th><th>区分度</th></tr>");
            coverage.perDimension().forEach((d, c) -> {
                int pos = coverage.positiveSamples().get(d);
                int neg = coverage.negativeSamples().get(d);
                boolean discrim = pos > 0 && neg > 0;
                sb.append("<tr><td>").append(d.label()).append("</td>")
                        .append("<td class=\"num\">").append(c).append("</td>")
                        .append("<td class=\"num\">").append(pos).append("</td>")
                        .append("<td class=\"num\">").append(neg).append("</td>")
                        .append("<td>").append(discrim ? "✓" : "不足").append("</td></tr>");
            });
            sb.append("</table>");
            sb.append("<p class=\"sub\">跨会话用例 ").append(coverage.crossSessionCount()).append(" 条；")
                    .append("更新×边界交叉用例 ").append(coverage.hasUpdateBoundaryCross() ? "已覆盖" : "缺失（建议增补）")
                    .append("</p></div>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    /** 仅主对比智能体的六维通过率，供雷达图使用 */
    private static Map<String, Map<Dimension, Double>> primaryRatesOf(BenchmarkResult result) {
        Map<String, Map<Dimension, Double>> rates = new LinkedHashMap<>();
        for (AgentUnderTest agent : result.primary()) {
            rates.put(agent.name(), RadarChart.ratesOf(result.reportOf(agent)));
        }
        return rates;
    }

    /** 渲染单个智能体的卡片（含结果分类、归因、逐条明细） */
    private static String agentCard(BenchmarkResult result, AgentUnderTest agent, boolean extra) {
        BenchmarkReport report = result.reportOf(agent);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"card\"><h2>").append(escape(agent.name()));
        if (extra) {
            sb.append("<span class=\"tag extra\">附加案例</span>");
        }
        sb.append("</h2>");
        if (!agent.description().isEmpty()) {
            sb.append("<div class=\"sub\">").append(escape(agent.description())).append("</div>");
        }
        sb.append("<p>用例总数 <b>").append(report.total())
                .append("</b> ｜ 已判定 <b>").append(report.judgedCount())
                .append("</b> ｜ 通过 <b>").append(report.passedCount())
                .append("</b> ｜ 不可判 <b>").append(report.unknownCount())
                .append("</b> ｜ 有效分 <b>").append(pct(report.passRate()))
                .append("</b> ｜ 覆盖率 <b>").append(pct(report.coverage()))
                .append("</b> ｜ 悲观下界 <b>").append(pct(report.lowerBound())).append("</b></p>");
        sb.append("<p><b>结果分类：</b>");
        report.outcomeCounts().forEach((outcome, count) -> {
            if (count > 0) {
                sb.append(escape(outcome.label())).append(" <b>").append(count).append("</b>　");
            }
        });
        sb.append("</p>");
        sb.append("<table><tr><th>用例</th><th>维度</th><th>结果</th><th>类别</th><th>归因</th><th>判定理由</th></tr>");
        sb.append(detailsOf(report));
        sb.append("</table></div>");
        return sb.toString();
    }

    /** 渲染单个智能体的逐条用例明细 */
    private static String detailsOf(BenchmarkReport report) {
        StringBuilder sb = new StringBuilder();
        for (Judgment judgment : report.judgments()) {
            sb.append("<tr>");
            sb.append("<td>").append(escape(judgment.caseId())).append("</td>");
            sb.append("<td>").append(judgment.dimension().label()).append("</td>");
            sb.append("<td class=\"").append(cssClass(judgment.verdict())).append("\">")
                    .append(judgment.verdict().label()).append("</td>");
            sb.append("<td>").append(judgment.outcome() == null ? "—" : judgment.outcome().label()).append("</td>");
            sb.append("<td>").append(judgment.cause() == null ? "—" : escape(judgment.cause().label())).append("</td>");
            sb.append("<td>").append(escape(judgment.reason())).append("</td>");
            sb.append("</tr>");
        }
        return sb.toString();
    }

    private static String cssClass(io.github.agentlitmus.core.Verdict verdict) {
        return switch (verdict) {
            case PASS -> "pass";
            case FAIL -> "fail";
            case UNKNOWN -> "unknown";
        };
    }

    private static String pct(double rate) {
        // Locale.ROOT：小数点不被本地化，保证同一份输入在任何环境下渲染出相同字节（golden 测试的前提）
        return String.format(java.util.Locale.ROOT, "%.0f%%", rate * 100);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
