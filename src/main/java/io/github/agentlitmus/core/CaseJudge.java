package io.github.agentlitmus.core;
import io.github.agentlitmus.llm.JudgeCache;
import io.github.agentlitmus.llm.LlmClient;
import io.github.agentlitmus.util.TextMatch;

import java.util.Locale;

/**
 * 用例判定器：<b>规则优先、语义兜底、拿不准就弃权</b>，判定为三态（通过 / 未过 / 不可判），
 * 通过与否再归入五类结果，并叠加一层记忆失败归因（含改进建议）。
 * <p>
 * 三条顺序上的刻意选择：
 * <ol>
 *   <li><b>规则优先</b>——判定本身不应引入新的不确定性，否则「评测结果不稳定」会掩盖被测对象的真实问题；</li>
 *   <li><b>三态</b>——评委不可用、模型调用失败、被测对象无响应，都意味着<b>判定并未发生</b>。
 *       算成失败会让评测导管的故障冒充记忆能力缺陷，算成通过又等于什么都没测；</li>
 *   <li><b>信号冲突时弃权</b>——规则层两个信号互相矛盾（期望项与排除项同时出现）时，
 *       关键词原理上分不清"对照说明"和"真的混淆"，此时宁可不判。
 *       这一条不是设计洁癖，是标注集量出来的：硬判会在真实回答上造出成批假阴性
 *       （见 {@code litmus selftest}）。</li>
 * </ol>
 */
public class CaseJudge {

    /**
     * 判定口径版本。同一批证据在不同口径下会得出不同结论，所以口径必须进运行契约：
     * 换了规则能看出「是规则变了」而不是「数据变了」。
     * <ul>
     *   <li>{@code 2026.10-a}：两态，无响应与评委故障计为失败；</li>
     *   <li>{@code 2026.10-b}：三态，无响应 / 评委不可用 / 评委调用失败一律计为不可判并移出分母；</li>
     *   <li>{@code 2026.10-c}：关键词比对改为归一化比对（NFKC + 去空白 + 小写，修掉 "ISO 9001" ≠ "ISO9001"
     *       这类纯排印造成的假阴性）；排除项命中时按维度分流——边界维度从严（复述即泄漏，免责声明不免责），
     *       其余维度在信号冲突时判不可判并交评委层（有评委则问评委，没有则如实弃权）。
     *       代价是覆盖率下降、收益是"开口即对率"上升，两者都写进 selftest 报告。</li>
     *   <li>同版还补了非答案识别（{@link NonAnswer}）：被测端返回的排队提示不再被当成回答计分。</li>
     * </ul>
     */
    public static final String RULES_VERSION = "2026.10-c";

    private final LlmClient llm;
    private final String judgeId;
    private final JudgeCache cache;

    /** 无模型：可判定的用例走规则，需要语义的用例产出「不可判」 */
    public CaseJudge() {
        this(null, null, null);
    }

    public CaseJudge(LlmClient llm) {
        this(llm, null, null);
    }

    /**
     * @param judgeId 评委指纹（模型 + 端点 + 判词版本）；进缓存键，换评委不会命中旧裁决
     * @param cache   裁决缓存；{@code null} 表示每次都问模型
     */
    public CaseJudge(LlmClient llm, String judgeId, JudgeCache cache) {
        this.llm = llm;
        this.judgeId = judgeId;
        this.cache = cache;
    }

    public boolean llmAvailable() {
        return llm != null;
    }

    /** 评委指纹；无评委时为 {@code null} */
    public String judgeFingerprint() {
        return judgeId;
    }

