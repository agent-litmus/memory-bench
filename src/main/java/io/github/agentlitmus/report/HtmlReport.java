package io.github.zaojiaoci.agentlitmus.report;
import io.github.zaojiaoci.agentlitmus.core.Judgment;
import io.github.zaojiaoci.agentlitmus.core.Dimension;
import io.github.zaojiaoci.agentlitmus.agent.AgentUnderTest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * HTML 报告生成器（自包含、零依赖）。
 * <p>
 * 把雷达图（内嵌 SVG）、横向对比表、各智能体明细整合成单个 HTML 文件，
 * 浏览器直接打开即可查看——不需要任何前端依赖，适合在 openKylin 桌面环境录屏演示。
 */
public final class HtmlReport {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private HtmlReport() {
    }

    public static String html(BenchmarkResult result) {
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
                  table { border-collapse: collapse; width: 100%; font-size: 14px; }
                  th, td { border: 1px solid #e0e0e0; padding: 8px 10px; text-align: left; }
                  th { background: #f0f0f0; }
                  td.num { text-align: right; }
                  .pass { color: #2E7D32; font-weight: bold; }
                  .fail { color: #C62828; font-weight: bold; }
                  pre { background: #f5f5f5; padding: 12px; border-radius: 6px;
                        overflow-x: auto; font-size: 13px; }
                </style>
                </head>
                <body>
                """);

        sb.append("<h1>AgentLitmus · 长期记忆评测报告</h1>");
        sb.append("<div class=\"sub\">生成时间：").append(LocalDateTime.now().format(FMT))
                .append(" ｜ 被测智能体：").append(result.reports().size()).append(" 款</div>");

        // 雷达图
        sb.append("<div class=\"card\"><h2>六维能力对比</h2>");
        sb.append(RadarChart.svg(result.dimensionRates()));
        sb.append("</div>");

        // 对比表
        sb.append("<div class=\"card\"><h2>横向对比</h2><table>");
        sb.append("<tr><th>智能体</th>");
        for (Dimension dimension : Dimension.values()) {
            sb.append("<th>").append(dimension.label()).append("</th>");
        }
        sb.append("<th>总体</th></tr>");
        List<AgentUnderTest> ranked = result.ranked();
        for (AgentUnderTest agent : ranked) {
            BenchmarkReport report = result.reportOf(agent);
            sb.append("<tr><td>").append(escape(agent.name())).append("</td>");
            for (Dimension dimension : Dimension.values()) {
                double rate = report.byDimension().get(dimension).rate();
                sb.append("<td class=\"num\">").append(pct(rate)).append("</td>");
            }
            sb.append("<td class=\"num\"><b>").append(pct(report.passRate())).append("</b></td></tr>");
        }
        sb.append("</table></div>");

        // 各智能体明细
        for (AgentUnderTest agent : ranked) {
            BenchmarkReport report = result.reportOf(agent);
            sb.append("<div class=\"card\"><h2>").append(escape(agent.name())).append("</h2>");
            if (!agent.description().isEmpty()) {
                sb.append("<div class=\"sub\">").append(escape(agent.description())).append("</div>");
            }
            sb.append("<p>用例总数 <b>").append(report.total())
                    .append("</b> ｜ 通过 <b>").append(report.passedCount())
                    .append("</b> ｜ 通过率 <b>").append(pct(report.passRate())).append("</b></p>");
            sb.append("<p><b>结果分类：</b>");
            report.outcomeCounts().forEach((outcome, count) -> {
                if (count > 0) {
                    sb.append(escape(outcome.label())).append(" <b>").append(count).append("</b>　");
                }
            });
            sb.append("</p>");
            sb.append("<table><tr><th>用例</th><th>维度</th><th>结果</th><th>类别</th><th>判定理由</th></tr>");
            sb.append(detailsOf(report));
            sb.append("</table></div>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    /** 渲染单个智能体的逐条用例明细 */
    private static String detailsOf(BenchmarkReport report) {
        StringBuilder sb = new StringBuilder();
        for (Judgment judgment : report.judgments()) {
            sb.append("<tr>");
            sb.append("<td>").append(escape(judgment.caseId())).append("</td>");
            sb.append("<td>").append(judgment.dimension().label()).append("</td>");
            sb.append("<td class=\"").append(judgment.passed() ? "pass" : "fail").append("\">")
                    .append(judgment.passed() ? "通过" : "未过").append("</td>");
            sb.append("<td>").append(judgment.outcome().label()).append("</td>");
            sb.append("<td>").append(escape(judgment.reason())).append("</td>");
            sb.append("</tr>");
        }
        return sb.toString();
    }

    private static String pct(double rate) {
        return String.format("%.0f%%", rate * 100);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
