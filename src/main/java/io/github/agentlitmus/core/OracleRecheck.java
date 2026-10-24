package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.RunnerMode;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.report.BenchmarkReport;

import java.util.ArrayList;
import java.util.List;

/**
 * 神谕对照（oracle recheck）：把<b>「记不住」与「答不对」分开</b>。
 * <p>
 * 一条 FAIL 有两种完全不同的成因：它没记住（记忆缺陷），或者它记住了但理解/执行环节出了错
 * （基座缺陷）。只看回答分不开——两者都表现为「答案里没有正确事实」。
 * <p>
 * 做法：<b>只针对失败用例</b>，在全新会话里把<b>正确事实直接写进提问</b>再问一次同一道题。
 * <ul>
 *   <li>附上就能答对（{@code ORACLE_RECOVERED}）→ 它缺的只是那条信息，失败<b>归因于记忆</b>，原判定保留；</li>
 *   <li>附上仍然答错 → 与记忆无关，原判定<b>改为不可判</b>并标注
 *       {@link FailureCause#BASE_OR_EXEC}：把「模型本身不行」记成「记忆不行」，
 *       改进方向会整个跑偏。</li>
 * </ul>
 * 这一步会<b>额外提问</b>（真实智能体上要花 token），因此默认关闭、由 {@code --with-oracle} 显式开启，
 * 并且只处理失败用例、带上限。
 */
public final class OracleRecheck {

    /** 判定方式标记：与常规规则判定区分开，报告里能看出这条结论来自神谕对照 */
    public static final String MODE_ORACLE = "oracle-recheck";

    /** 默认最多重问多少条失败用例——真实智能体上重问是有成本的，必须有上限 */
    public static final int DEFAULT_MAX_CASES = 40;

    /**
     * 神谕对照只对<b>记忆在被测对象内部、评测方读不到</b>的对象成立（对话模式）。
     * <p>
     * 记忆由框架注入的模式下，每条记忆都是评测方自己写进去、逐条可读的，
     * 失败卡在「没落盘」还是「落盘了没答出来」已经是观测事实。此时重问会换一个新会话 id，
     * 注入的记忆随之整个落空，回答必然变成「我不掌握相关信息」——
     * 每条记忆缺陷都会被算成基座缺陷，把结论正好洗反。
     */
    public static boolean applicableTo(AgentUnderTest agent) {
        return agent != null && agent.mode() == RunnerMode.DIALOGUE;
    }

    /** 跳过原因要写进报告，不能静默少一段——否则读者会以为这个对象没有失败用例 */
    public static String skipReason(AgentUnderTest agent) {
        return "神谕对照已跳过（模式 " + agent.mode() + "）：该对象的记忆由评测框架注入且逐条可读，"
                + "失败归因直接来自记忆内容与召回结果，不需要也不应再用重问推断。";
    }

    /**
     * @param caseId     用例 ID
     * @param dimension  维度
     * @param question   附上正确事实后的提问原文（即神谕输入）
     * @param oracleFact 注入的正确事实
     * @param verdict    重问后的判定
     * @param answer     重问得到的回答
     * @param reason     判定理由
     */
    public record OracleCheck(String caseId,
                              Dimension dimension,
                              String oracleFact,
                              String question,
                              Verdict verdict,
                              String answer,
                              String reason) {

        /** 附上正确事实就能答对 ⇒ 失败确实出在记忆环节 */
        public boolean recovered() {
            return verdict == Verdict.PASS;
        }
    }

    /**
     * @param report         调整后的报告（非记忆成因的失败已改为不可判并标注归因）
     * @param checks         逐条重问记录
     * @param skippedByCap   因超出上限而未重问的失败用例数
     */
    public record Result(BenchmarkReport report, List<OracleCheck> checks, int skippedByCap) {

        public long recoveredCount() {
            return checks.stream().filter(OracleCheck::recovered).count();
        }

        public long baseOrExecCount() {
            return checks.size() - recoveredCount();
        }

        /** 一段可直接放进报告的结论：失败里有多少是记忆问题、多少根本不是 */
        public String toText() {
            if (checks.isEmpty()) {
                return "神谕对照：无失败用例需要重问（或无可用期望值）。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("========== 神谕对照（失败用例附正确答案重问）==========\n");
            sb.append("重问 ").append(checks.size()).append(" 条：记忆成因 ").append(recoveredCount())
                    .append(" 条 ｜ 非记忆成因（改判不可判）").append(baseOrExecCount()).append(" 条");
            if (skippedByCap > 0) {
                sb.append("（另有 ").append(skippedByCap).append(" 条因超出上限未重问，仍按原判定统计）");
            }
            sb.append('\n');
            for (OracleCheck check : checks) {
                sb.append("  ").append(check.recovered() ? "[记忆成因]" : "[非记忆成因 ]")
                        .append(" ").append(check.caseId())
                        .append("  ").append(check.dimension().label())
                        .append("  期望=").append(check.oracleFact())
                        .append("  重问回答=").append(flat(check.answer())).append('\n');
            }
            return sb.toString();
        }

        private static String flat(String text) {
            if (text == null || text.isBlank()) {
                return "（空回答）";
            }
            String value = text.replaceAll("\\s+", " ").trim();
            return value.length() <= 100 ? value : value.substring(0, 100) + "…";
        }
    }