    public Judgment judge(MemoryCase kase, String answer) {
        String text = answer == null ? "" : answer;

        // 0) 无有效响应必须先于规则判定截断：否则「空回答 + 用例无关键词约束」会被当成通过。
        //    状态占位文本（排队提示等）与空回答同类——都是"这一轮没拿到回答"，见 NonAnswer。
        if (text.isBlank()) {
            return Judgment.noResponse(kase.id(), kase.dimension(),
                    "无有效响应（空回答 / 请求失败 / 限流 / 超时）——判定未发生，不计入评分分母");
        }
        String placeholder = NonAnswer.detect(text);
        if (placeholder != null) {
            return Judgment.noResponse(kase.id(), kase.dimension(),
                    "被测端返回状态占位文本而非回答：「" + placeholder + "」——判定未发生，不计入评分分母");
        }

        // 1) 规则判定：期望包含 / 期望排除（归一化后比对，见 TextMatch）
        StringBuilder reason = new StringBuilder();
        boolean rulePass = true;
        boolean expectedHit = true;
        boolean excludedAppeared = false;

        String expected = kase.expectedContains();
        boolean hasExpected = expected != null && !expected.isBlank();
        if (hasExpected) {
            expectedHit = TextMatch.contains(text, expected);
            rulePass &= expectedHit;
            reason.append("包含[").append(expected).append("] ").append(expectedHit ? "命中" : "未命中");
        }

        String excluded = kase.expectedNotContains();
        boolean hasExcluded = excluded != null && !excluded.isBlank();
        if (hasExcluded) {
            excludedAppeared = TextMatch.contains(text, excluded);
            rulePass &= !excludedAppeared;
            if (!reason.isEmpty()) {
                reason.append("；");
            }
            reason.append("排除[").append(excluded).append("] ").append(excludedAppeared ? "出现" : "未出现");
        }

        if (reason.isEmpty()) {
            reason.append("无规则约束");
        }

        // 2) 排除项命中时的分流。
        //    边界维度从严：不该保留的信息被复述出来就是泄漏，"我只是在提醒你"不改变它已经出现在回答里。
        //    其余维度：排除项出现有两种完全不同的解释——作为对照/旧值/否定语境被提及（应判通过），
        //    或被当成了当前答案（应判未过）。字面一致、语义相反，关键词分不开，只能弃权或问评委。
        //    例外：期望项明确存在却没命中，说明它既没答对又答了不该答的，这不是歧义，是失败。
        if (excludedAppeared && kase.dimension() != Dimension.BOUNDARY
                && (expectedHit || !hasExpected)) {
            String note = reason + "（排除项出现，但"
                    + (hasExpected ? "期望项同时命中" : "本用例只约束排除项")
                    + "：关键词无法区分「对照说明 / 否定语境」与「真的混淆或泄漏」）";
            if (llm == null) {
                return Judgment.degraded(kase.id(), kase.dimension(),
                        note + "——需评委层，判定未发生（加 --judge 可让语义层接管）",
                        FailureCause.NEEDS_SEMANTIC_JUDGE);
            }
            return judgeByLlm(kase, text, note, ambiguityRubric(kase, expected, excluded));
        }

        Outcome outcome = classify(kase, rulePass, expectedHit, excludedAppeared);
        FailureCause cause = causeOf(outcome, kase.crossSession());

        // 3) 不需要语义判定：规则结果即为最终结果
        if (!kase.requiresLlmJudge()) {
            return Judgment.rule(kase.id(), kase.dimension(), rulePass,
                    rulePass ? reason.toString() : reason + "（不合格）", outcome, cause);
        }

        // 4) 需要语义判定但没有评委：判定并未发生，不能替被测对象记一笔失败
        if (llm == null) {
            return Judgment.degraded(kase.id(), kase.dimension(),
                    reason + "（需语义判定但无可用评委——未发生判定）", null);
        }

        // 5) 规则是底线：规则不过直接判失败，不再浪费一次模型调用
        if (!rulePass) {
            return Judgment.rule(kase.id(), kase.dimension(), false, reason + "（不合格）", outcome, cause);
        }

        return judgeByLlm(kase, text, reason.toString(), kase.llmRubric());
    }

    /**
     * 信号冲突时给评委的判定标准。
     * <p>
     * 判词里必须把"两种解释"都写清楚，否则评委会顺着关键词的表面命中直接判过——
     * 那就等于把规则层的错误换了一种更贵的方式再犯一次。
     */
    private static String ambiguityRubric(MemoryCase kase, String expected, String excluded) {
        return "判断这段回答是否<b>正确使用了应被记住的信息</b>。\n"
                + (expected == null || expected.isBlank() ? "" : "期望回答传达：" + expected + "\n")
                + "不应被当成当前答案的内容：" + excluded + "\n"
                + "关键区分——「" + excluded + "」出现在回答里有两种完全不同的情况：\n"
                + "  (a) 作为对照项、旧值、或被明确否定/拒绝的内容被提及"
                + "（例如「测试库在 5433，别搞混」「4核8G 已被 8核16G 取代」「不含花生」）——这不算混淆；\n"
                + "  (b) 被当成当前答案，或与期望内容混为一谈、无法分辨哪个是准的——这算混淆。\n"
                + "只有 (a) 判 PASS，出现 (b) 判 FAIL。\n"
                + "若回答根本没有作答（只有排队提示、空转输出、纯反问澄清、拒绝定论），输出 UNKNOWN。";
    }

