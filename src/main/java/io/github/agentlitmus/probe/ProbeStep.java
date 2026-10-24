package io.github.agentlitmus.probe;

import io.github.agentlitmus.agent.AgentCapability;

/**
 * 探针的一个观测步骤——接入向导的输出由这些步骤逐条组成。
 *
 * @param name       步骤名（人读的观测标题）
 * @param capability 该步骤对应的能力；{@code null} 表示只是背景观测，不构成能力判据
 * @param result     观测结论
 * @param detail     依据：看到了什么、为什么这样判
 */
public record ProbeStep(String name, AgentCapability capability, Result result, String detail) {

    /**
     * 观测结论只有三种，且<b>「没测到」不等于「没有」</b>：
     * 把测不了写成 NO，接入方会被自己的探针劝退；写成 NOT_TESTABLE，
     * 报告里就是「置信度受限」而不是「该平台有缺陷」。
     */
    public enum Result {
        /** 观测到该能力成立 */
        YES,
        /** 观测到该能力不成立（真实缺陷或平台确实做不到） */
        NO,
        /** 本次探测无法给出结论（缺少可扫描目录、黑盒测不到等） */
        NOT_TESTABLE;

        public String label() {
            return switch (this) {
                case YES -> "✓ 已观测";
                case NO -> "✗ 未成立";
                case NOT_TESTABLE -> "? 不可探测";
            };
        }
    }
}