    private final Answerer agent;
    private final CaseJudge judge;
    private final int maxCases;
    private final EvidenceCollector collector;

    public OracleRecheck(Answerer agent, CaseJudge judge) {
        this(agent, judge, DEFAULT_MAX_CASES, null);
    }

    public OracleRecheck(Answerer agent, CaseJudge judge, int maxCases, EvidenceCollector collector) {
        if (agent == null) {
            throw new IllegalArgumentException("神谕对照需要可对话的被测对象");
        }
        this.agent = agent;
        this.judge = judge == null ? new CaseJudge() : judge;
        this.maxCases = maxCases <= 0 ? DEFAULT_MAX_CASES : maxCases;
        this.collector = collector;
    }

    /**
     * 对报告里的失败用例做神谕重问。
     * <p>
     * 只处理<b>有正向期望值</b>（{@code expectedContains}）的用例：边界识别类用例的期望是「别说某句话」，
     * 没有可注入的正确事实，硬做只会得到一个自证结论。
     */
    public Result run(List<MemoryCase> cases, BenchmarkReport report) {
        List<OracleCheck> checks = new ArrayList<>();
        List<Judgment> adjusted = new ArrayList<>(report.judgments());
        for (Judgment judgment : report.judgments()) {
            if (judgment.verdict() != Verdict.FAIL) {
                continue;
            }
            if (checks.size() >= maxCases) {
                continue;
            }
            MemoryCase kase = cases.stream()
                    .filter(c -> c.id().equals(judgment.caseId()))
                    .findFirst()
                    .orElse(null);
            if (kase == null || kase.expectedContains() == null || kase.expectedContains().isBlank()) {
                continue;
            }
            String fact = kase.expectedContains();
            String oracleQuestion = "已知这条信息为真：" + fact
                    + "。请依据它回答：" + kase.question();
            // 全新会话：神谕输入必须与它自己的记忆隔离，否则重问仍会被旧记忆污染
            String sessionId = kase.sessionId() + "-oracle";
            String answer;
            try {
                answer = agent.answer(sessionId, oracleQuestion);
            } catch (Exception e) {
                answer = "";
            }
            if (collector != null) {
                collector.collect(Evidence.dialogue(sessionId, oracleQuestion, Evidence.ROLE_QUESTION));
                collector.collect(Evidence.dialogue(sessionId, answer == null ? "" : answer, Evidence.ROLE_ANSWER));
            }
            Judgment recheck = judge.judge(
                    withQuestion(kase, oracleQuestion), answer);
            checks.add(new OracleCheck(kase.id(), kase.dimension(), fact, oracleQuestion,
                    recheck.verdict(), answer, recheck.reason()));
            if (recheck.verdict() != Verdict.PASS && recheck.verdict().judged()) {
                // 附上正确事实仍答错：不是记忆问题，移出记忆能力分母
                int index = adjusted.indexOf(judgment);
                adjusted.set(index, new Judgment(judgment.caseId(), judgment.dimension(),
                        Verdict.UNKNOWN, 0.0,
                        "神谕对照：附上正确事实「" + fact + "」后仍答错——失败在理解/执行环节，与长期记忆无关"
                                + "（原判定：" + judgment.reason() + "）",
                        MODE_ORACLE, null, FailureCause.BASE_OR_EXEC));
            }
        }
        int skipped = skippedFailures(cases, report, checks.size());
        return new Result(new BenchmarkReport(adjusted), List.copyOf(checks), skipped);
    }

    /** 超出上限而未重问的失败用例数（让「没重问」在报告里可见，而不是静默少算） */
    private int skippedFailures(List<MemoryCase> cases, BenchmarkReport report, int rechecked) {
        if (rechecked < maxCases) {
            return 0;
        }
        long failuresWithFact = report.judgments().stream()
                .filter(j -> j.verdict() == Verdict.FAIL)
                .filter(j -> cases.stream().anyMatch(c -> c.id().equals(j.caseId())
                        && c.expectedContains() != null && !c.expectedContains().isBlank()))
                .count();
        return (int) Math.max(0, failuresWithFact - rechecked);
    }

    /** 判定仍用原题的约束，只把提问换成神谕版本——否则「包含期望值」会因为原句里就有期望值而自证 */
    private static MemoryCase withQuestion(MemoryCase kase, String oracleQuestion) {
        return new MemoryCase(kase.id(), kase.dimension(), kase.sessionId(), kase.seeds(),
                kase.seedsRetainable(), false, null, oracleQuestion,
                kase.expectedContains(), kase.expectedNotContains(), false, null);
    }
}
