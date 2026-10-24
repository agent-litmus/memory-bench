package io.github.agentlitmus.report;

import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.util.Digests;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 雷达图渲染的 golden 快照：固定输入 ⇒ 固定 sha256。
 * <p>
 * 渲染层没有任何测试时，字号、坐标、图例换行这类改动只能靠人眼盯截图；
 * 而渲染又是跨 JVM sha256 对账（CI 门禁）的一环——小数点被本地化、换行符跟平台走，
 * 都会让同一份数据在不同环境渲染出不同字节。这个测试把「渲染是纯函数」钉死：
 * <b>输入没变，字节就不许变</b>；有意改样式时，重算哈希并 conscious 地更新它。
 * <p>
 * 重算方式：把失败信息里打印的「实际 sha256」替换 GOLDEN_SHA256 即可——
 * 但先确认差异确实来自有意的样式改动，而不是引入了不确定性。
 */
class RadarChartGoldenTest {

    /** 有意改样式时更新我（先看失败信息里的实际值，并确认改动是有意的） */
    private static final String GOLDEN_SHA256 = "987e213270fd7224a79cc4e5cc0fde2e66830a44bd9b472d28b5c49b52a3bb12";

    @Test
    void fixedInputRendersByteIdenticalSvg() {
        Map<String, Map<Dimension, Double>> series = new LinkedHashMap<>();
        series.put("参考智能体（baseline）", rates(1.0, 1.0, 1.0, 1.0, 1.0, 1.0));
        series.put("缺陷智能体（degraded）", rates(1.0, 0.875, 0.0, 0.6, 0.0, 0.76));
        series.put("Hermes Agent（ACP）", rates(0.96, 0.56, 0.04, 0.36, 0.12, 0.76));

        String svg = RadarChart.svg(series);
        String actual = Digests.sha256Hex(svg);
        assertEquals(GOLDEN_SHA256, actual,
                "\n雷达图渲染与 golden 不一致。若样式改动是有意的，用下面的值更新 GOLDEN_SHA256：\n"
                        + "实际 sha256=" + actual + "\n输入不变而字节变了 = 引入了不确定性（locale/换行/迭代序），必须排查\n");
    }

    private static Map<Dimension, Double> rates(double... values) {
        Dimension[] dims = Dimension.values();
        Map<Dimension, Double> rates = new LinkedHashMap<>();
        for (int i = 0; i < dims.length; i++) {
            rates.put(dims[i], values[i]);
        }
        return rates;
    }
}
