package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.evidence.Evidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 归因证据化：回答「报告里那些失败归因，有多少条是<b>看出来的</b>，有多少条是<b>推出来的</b>」。
 * <p>
 * 为什么要有这个口径：一条 FAIL 的归因（旧值未更新 / 边界泄漏 / 召回缺失……）听起来都像结论，
 * 但它们的证据强度完全不同。记忆可读时，「旧值仍在库里」是<b>观测事实</b>；
 * 记忆在被测对象内部读不到时，同一句归因只是<b>从回答文本做的推断</b>。
 * 把两者混在一个"归因分布"里报出去，读者无法判断该信到什么程度——
 * roadmap 验收项 #3 要的就是把这个比例量化出来。
 * <p>
 * <b>判据（每类归因各自要求一种独立证据，不接受"回答文本"本身当作证据）</b>：
 * <ul>
 *   <li>{@link FailureCause#STALE_REUSE} / {@link FailureCause#BOUNDARY_LEAKED} /
 *       {@link FailureCause#SIMILAR_CONFUSED}：该会话的记忆快照里<b>确实存在</b>那个不该出现的值；</li>
 *   <li>{@link FailureCause#RECALL_MISSING}：记忆快照里<b>确实存在</b>该记住的值（存了没召回，而非没存）；</li>
 *   <li>{@link FailureCause#PERSIST_MISSING}：该会话有记忆快照且<b>不含</b>该记住的值；</li>
 *   <li>{@link FailureCause#NEEDS_SEMANTIC_JUDGE}：用例的约束形态本身构成冲突——只约束排除项，
 *       或期望项与排除项在快照里<b>同时存在</b>。这类归因说的是「尺子分不开」，不是「它记错了」，
 *       所以证据来自用例定义而非记忆内容；</li>
 *   <li>{@link FailureCause#NO_RESPONSE}：该会话有<b>行动证据</b>（命令行 / 退出码 / 是否超时）；</li>
 *   <li>{@link FailureCause#BASE_OR_EXEC}：该用例有<b>神谕对照记录</b>（附上正确值重问过）；</li>
 *   <li>{@link FailureCause#NOT_OBSERVABLE}：能力声明里<b>确实缺</b>该用例要求的能力。</li>
 * </ul>
 * 记忆快照不可得（{@code MEMORY_INSPECTABLE} 未声明，或对话模式下记忆在被测对象内部）时，
 * 依赖快照的那几类一律记为<b>未证据化</b>并如实列出——这类归因此时就是推断，
 * 把它们算成"有证据"正是本口径要防的事。
 */
public final class AttributionEvidence {

    /** 目标线：低于此比例时报告要显式提示"归因以推断为主" */
    public static final double TARGET = 0.80;

    private AttributionEvidence() {
    }

    /**
     * @param attributed      带归因的判定条数（分母）
     * @param backed          其中由独立证据支撑的条数（分子）
     * @param ratio           backed / attributed；分母为 0 时记 1.0（没有归因就没有需要证据化的东西）
     * @param basis           本次可用的证据类别，说明比例是在什么条件下算出来的
     * @param unbacked        未证据化的归因逐条清单（用例 ID + 归因 + 缺什么证据）
     * @param byCause         按归因类别的 backed/attributed 细分
     */
    public record Result(int attributed,
                         int backed,
                         double ratio,
                         String basis,
                         List<String> unbacked,
                         Map<FailureCause, int[]> byCause) {

        public Result {
            unbacked = unbacked == null ? List.of() : List.copyOf(unbacked);
            // 不能用 Map.copyOf：Java 的不可变 Map 每次 JVM 启动会随机化迭代次序，
            // 报告里按归因类别逐行打印，次序一变 report.txt 的 sha256 就变——
            // 「同配置两次运行逐字节一致」这条会被这一行悄悄破坏
            byCause = byCause == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(byCause));
        }

        public boolean meetsTarget() {
            return ratio >= TARGET;
        }

        /** 报告里的一句话结论：达标就说达标，不达标要说清是"缺证据"而不是"归因错了" */
        public String headline() {
            String head = "归因证据化 " + backed + "/" + attributed
                    + " = " + String.format(Locale.ROOT, "%.1f%%", ratio * 100)
                    + "（目标 ≥" + (int) (TARGET * 100) + "%）";
            if (attributed == 0) {
                return head + "：本次没有失败归因，无需证据化";
            }
            return meetsTarget()
                    ? head + "：达标——归因以观测事实为主"
                    : head + "：未达标——下列归因是从回答文本推断的，不是观测事实，引用时需注明";
        }
    }

    /**
     * @param judgments      本轮判定
     * @param casesById      用例表（提供期望项 / 排除项 / 跨会话标记，用来对齐证据）
     * @param evidence       该智能体的全部证据
     * @param capabilities   能力声明（决定记忆快照是否可得）
     * @param oracleCaseIds  做过神谕对照的用例 ID
     */
    public static Result evaluate(List<Judgment> judgments,
                                  Map<String, MemoryCase> casesById,
                                  List<Evidence> evidence,
                                  Set<AgentCapability> capabilities,
                                  Set<String> oracleCaseIds) {
        Map<String, List<String>> memoryBySession = new LinkedHashMap<>();
        Map<String, Boolean> actionBySession = new LinkedHashMap<>();
        for (Evidence item : evidence == null ? List.<Evidence>of() : evidence) {
            String session = item.sessionId();
            if (Evidence.MEMORY.equals(item.type())) {
                memoryBySession.computeIfAbsent(session, k -> new ArrayList<>()).add(item.content());
            } else if (Evidence.ACTION.equals(item.type())) {
                actionBySession.put(session, Boolean.TRUE);
            }
        }

        boolean memoryInspectable = capabilities != null && capabilities.contains(AgentCapability.MEMORY_INSPECTABLE);
        boolean resettable = capabilities != null && capabilities.contains(AgentCapability.RESETS_CONTEXT);
        Set<String> oracles = oracleCaseIds == null ? Set.of() : oracleCaseIds;

        List<String> basisParts = new ArrayList<>();
        basisParts.add(memoryInspectable ? "记忆快照可读" : "记忆快照不可读（归因只能靠回答文本推断）");
        basisParts.add(actionBySession.isEmpty() ? "无行动证据" : "有行动证据");
        basisParts.add(oracles.isEmpty() ? "未做神谕对照" : "神谕对照 " + oracles.size() + " 条");

        int attributed = 0;
        int backed = 0;
        List<String> unbacked = new ArrayList<>();
        Map<FailureCause, int[]> byCause = new LinkedHashMap<>();

        for (Judgment judgment : judgments == null ? List.<Judgment>of() : judgments) {
            FailureCause cause = judgment.cause();
            if (cause == null) {
                continue;
            }
            attributed++;
            int[] tally = byCause.computeIfAbsent(cause, k -> new int[2]);
            tally[1]++;

            MemoryCase kase = casesById == null ? null : casesById.get(judgment.caseId());
            List<String> memory = memoryBySession.getOrDefault(sessionOf(judgment, kase), List.of());
            String missing = corroborate(cause, kase, memory, memoryInspectable,
                    Boolean.TRUE.equals(actionBySession.get(sessionOf(judgment, kase))),
                    oracles.contains(judgment.caseId()), resettable);
            if (missing == null) {
                backed++;
                tally[0]++;
            } else {
                unbacked.add(judgment.caseId() + " " + cause.label() + " ← 缺" + missing);
            }
        }

        double ratio = attributed == 0 ? 1.0 : (double) backed / attributed;
        return new Result(attributed, backed, ratio, String.join("；", basisParts), unbacked, byCause);
    }

    /**
     * @return {@code null} 表示已有独立证据支撑；否则返回"缺什么证据"的一句话
     */
    private static String corroborate(FailureCause cause,
                                      MemoryCase kase,
                                      List<String> memory,
                                      boolean memoryInspectable,
                                      boolean hasAction,
                                      boolean hasOracle,
                                      boolean resettable) {
        switch (cause) {
            case NO_RESPONSE:
                return hasAction ? null : "行动证据（命令行/退出码），无法区分"
                        + "「智能体没回答」与「评测端没把请求发出去」";
            case BASE_OR_EXEC:
                return hasOracle ? null : "神谕对照记录；没有重问过就不能断言失败与记忆无关";
            case NOT_OBSERVABLE:
                // 这类归因本身就是"能力声明里缺了这一项"，声明即证据
                return resettable ? "能力声明矛盾：既称可复位又判观测不足" : null;
            case STALE_REUSE:
            case BOUNDARY_LEAKED:
            case SIMILAR_CONFUSED:
                if (!memoryInspectable) {
                    return "记忆快照（不可读）：无法证明那个值是<b>存进了库</b>而不是随口生成";
                }
                String unwanted = kase == null ? null : kase.expectedNotContains();
                if (unwanted == null) {
                    return "用例未声明排除项，无从比对记忆快照";
                }
                return containsAny(memory, unwanted) ? null
                        : "记忆快照中的排除项「" + unwanted + "」：库里没有它，泄漏就不成立";
            case RECALL_MISSING:
                if (!memoryInspectable) {
                    return "记忆快照（不可读）：存了没召回 与 根本没存 在回答上无法区分";
                }
                String wanted = kase == null ? null : kase.expectedContains();
                if (wanted == null) {
                    return "用例未声明期望项，无从比对记忆快照";
                }
                return containsAny(memory, wanted) ? null
                        : "记忆快照中的期望项「" + wanted + "」：库里没有它，应归因为未持久化";
            case PERSIST_MISSING:
                if (!memoryInspectable) {
                    return "记忆快照（不可读）：无法证明写入链路确实没落盘";
                }
                String target = kase == null ? null : kase.expectedContains();
                if (target == null) {
                    return "用例未声明期望项，无从比对记忆快照";
                }
                return containsAny(memory, target)
                        ? "记忆快照里期望项「" + target + "」反而存在，与「未持久化」矛盾" : null;
            case NEEDS_SEMANTIC_JUDGE:
                // 这类归因说的不是被测对象的记忆，而是<b>尺子自己分不开</b>，
                // 因此证据来自用例的约束形态本身，不需要记忆快照：
                //   只约束排除项 ⇒ 排除词出现在「对照说明 / 否定语境」里与「真的泄漏」在关键词上同形；
                //   同时约束期望项与排除项 ⇒ 两者在库里并存时，规则层无法判断是正确对照还是混淆。
                String keep = kase == null ? null : kase.expectedContains();
                String drop = kase == null ? null : kase.expectedNotContains();
                if (drop == null) {
                    return "用例未声明排除项，构不成规则层冲突";
                }
                if (keep == null) {
                    return null;
                }
                if (!memoryInspectable) {
                    return "记忆快照（不可读）：无法确认期望项与排除项确实同时存在";
                }
                return containsAny(memory, keep) && containsAny(memory, drop) ? null
                        : "记忆快照中同时存在的期望项与排除项——只有一项时规则层本可判定，不该弃权";
            default:
                return "未定义判据的归因类别";
        }
    }

    private static String sessionOf(Judgment judgment, MemoryCase kase) {
        if (kase != null && kase.sessionId() != null) {
            return kase.sessionId();
        }
        return judgment.caseId();
    }

    /** 记忆内容是整句，期望项是句中的关键片段，因此按子串比对 */
    private static boolean containsAny(List<String> memory, String needle) {
        if (needle == null || needle.isBlank()) {
            return false;
        }
        String lower = needle.toLowerCase(Locale.ROOT);
        for (String entry : memory) {
            if (entry != null && entry.toLowerCase(Locale.ROOT).contains(lower)) {
                return true;
            }
        }
        return false;
    }
}
