package io.github.agentlitmus.report;

import io.github.agentlitmus.agent.AgentRole;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.FailureCause;
import io.github.agentlitmus.core.Judgment;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.core.Outcome;
import io.github.agentlitmus.dataset.CoverageReport;
import io.github.agentlitmus.util.Digests;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTML 报告渲染的 golden 快照：固定输入 ⇒ 固定 sha256。
 * <p>
 * 与 {@link RadarChartGoldenTest} 同一动机：报告是给人看、给录屏用、也进 sha256 对账的产物，
 * 渲染必须是纯函数。报告里唯一的非确定项是首屏「生成时间」，golden 前先把它替换成占位符，
 * 其余所有字节（含内嵌雷达图、逐条明细、归因建议）都在哈希覆盖范围内。
 */
class HtmlReportGoldenTest {

    /** 有意改样式/文案时更新我（先看失败信息里的实际值，并确认改动是有意的） */
    private static final String GOLDEN_SHA256 = "4aea8ecca1a7689617d0d552673c2956e43cb1c889f3ac49125b3f42e9a39828";

    @Test
    void fixedInputRendersByteIdenticalHtml() {
        List<MemoryCase> cases = List.of(
                MemoryCase.expect("g1", Dimension.RETENTION,
                        List.of("用户的工牌颜色是青绿色"), "我的工牌是什么颜色？", "青绿色"),
                MemoryCase.expectUpdate("g2", "备份窗口是凌晨 2 点", "备份窗口改为凌晨 4 点",
                        "现在的备份窗口是几点？", "凌晨 4 点", "凌晨 2 点"),
                MemoryCase.expectAbsent("g3", Dimension.BOUNDARY,
                        List.of("临时验证码 8899"), false, "我的验证码是多少？", "8899"));

        AgentUnderTest primary = AgentUnderTest.dialogue("gold-a", "甲智能体", "golden 固定输入的主对比",
                (sessionId, question) -> "回答", AgentRole.PRIMARY, List.of());
        AgentUnderTest extra = AgentUnderTest.dialogue("gold-b", "乙智能体（垂直领域）", "golden 固定输入的附加案例",
                (sessionId, question) -> "回答", AgentRole.EXTRA, List.of());

        Map<AgentUnderTest, BenchmarkReport> reports = new LinkedHashMap<>();
        reports.put(primary, new BenchmarkReport(List.of(
                Judgment.rule("g1", Dimension.RETENTION, true, "包含[青绿色] 命中",
                        Outcome.CORRECT, null),
                Judgment.rule("g2", Dimension.UPDATE, false, "包含[凌晨 4 点] 未命中",
                        Outcome.WRONG_REUSE, FailureCause.STALE_REUSE),
                Judgment.unknownByLlm("g3", Dimension.BOUNDARY,
                        "排除[8899] 出现（需评委但无可用评委——未发生判定）", null))));
        reports.put(extra, new BenchmarkReport(List.of(
                Judgment.rule("g1", Dimension.RETENTION, false, "包含[青绿色] 未命中",
                        Outcome.OMISSION, FailureCause.RECALL_MISSING))));

        BenchmarkResult result = new BenchmarkResult(reports, new LinkedHashMap<>());
        String html = HtmlReport.html(result, null, CoverageReport.of(cases),
                null, null, "样例归因文本（golden）");

        // 报告首屏的时间戳是唯一合法的非确定项：替换成占位符再哈希
        String normalized = html.replaceAll("生成时间：\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}",
                "生成时间：GOLDEN");
        String actual = Digests.sha256Hex(normalized);
        assertEquals(GOLDEN_SHA256, actual,
                "\nHTML 渲染与 golden 不一致。若样式/文案改动是有意的，用下面的值更新 GOLDEN_SHA256：\n"
                        + "实际 sha256=" + actual + "\n输入不变而字节变了 = 引入了不确定性（locale/换行/迭代序），必须排查\n");
    }
}
