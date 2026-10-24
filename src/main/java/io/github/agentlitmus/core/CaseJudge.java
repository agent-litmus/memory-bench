package io.github.agentlitmus.core;
import io.github.agentlitmus.llm.JudgeCache;
import io.github.agentlitmus.llm.LlmClient;
import io.github.agentlitmus.util.TextMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用例判定器：<b>规则优先、语义兜底、拿不准就弃权</b>，判定为三态（通过 / 未过 / 不可判），
 * 通过与否再归入五类结果，并叠加一层记忆失败归因（含改进建议）。
 * <p>
 * 四条顺序上的刻意选择：
 * <ol>
 *   <li><b>规则优先</b>——判定本身不应引入新的不确定性，否则「评测结果不稳定」会掩盖被测对象的真实问题；</li>
 *   <li><b>三态</b>——评委不可用、模型调用失败、被测对象无响应，都意味着<b>判定并未发生</b>。
 *       算成失败会让评测导管的故障冒充记忆能力缺陷，算成通过又等于什么都没测；</li>
 *   <li><b>信号冲突时弃权</b>——规则层两个信号互相矛盾（期望项与排除项同时出现）时，
 *       关键词原理上分不清"对照说明"和"真的混淆"，此时宁可不判。
 *       这一条不是设计洁癖，是标注集量出来的：硬判会在真实回答上造出成批假阴性
 *       （见 {@code litmus selftest}）。</li>
 *   <li><b>多评委严格多数决</b>——配置多位评委（如 {@code --judge-model qwen-plus,deepseek-chat"}）时，
 *       每位独立请求、独立缓存，按严格多数（&gt; 半数）聚合；平票、不可判占多数、
 *       或某位输出不符合 PASS/FAIL/UNKNOWN 约定，都按「该评委未给出裁决」处理并计入不可判侧。
 *       弃权优先于硬判——评委团的分歧本身是信息，抹平它等于花多份钱得到一份瞎猜。</li>
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

    /**
     * 一位评委：调用句柄 + 指纹（模型@端点@判词版本）。
     * <p>
     * 指纹进缓存键，多位评委各自独立缓存——混用同一个键会让第二位评委直接命中第一位的旧裁决，
     * 「评委团」退化成「一位评委 + 它的复读机」。
     */
    public record Judge(LlmClient client, String id) {
    }

    private final List<Judge> judges;
    private final JudgeCache cache;

    /** 无模型：可判定的用例走规则，需要语义的用例产出「不可判」 */
    public CaseJudge() {
        this(List.of(), null);
    }

    public CaseJudge(LlmClient llm) {
        this(llm == null ? List.of() : List.of(new Judge(llm, "")), null);
    }

    public CaseJudge(LlmClient llm, String judgeId, JudgeCache cache) {
        this(llm == null ? List.of() : List.of(new Judge(llm, judgeId == null ? "" : judgeId)), cache);
    }

    /** 评委团构造：单评委走上面的构造器即可 */
    public CaseJudge(List<Judge> judges, JudgeCache cache) {
        this.judges = List.copyOf(judges);
        this.cache = cache;
    }

    public boolean llmAvailable() {
        return !judges.isEmpty();
    }

    /** 评委指纹；无评委时为 {@code null}，多位用 {@code |} 连接 */
    public String judgeFingerprint() {
        if (judges.isEmpty()) {
            return null;
        }
        return String.join("|", judges.stream().map(Judge::id).toList());
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
            if (!llmAvailable()) {
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
        if (!llmAvailable()) {
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
     * 问评委（团），把每位裁决写进各自的缓存。
     * <p>
     * 命中缓存时<b>不发请求</b>：这既是省钱，也是"离线重评结果不变"的唯一保证。
     * 缓存键含判定口径版本与<b>各自</b>评委指纹，所以换口径、换评委、加评委都会重新询问，不会串味。
     * <p>
     * 聚合规则见类注释第 4 条：严格多数决。单评委时保持原行为（该评委的裁决即最终裁决），
     * 输出不符合约定（不以 PASS/FAIL/UNKNOWN 开头）按弃权计。
     */
    private Judgment judgeByLlm(MemoryCase kase, String answer, String ruleReason, String rubric) {
        int pass = 0;
        int fail = 0;
        int unknown = 0;
        int errors = 0;
        String firstError = null;
        List<String> perJudge = new ArrayList<>();
        String singleVerdict = null;   // 单评委时保留「；评委判定：<原文>」的原格式
        boolean singleFromCache = false;

        for (Judge judge : judges) {
            String key = cache == null ? null : JudgeCache.key(RULES_VERSION, judge.id(), rubric, answer);
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
                    verdict = llmComplete(judge, rubric, answer);
                } catch (Exception e) {
                    // 模型故障不等于被测对象记忆失败：该评委未给出裁决
                    errors++;
                    unknown++;
                    if (firstError == null) {
                        firstError = "评委调用失败——未发生判定：" + e.getMessage();
                    }
                    perJudge.add(judgeLabel(judge) + ": 调用失败（" + e.getMessage() + "）");
                    continue;
                }
                if (verdict == null || verdict.isBlank()) {
                    errors++;
                    unknown++;
                    if (firstError == null) {
                        firstError = "评委无输出——未发生判定";
                    }
                    perJudge.add(judgeLabel(judge) + ": 无输出");
                    continue;
                }
                verdict = verdict.trim();
                if (key != null) {
                    cache.put(key, verdict, ruleReason, judge.id());
                }
            }

            String upper = verdict.toUpperCase(Locale.ROOT);
            String note = judgeLabel(judge) + (fromCache ? "（缓存）: " : ": ") + verdict;
            if (upper.startsWith("UNKNOWN")) {
                unknown++;
                perJudge.add(note);
            } else if (upper.startsWith("PASS")) {
                pass++;
                perJudge.add(note);
            } else if (upper.startsWith("FAIL")) {
                fail++;
                perJudge.add(note);
            } else {
                // 输出不符合约定：该评委等于没给出裁决，按弃权计而不是硬判
                unknown++;
                perJudge.add(note + "（输出不符合 PASS/FAIL/UNKNOWN 约定，按弃权计）");
            }
            if (judges.size() == 1) {
                singleVerdict = verdict;
                singleFromCache = fromCache;
            }
        }

        // 全体故障 = 判定未发生：不是被测对象的失败，也不是评委团的可判结论
        if (errors == judges.size()) {
            return Judgment.degraded(kase.id(), kase.dimension(),
                    ruleReason + "（" + firstError + "）", null);
        }

        int n = judges.size();
        Verdict verdict = pass * 2 > n ? Verdict.PASS : fail * 2 > n ? Verdict.FAIL : Verdict.UNKNOWN;
        String suffix = n == 1
                ? "；评委判定" + (singleFromCache ? "（缓存命中）：" : "：") + singleVerdict
                : "；评委判定（" + n + " 位，多数决 PASS " + pass + " / FAIL " + fail + " / 弃权 " + unknown
                        + "）——" + String.join("；", perJudge);

        if (verdict == Verdict.UNKNOWN) {
            // 多数意见是「判定不该发生」（或平票）：如实记为不可判
            return Judgment.unknownByLlm(kase.id(), kase.dimension(), ruleReason + suffix, null);
        }
        boolean isPass = verdict == Verdict.PASS;
        // 规则通过但评委判不过：结果类别不能沿用规则那条（它会把"通过"的分类带过来），
        // 统一按"未出现排除项而判定不过"归入遗漏，保持分类口径只有一处定义
        Outcome finalOutcome = isPass ? Outcome.CORRECT : classify(kase, false, true, false);
        return new Judgment(kase.id(), kase.dimension(), verdict,
                isPass ? 1.0 : 0.0,
                ruleReason + suffix,
                Judgment.MODE_LLM, finalOutcome, causeOf(finalOutcome, kase.crossSession()));
    }

    private static String llmComplete(Judge judge, String rubric, String answer) {
        return judge.client().complete(promptOf(rubric, answer));
    }

    /** 报告里指名道姓地写出哪位评委给了什么结论；无指纹的评委（测试桩）用序号之外的一切可用信息 */
    private static String judgeLabel(Judge judge) {
        return judge.id() == null || judge.id().isBlank() ? "评委" : judge.id();
    }

    /** 判词是评委指纹的一部分：改判词必须改 {@code PROMPT_VERSION}，否则旧缓存会被错误命中 */
    private static String promptOf(String rubric, String answer) {
        return "你是严格的评测裁判。只输出 PASS、FAIL 或 UNKNOWN 之一开头，随后用一句话说明理由。\n"
                + "PASS=回答正确使用了应被记住的信息；FAIL=没有；UNKNOWN=根本没有作答，判定不应发生。\n\n"
                + "【判定标准】\n" + rubric
                + "\n\n【待判定回答】\n" + answer;
    }
}