    /**
     * 把判定结果归入五类之一。
     * <p>
     * 分类依据是「错在哪」而非「错了没」：同样不合格，混入干扰项、混入过时旧值、混入不该保留的信息，
     * 指向三种完全不同的记忆缺陷。
     */
    private static Outcome classify(MemoryCase kase, boolean passed,
                                    boolean expectedHit, boolean excludedAppeared) {
        if (passed) {
            return Outcome.CORRECT;
        }
        if (excludedAppeared) {
            return switch (kase.dimension()) {
                case BOUNDARY -> Outcome.WRONG_PERSISTENCE;
                case UPDATE -> Outcome.WRONG_REUSE;
                case DISCRIMINATION -> Outcome.CONFUSION;
                default -> Outcome.OMISSION;
            };
        }
        return Outcome.OMISSION;
    }

    /**
     * 在五类结果之上做记忆失败归因。
     * <p>
     * 对 {@link Outcome#OMISSION} 的三态细分依赖 {@code crossSession} 标记推断遗漏发生的阶段：
     * 跨会话仍遗漏 → 未持久化；同会话遗漏 → 未召回/未应用。这是<b>推断而非观测</b>，
     * 要让它成为证据，需要补上记忆状态快照（T2 层）与神谕对照。
     */
    private static FailureCause causeOf(Outcome outcome, boolean crossSession) {
        return switch (outcome) {
            case CORRECT -> null;
            case OMISSION -> crossSession ? FailureCause.PERSIST_MISSING : FailureCause.RECALL_MISSING;
            case CONFUSION -> FailureCause.SIMILAR_CONFUSED;
            case WRONG_PERSISTENCE -> FailureCause.BOUNDARY_LEAKED;
            case WRONG_REUSE -> FailureCause.STALE_REUSE;
        };
    }

    /**
     * 问评委，并把裁决写进缓存。
     * <p>
     * 命中缓存时<b>不发请求</b>：这既是省钱，也是"离线重评结果不变"的唯一保证。
     * 缓存键含判定口径版本与评委指纹，所以换口径、换评委都会重新询问，不会串味。
     */
    private Judgment judgeByLlm(MemoryCase kase, String answer, String ruleReason, String rubric) {
        String key = cache == null ? null : JudgeCache.key(RULES_VERSION, judgeId, rubric, answer);
        String verdict = null;
        boolean fromCache = false;
        if (key != null) {
            JudgeCache.Entry hit = cache.get(key);
            if (hit != null) {
                verdict = hit.verdict();
                fromCache = true;
            }
        }

        if (verdict == null) {
            try {
                verdict = llm.complete(promptOf(rubric, answer));
            } catch (Exception e) {
                // 模型故障不等于被测对象记忆失败：判定未发生
                return Judgment.degraded(kase.id(), kase.dimension(),
                        ruleReason + "（评委调用失败——未发生判定：" + e.getMessage() + "）", null);
            }
            if (verdict == null || verdict.isBlank()) {
                return Judgment.degraded(kase.id(), kase.dimension(),
                        ruleReason + "（评委无输出——未发生判定）", null);
            }
            verdict = verdict.trim();
            if (key != null) {
                cache.put(key, verdict, ruleReason, judgeId);
            }
        }

        String upper = verdict.trim().toUpperCase(Locale.ROOT);
        String suffix = fromCache ? "；评委判定（缓存命中）：" : "；评委判定：" ;
        if (upper.startsWith("UNKNOWN")) {
            // 评委也认为判定不该发生（例如被测端只回了排队提示）：如实记为不可判
            return Judgment.unknownByLlm(kase.id(), kase.dimension(), ruleReason + suffix + verdict.trim(), null);
        }
        boolean pass = upper.startsWith("PASS");
        // 规则通过但评委判不过：结果类别不能沿用规则那条（它会把"通过"的分类带过来），
        // 统一按"未出现排除项而判定不过"归入遗漏，保持分类口径只有一处定义
        Outcome finalOutcome = pass ? Outcome.CORRECT : classify(kase, false, true, false);
        return new Judgment(kase.id(), kase.dimension(), pass ? Verdict.PASS : Verdict.FAIL,
                pass ? 1.0 : 0.0,
                ruleReason + suffix + verdict.trim(),
                Judgment.MODE_LLM, finalOutcome, causeOf(finalOutcome, kase.crossSession()));
    }

    /** 判词是评委指纹的一部分：改判词必须改 {@code PROMPT_VERSION}，否则旧缓存会被错误命中 */
    private static String promptOf(String rubric, String answer) {
        return "你是严格的评测裁判。只输出 PASS、FAIL 或 UNKNOWN 之一开头，随后用一句话说明理由。\n"
                + "PASS=回答正确使用了应被记住的信息；FAIL=没有；UNKNOWN=根本没有作答，判定不应发生。\n\n"
                + "【判定标准】\n" + rubric
                + "\n\n【待判定回答】\n" + answer;
    }
}
