package io.github.zaojiaoci.agentlitmus.report;
import io.github.zaojiaoci.agentlitmus.core.Dimension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 六维雷达图生成器（纯 SVG，零依赖）。
 * <p>
 * 命题要求「产出多维对比雷达图」。这里选择直接生成 SVG 字符串而非引入图表库：
 * <ul>
 *   <li>零依赖，打包成 CLI / .deb 后不依赖任何图形库；</li>
 *   <li>可以直接内嵌进 HTML 报告，浏览器打开即见，便于录屏演示；</li>
 *   <li>文本格式可读、可版本管理、可转成 PNG。</li>
 * </ul>
 */
public final class RadarChart {

    private static final String[] COLORS = {
            "#2E7D32", "#C62828", "#1565C0", "#EF6C00", "#6A1B9A", "#00838F"
    };

    private static final int WIDTH = 640;
    private static final int HEIGHT = 520;
    // 中心与半径参与浮点坐标计算，必须声明为 double：
    // 否则 String.format("%.1f", CX) 会因传入 int 抛出 IllegalFormatConversionException
    private static final double CX = 320;
    private static final double CY = 250;
    private static final double RADIUS = 165;

    private RadarChart() {
    }

    /**
     * 生成雷达图 SVG。
     *
     * @param series 智能体名称 → （维度 → 通过率 0~1）
     * @return SVG 字符串
     */
    public static String svg(Map<String, Map<Dimension, Double>> series) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">%n",
                WIDTH, HEIGHT, WIDTH, HEIGHT));
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>");
        sb.append("<g font-family=\"sans-serif\">");

        // 网格：同心六边形
        for (double level = 0.25; level <= 1.0001; level += 0.25) {
            sb.append(polygon(ring(level), "#d0d0d0", level >= 1.0 ? 2 : 1, "none"));
        }

        // 轴线与维度标签
        Dimension[] dims = Dimension.values();
        for (int i = 0; i < dims.length; i++) {
            double angle = angleOf(i, dims.length);
            double x = CX + RADIUS * Math.cos(angle);
            double y = CY + RADIUS * Math.sin(angle);
            sb.append(String.format(
                    "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"#b0b0b0\" stroke-width=\"1\"/>",
                    CX, CY, x, y));
            // 标签稍微外移
            double lx = CX + (RADIUS + 26) * Math.cos(angle);
            double ly = CY + (RADIUS + 26) * Math.sin(angle);
            sb.append(String.format(
                    "<text x=\"%.1f\" y=\"%.1f\" font-size=\"14\" fill=\"#333\" text-anchor=\"middle\" dominant-baseline=\"middle\">%s</text>",
                    lx, ly, dims[i].label()));
        }

        // 每个智能体一条多边形
        int index = 0;
        for (Map.Entry<String, Map<Dimension, Double>> entry : series.entrySet()) {
            String color = COLORS[index % COLORS.length];
            sb.append(polygon(ringOf(entry.getValue()), color, 2, color));
            // 顶点小圆点
            for (int i = 0; i < dims.length; i++) {
                double rate = entry.getValue().getOrDefault(dims[i], 0.0);
                double angle = angleOf(i, dims.length);
                double r = RADIUS * Math.max(0, Math.min(1, rate));
                sb.append(String.format(
                        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"3.5\" fill=\"%s\"/>",
                        CX + r * Math.cos(angle), CY + r * Math.sin(angle), color));
            }
            index++;
        }

        // 图例
        int legendY = HEIGHT - 34;
        index = 0;
        int legendX = 40;
        for (String name : series.keySet()) {
            String color = COLORS[index % COLORS.length];
            sb.append(String.format(
                    "<rect x=\"%d\" y=\"%d\" width=\"14\" height=\"14\" fill=\"%s\"/>", legendX, legendY - 11, color));
            sb.append(String.format(
                    "<text x=\"%d\" y=\"%d\" font-size=\"14\" fill=\"#333\">%s</text>",
                    legendX + 20, legendY, escape(name)));
            legendX += 30 + name.length() * 14;
            index++;
        }

        sb.append("</g></svg>");
        return sb.toString();
    }

    /** 按维度顺序取出各维通过率，供绘图使用 */
    public static Map<Dimension, Double> ratesOf(BenchmarkReport report) {
        Map<Dimension, Double> rates = new LinkedHashMap<>();
        report.byDimension().forEach((dimension, stat) -> rates.put(dimension, stat.rate()));
        return rates;
    }

    private static List<double[]> ring(double level) {
        List<double[]> points = new ArrayList<>();
        int n = Dimension.values().length;
        for (int i = 0; i < n; i++) {
            double angle = angleOf(i, n);
            points.add(new double[]{CX + RADIUS * level * Math.cos(angle),
                    CY + RADIUS * level * Math.sin(angle)});
        }
        return points;
    }

    private static List<double[]> ringOf(Map<Dimension, Double> rates) {
        List<double[]> points = new ArrayList<>();
        Dimension[] dims = Dimension.values();
        for (int i = 0; i < dims.length; i++) {
            double rate = Math.max(0, Math.min(1, rates.getOrDefault(dims[i], 0.0)));
            double angle = angleOf(i, dims.length);
            points.add(new double[]{CX + RADIUS * rate * Math.cos(angle),
                    CY + RADIUS * rate * Math.sin(angle)});
        }
        return points;
    }

    private static String polygon(List<double[]> points, String stroke, int width, String fill) {
        StringBuilder sb = new StringBuilder("<polygon points=\"");
        for (double[] p : points) {
            sb.append(String.format("%.1f,%.1f ", p[0], p[1]));
        }
        sb.append(String.format("\" stroke=\"%s\" stroke-width=\"%d\" fill=\"%s\" ",
                stroke, width, fill));
        sb.append("fill-opacity=\"0.15\" stroke-linejoin=\"round\"/>");
        return sb.toString();
    }

    /** 从正上方开始，顺时针均匀分布 */
    private static double angleOf(int index, int total) {
        return -Math.PI / 2 + (2 * Math.PI * index / total);
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
