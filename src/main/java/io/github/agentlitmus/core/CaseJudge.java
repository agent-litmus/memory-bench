package io.github.zaojiaoci.agentlitmus.core;
import io.github.zaojiaoci.agentlitmus.llm.LlmClient;

import java.util.Locale;

/**
 * 用例判定器：<b>规则优先、LLM 兜底</b>，并输出五类结果之一。
 * <p>
 * 这是刻意的顺序选择：能用确定性规则判定的（关键词包含 / 排除）绝不交给模型——
 * 判定本身不应引入新的不确定性，否则「评测结果不稳定」会掩盖被测对象的真实问题。
 * 只有无法用关键词表达的标准（如语气、得体性）才走 LLM，且无模型时降级为规则判定并标注，
 * 不会因为缺模型就跑不出结果。
 */
public class CaseJudge {

    private final LlmClient llm;

    /** 无模型：全部走规则判定 */
    public CaseJudge() {
        this(null);
    }

    public CaseJudge(LlmClient llm) {
        this.llm = llm;
    }

    public boolean llmAvailable() {
        return llm != null;
    }

    public Judgment judge(MemoryCase kase, String answer) {
        String text = answer == null ? "" : answer;

        // 1) 规则判定：期望包含 / 期望排除
        StringBuilder reason = new StringBuilder();
        boolean rulePass = true;
        boolean expectedHit = true;       // 期望内容是否命中
        boolean excludedAppeared = false; // 不该出现的内容是否出现

        String expected = kase.expectedContains();
        if (expected != null && !expected.isBlank()) {
            expectedHit = text.contains(expected);
            rulePass &= expectedHit;
            reason.append("包含[").append(expected).append("] ").append(expectedHit ? "命中" : "未命中");
        }

        String excluded = kase.expectedNotContains();
        if (excluded != null && !excluded.isBlank()) {
            excludedAppeared = text.contains(excluded);
            rulePass &= !excludedAppeared;
            if (reason.length() > 0) {
                reason.append("；");
            }
            reason.append("排除[").append(excluded).append("] ").append(excludedAppeared ? "出现(不合格)" : "未出现(合格)");
        }

        if (reason.length() == 0) {
            reason.append("无规则约束");
        }

        Outcome outcome = classify(kase, rulePass, expectedHit, excludedAppeared);

        // 2) 不需要 LLM 判定：直接返回规则结果
        if (!kase.requiresLlmJudge()) {
            return Judgment.rule(kase.id(), kase.dimension(), rulePass, reason.toString(), outcome);
        }

        // 3) 需要 LLM 判定但没有模型：降级为规则判定并标注，保证流程不中断
        if (llm == null) {
            return new Judgment(kase.id(), kase.dimension(), rulePass, rulePass ? 1.0 : 0.0,
                    reason + "（需 LLM 判定但无可用模型，已降级）", Judgment.MODE_DEGRADED, outcome);
        }

        // 4) 规则是底线：规则不过直接判失败，不再浪费一次模型调用
        if (!rulePass) {
            return Judgment.rule(kase.id(), kase.dimension(), false, reason.toString(), outcome);
        }

        return judgeByLlm(kase, text, reason.toString(), outcome);
    }

    /**
     * 把判定结果归入五类之一。
     * <p>
     * 分类依据是「错在哪」而非「错了没」：同样是不合格，混入了干扰项、混入了过时旧值、
     * 混入了不该保留的信息，指向的是三种完全不同的记忆缺陷。
     */
    private static Outcome classify(MemoryCase kase, boolean passed,
                                    boolean expectedHit, boolean excludedAppeared) {
        if (passed) {
            return Outcome.CORRECT;
        }
        if (excludedAppeared) {
            return switch (kase.dimension()) {
                // 边界维度里出现了不该保留的信息 —— 不该记却记了
                case BOUNDARY -> Outcome.WRONG_PERSISTENCE;
                // 更新维度里出现了旧值 —— 用了过时的信息
                case UPDATE -> Outcome.WRONG_REUSE;
                // 区分维度里出现了干扰项 —— 把相近信息搞混
                case DISCRIMINATION -> Outcome.CONFUSION;
                default -> Outcome.OMISSION;
            };
        }
        // 期望的内容没出现：该记住/该用上的没做到
        return expectedHit ? Outcome.OMISSION : Outcome.OMISSION;
    }

    private Judgment judgeByLlm(MemoryCase kase, String answer, String ruleReason, Outcome outcome) {
        String verdict;
        try {
            verdict = llm.complete(
                    "你是严格的评测裁判。只输出 PASS 或 FAIL 开头，随后用一句话说明理由。\n\n"
                            + "【判定标准】\n" + kase.llmRubric()
                            + "\n\n【待判定回答】\n" + answer);
        } catch (Exception e) {
            // 模型调用失败不应让评测崩溃：回退到规则结果
            return new Judgment(kase.id(), kase.dimension(), true, 1.0,
                    ruleReason + "（LLM 判定失败，回退规则：" + e.getMessage() + "）",
                    Judgment.MODE_DEGRADED, outcome);
        }

        boolean pass = verdict != null
                && verdict.trim().toUpperCase(Locale.ROOT).startsWith("PASS");
        return new Judgment(kase.id(), kase.dimension(), pass, pass ? 1.0 : 0.0,
                ruleReason + "；LLM 判定：" + (verdict == null ? "无输出" : verdict.trim()),
                Judgment.MODE_LLM, pass ? Outcome.CORRECT : outcome);
    }
}
