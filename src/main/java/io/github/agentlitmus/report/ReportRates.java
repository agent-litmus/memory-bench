package io.github.agentlitmus.report;

import io.github.agentlitmus.core.Dimension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从已生成的 {@code report.txt} 中解析「各智能体 × 六维通过率」。
 * <p>
 * 存在意义：<b>不重跑评测也能重新出图</b>。评测一旦涉及真实智能体，往往耗时数十分钟并消耗云端额度；
 * 而图表样式只是<b>渲染问题</b>，与数据采集无关。把已有结果重新渲染一遍即可，无需重新跑一遍被测对象。
 */
public final class ReportRates {

    private ReportRates() {
    }

    /**
     * 解析「多智能体横向对比」表。
     * <p>
     * 表格形如（智能体名称可能含空格，数值列固定为「六维 + 总体」共 7 列）：
     * <pre>
     * 智能体                   长期保持  记忆调用  ...  任务复用  总体
     * KylinBot（麒灵助手）         92%      56%    ...     76%     67%
     * Hermes Agent（ACP）         96%      56%    ...     76%     47%
     * </pre>
     * 因此取<b>末尾 7 个</b>数值 token，其余部分拼接为智能体名称——这样即使名称含空格也能正确还原。
     */
    public static Map<String, Map<Dimension, Double>> parse(Path reportTxt) throws IOException {
        Map<String, Map<Dimension, Double>> series = new LinkedHashMap<>();
        Dimension[] dims = Dimension.values();
        int numberCols = dims.length + 1;   // 六维 + 总体

        boolean inTable = false;
        boolean headerSeen = false;
        for (String raw : Files.readAllLines(reportTxt, StandardCharsets.UTF_8)) {
            String line = raw.trim();

            if (line.contains("多智能体横向对比")) {
                inTable = true;
                headerSeen = false;
                continue;
            }
            if (!inTable) {
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("=")) {
                if (!series.isEmpty()) {
                    break;    // 表格结束
                }
                continue;
            }
            if (line.startsWith("智能体")) {
                headerSeen = true;
                continue;
            }
            if (!headerSeen) {
                continue;
            }

            String[] toks = line.split("\\s+");
            if (toks.length < numberCols + 1) {
                continue;
            }
            int n = toks.length;
            List<Double> nums = new ArrayList<>();
            for (int i = n - numberCols; i < n; i++) {
                try {
                    nums.add(Double.parseDouble(toks[i].replace("%", "")) / 100.0);
                } catch (NumberFormatException e) {
                    nums.clear();
                    break;
                }
            }
            if (nums.size() != numberCols) {
                continue;
            }

            StringBuilder name = new StringBuilder();
            for (int i = 0; i < n - numberCols; i++) {
                if (i > 0) {
                    name.append(' ');
                }
                name.append(toks[i]);
            }
            Map<Dimension, Double> rates = new LinkedHashMap<>();
            for (int i = 0; i < dims.length; i++) {
                rates.put(dims[i], nums.get(i));
            }
            series.put(name.toString(), rates);
        }
        return series;
    }
}
