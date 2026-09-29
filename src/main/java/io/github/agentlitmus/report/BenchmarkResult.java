package io.github.zaojiaoci.agentlitmus.report;
import io.github.zaojiaoci.agentlitmus.core.Dimension;
import io.github.zaojiaoci.agentlitmus.evidence.EvidenceCollector;
import io.github.zaojiaoci.agentlitmus.agent.AgentUnderTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次批量评测的完整结果：多个被测智能体各自的报告与证据。
 * <p>
 * 命题要求「支持导入不同智能体配置进行批量对比评测」，因此结果需要
 * 同时容纳多个智能体，并支持横向对比（雷达图 / 对比表）。
 *
 * @param reports 智能体 → 评测报告
 * @param evidence 智能体 → 证据收集器
 */
public record BenchmarkResult(Map<AgentUnderTest, BenchmarkReport> reports,
                              Map<AgentUnderTest, EvidenceCollector> evidence) {

    public BenchmarkResult {
        reports = reports == null ? new LinkedHashMap<>() : new LinkedHashMap<>(reports);
        evidence = evidence == null ? new LinkedHashMap<>() : new LinkedHashMap<>(evidence);
    }

    /** 各智能体的总体通过率（名称 → 通过率），用于横向对比 */
    public Map<String, Double> overallRates() {
        Map<String, Double> rates = new LinkedHashMap<>();
        reports.forEach((agent, report) -> rates.put(agent.name(), report.passRate()));
        return rates;
    }

    /** 各智能体的六维通过率（名称 → 维度 → 通过率），用于雷达图 */
    public Map<String, Map<Dimension, Double>> dimensionRates() {
        Map<String, Map<Dimension, Double>> rates = new LinkedHashMap<>();
        reports.forEach((agent, report) -> rates.put(agent.name(), RadarChart.ratesOf(report)));
        return rates;
    }

    /** 按总体通过率从高到低排序的智能体列表 */
    public List<AgentUnderTest> ranked() {
        return reports.keySet().stream()
                .sorted((a, b) -> Double.compare(reports.get(b).passRate(), reports.get(a).passRate()))
                .toList();
    }

    public BenchmarkReport reportOf(AgentUnderTest agent) {
        return reports.get(agent);
    }
}
