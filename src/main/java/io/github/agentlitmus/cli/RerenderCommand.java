package io.github.agentlitmus.cli;

import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.report.RadarChart;
import io.github.agentlitmus.report.ReportRates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * {@code litmus rerender}——按已有报告重新渲染雷达图，<b>不重跑评测</b>。
 * <p>
 * 评测一旦涉及真实智能体，往往耗时数十分钟并消耗云端额度；而图表样式只是<b>渲染问题</b>，
 * 与数据采集无关。因此从 {@code report.txt} 解析各智能体六维通过率后直接重新出图，
 * 并同步更新 {@code report.html} 中内嵌的 SVG。
 *
 * <p>产物默认写到哪里由 {@link #rerenderTarget} 决定：带 {@code --out} 时源目录只读，
 * 不带时只有在源目录没有 {@code manifest.json} 的情况下才允许就地改写。
 */
public final class RerenderCommand implements Command {

    @Override
    public String name() {
        return "rerender";
    }

    @Override
    public void run(CliOptions o) {
        Path from = Path.of(o.from == null ? CliOptions.DEFAULT_OUT : o.from);
        Path report = from.resolve("report.txt");
        if (!Files.exists(report)) {
            System.err.println("未找到报告文件: " + report + "（请先运行评测，或用 --from 指定运行目录）");
            return;
        }
        Path target = rerenderTarget(from, Path.of(o.out), o.outExplicit);
        boolean inPlace = target.equals(from);
        try {
            Map<String, Map<Dimension, Double>> series = ReportRates.parse(report);
            if (series.isEmpty()) {
                System.err.println("未能从 " + report + " 解析出横向对比数据。");
                return;
            }
            if (!inPlace) {
                Files.createDirectories(target);
            }
            String svg = RadarChart.svg(series);
            Path svgOut = target.resolve("radar.svg");
            Files.writeString(svgOut, svg, StandardCharsets.UTF_8);
            System.out.println("已重新生成雷达图: " + svgOut + "（" + series.size() + " 款智能体）");
            series.forEach((name, rates) -> System.out.println("  - " + name));

            Path srcHtml = from.resolve("report.html");
            if (Files.exists(srcHtml)) {
                String content = Files.readString(srcHtml, StandardCharsets.UTF_8);
                String updated = replaceFirstSvg(content, svg);
                Path htmlOut = target.resolve("report.html");
                if (updated == null) {
                    System.out.println("内嵌雷达图无从替换（HTML 里没有完整的 <svg>…</svg> 块），只产出 radar.svg");
                } else if (inPlace && updated.equals(content)) {
                    System.out.println("内嵌雷达图已是最新（与现有版本逐字节一致，未改写）");
                } else {
                    Files.writeString(htmlOut, updated, StandardCharsets.UTF_8);
                    System.out.println("已同步更新内嵌雷达图: " + htmlOut);
                }
            }
            if (!inPlace) {
                Files.copy(report, target.resolve("report.txt"), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("源目录未被改动: " + from);
                System.out.println("注意: " + target + " 里没有 manifest.json——"
                        + "产物哈希属于原始那次运行，这个目录不能拿去做 verify 对账。");
            }
        } catch (IOException e) {
            System.err.println("重新渲染失败: " + e.getMessage());
        }
    }

    /**
     * 决定重渲染的产物写到哪里。
     *
     * <p>就地改写 {@code radar.svg} / {@code report.html} 会让同一个目录的
     * {@code verify} 报「产物被改动」——一次运行的可对账性就这样被一条画图命令毁掉，
     * 而用户只是想让图好看一点。所以目录里已经登记了哈希就拒绝就地写，
     * 让人换一个 {@code --out}；没登记过（临时目录、旧版本产物）才允许就地。
     */
    static Path rerenderTarget(Path from, Path out, boolean outExplicit) {
        Path source = from.toAbsolutePath().normalize();
        if (outExplicit) {
            Path target = out.toAbsolutePath().normalize();
            if (target.equals(source)) {
                throw new IllegalStateException("输出目录与 --from 相同：" + source
                        + "。源目录需要只读，否则这次渲染会覆盖掉自己解析的那份报告。请换一个 --out。");
            }
            return target;
        }
        if (Files.exists(source.resolve("manifest.json"))) {
            throw new IllegalStateException("拒绝就地重渲染：" + source
                    + " 已用 manifest.json 登记产物哈希，改写 radar.svg / report.html 之后"
                    + " verify 会报「产物被改动」——画图命令不该毁掉一次运行的可对账性。"
                    + "请加 --out <新目录>，产物写到新目录，源目录不动。");
        }
        return source;
    }

    /**
     * 用新的 SVG 替换 HTML 中第一个 {@code <svg>…</svg>} 块。
     *
     * @return 替换后的内容；找不到完整 SVG 块时返回 {@code null}（与「替换后内容一致」区分开）
     */
    static String replaceFirstSvg(String html, String svg) {
        int start = html.indexOf("<svg");
        if (start < 0) {
            return null;
        }
        int end = html.indexOf("</svg>", start);
        if (end < 0) {
            return null;
        }
        return html.substring(0, start) + svg + html.substring(end + "</svg>".length());
    }
}
