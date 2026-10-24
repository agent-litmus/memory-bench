package io.github.agentlitmus.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.agentlitmus.dataset.GoldSet;
import io.github.agentlitmus.llm.JudgeCache;
import io.github.agentlitmus.stats.Agreement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 尺子自证：拿标注集去量<b>判定器自己</b>，输出一致率、Cohen's κ、弃权率与逐条分歧。
 * <p>
 * 评测工具最隐蔽的失效方式不是崩溃，而是<b>尺子歪了却仍在出数</b>：分数照常产出、报告照常好看，
 * 只是量出来的东西不再是记忆能力。标注集是唯一能发现这件事的手段——它把"我的判定准不准"
 * 从一句自我声明变成一个可重算的数字。
 * <p>
 * 因此本类刻意做成<b>不依赖被测对象</b>：输入只有标注集与判定器，输出只有统计。
 * 它可以进 CI（每次改判定规则都自动对账），也可以在发布前手动跑一次贴进材料。
 * <p>
 * 分两层量：规则层（离线、无 Key、必须可用）与评委层（有 Key 时才启用）。
 * 评委层缺席时如实标注"不可判"，<b>不拿规则层的数字冒充评委层的结论</b>——
 * 那正好是这套工具在所有别处都拒绝做的事。
 */
public final class RulerSelfTest {

    /** 规则层「开口即对率」下限：低于此值说明尺子刻度不准，不该拿去量别人 */
    public static final double RULE_AGREEMENT_FLOOR = 0.90;

    /** 评委层 κ 下限：与路线图验收口径一致（gold set 上 κ ≥ 0.85） */
    public static final double JUDGE_KAPPA_FLOOR = 0.85;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RulerSelfTest() {
    }

    /**
     * 一层（规则层或评委层）的自证结果。
     *
     * @param name      层名
     * @param judgeId   评委指纹；规则层为 {@code null}
     * @param agreement 一致性统计
     * @param items     逐条对照，用于把分歧定位回具体用例
     */
    public record Layer(String name, String judgeId, Agreement.Result agreement, List<Agreement.Item> items) {
    }

    /**
     * 一次完整自证。
     *
     * @param gold              标注集
     * @param validationIssues  标注集与当前题集的对账问题；非空即标注已失效，κ 无意义
     * @param rulesVersion      判定口径版本
     * @param rule              规则层结果
     * @param judge             评委层结果；未启用时为 {@code null}
     * @param judgeUnavailable  评委层未启用的原因（启用时为 {@code null}）
     * @param cache             评委缓存统计行；未启用时为 {@code null}
     */
    public record Result(GoldSet gold,
                         List<String> validationIssues,
                         String rulesVersion,
                         Layer rule,
                         Layer judge,
                         String judgeUnavailable,
                         String cache) {

        public boolean valid() {
            return validationIssues.isEmpty();
        }

        /** 门禁：哪些指标没达标（标注失效也算不达标——失效的标注集量不出任何东西） */
        public List<String> gateFailures() {
            List<String> failures = new ArrayList<>(validationIssues);
            if (rule != null) {
                double rate = rule.agreement().spokenRate();
                if (rule.agreement().spoken() == 0) {
                    failures.add("规则层在标注集上没有给出任何确定结论，开口即对率无定义");
                } else if (Double.isNaN(rate) || rate < RULE_AGREEMENT_FLOOR) {
                    failures.add(String.format(Locale.ROOT,
                            "规则层开口即对率 %.1f%% 低于下限 %.0f%%——尺子刻度不准，先修判定规则再量别人",
                            rate * 100, RULE_AGREEMENT_FLOOR * 100));
                }
            }
            if (judge != null) {
                double kappa = judge.agreement().kappa();
                if (Double.isNaN(kappa)) {
                    failures.add("评委层 κ 无定义（一侧只输出单一类别），无法据此判定尺子是否达标");
                } else if (kappa < JUDGE_KAPPA_FLOOR) {
                    failures.add(String.format(Locale.ROOT,
                            "评委层 κ %.3f 低于下限 %.2f——评委判词或模型需要调整", kappa, JUDGE_KAPPA_FLOOR));
                }
            }
            return failures;
        }

        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("========== 尺子自证（标注集对账） ==========\n");
            sb.append("标注集       : ").append(gold.entries().size()).append(" 条  版本 ")
                    .append(gold.version()).append("  sha256=").append(shortSha(gold.sha256())).append('\n');
            sb.append("标注者       : ").append(gold.annotator()).append("（").append(gold.annotatedAt()).append("）\n");
            sb.append("判定口径     : ").append(rulesVersion).append('\n');
            sb.append("标签分布     : ").append(gold.labelCounts()).append('\n');
            sb.append("难点覆盖     : ").append(gold.focusCounts()).append('\n');
            if (gold.honestyNote() != null && !gold.honestyNote().isBlank()) {
                sb.append("诚信声明     : ").append(oneLine(gold.honestyNote())).append('\n');
            }
            sb.append('\n');

            if (!validationIssues.isEmpty()) {
                sb.append("⚠ 标注集与当前题集不一致（").append(validationIssues.size()).append(" 项）——")
                        .append("此时 κ 与一致率都没有意义，请先重新人工确认这些条目：\n");
                validationIssues.forEach(i -> sb.append("  - ").append(i).append('\n'));
                sb.append('\n');
            }
            if (rule != null) {
                sb.append(rule.agreement().toText("规则层（离线，无 API Key）")).append('\n');
            }
            if (judge != null) {
                sb.append(judge.agreement().toText("评委层（" + judge.judgeId() + "）")).append('\n');
                if (cache != null) {
                    sb.append(cache).append('\n').append('\n');
                }
            } else if (judgeUnavailable != null) {
                sb.append("── 尺子自证: 评委层 ──\n");
                sb.append("未启用：").append(judgeUnavailable).append('\n');
                sb.append(String.format(Locale.ROOT,
                        "因此 κ ≥ %.2f 这项门禁本次判为「不可判」，不计入结论——"
                                + "规则层的 κ 含弃权，不能替代评委层的 κ。%n", JUDGE_KAPPA_FLOOR));
                sb.append('\n');
            }

            List<String> failures = gateFailures();
            if (failures.isEmpty()) {
                sb.append("结论         : 达标\n");
            } else {
                sb.append("结论         : 不达标（").append(failures.size()).append(" 项）\n");
                failures.forEach(f -> sb.append("  - ").append(f).append('\n'));
            }
            sb.append("==========================================");
            return sb.toString();
        }

        public String toJson() {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("rulesVersion", rulesVersion);
            root.put("goldVersion", gold.version());
            root.put("goldSha256", gold.sha256());
            root.put("goldEntries", gold.entries().size());
            root.put("annotator", gold.annotator());
            root.put("honestyNote", gold.honestyNote());
            ArrayNode issues = root.putArray("validationIssues");
            validationIssues.forEach(issues::add);
            if (rule != null) {
                root.set("ruleLayer", layerJson(rule));
            }
            if (judge != null) {
                root.set("judgeLayer", layerJson(judge));
                root.put("judgeCache", cache);
            } else {
                root.put("judgeLayerUnavailable", judgeUnavailable);
                root.put("judgeKappaGate", "不可判（评委层未启用）");
            }
            ArrayNode gates = root.putArray("gateFailures");
            gateFailures().forEach(gates::add);
            root.put("pass", gateFailures().isEmpty());
            try {
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            } catch (Exception e) {
                throw new IllegalStateException("selftest JSON 序列化失败", e);
            }
        }

        private static ObjectNode layerJson(Layer layer) {
            Agreement.Result a = layer.agreement();
            ObjectNode node = MAPPER.createObjectNode();
            node.put("name", layer.name());
            node.put("judgeId", layer.judgeId());
            node.put("total", a.total());
            node.put("agreed", a.agreed());
            node.put("agreement", round(a.agreement()));
            node.put("kappa", a.kappaDefined() ? round(a.kappa()) : null);
            node.put("kappaBand", Agreement.kappaBand(a.kappa()));
            node.put("spoken", a.spoken());
            node.put("spokenCorrect", a.spokenCorrect());
            node.put("spokenRate", Double.isNaN(a.spokenRate()) ? null : round(a.spokenRate()));
            node.put("abstained", a.abstained());
            node.put("abstainRate", round(a.abstainRate()));
            node.put("blindSpots", a.blindSpots());
            node.set("confusion", MAPPER.valueToTree(Agreement.matrixOf(layer.items())));
            ArrayNode dis = node.putArray("disagreements");
            for (Agreement.Disagreement d : a.disagreements()) {
                ObjectNode item = dis.addObject();
                item.put("key", d.key());
                item.put("kind", d.kind());
                item.put("predicted", d.predicted().name());
                item.put("gold", d.gold().name());
                item.put("detail", d.detail());
            }
            return node;
        }

        private static Double round(double value) {
            return Double.isNaN(value) ? null : Math.round(value * 10000) / 10000.0;
        }
    }

    /**
     * 对一层判定器做自证。
     *
     * @param gold   标注集
     * @param byId   用例 ID → 当前题集里的用例定义
     * @param judge  被测的判定器（规则层传 {@code new CaseJudge()}，评委层传带 LlmClient 的实例）
     * @param name   层名，写进报告
     */
    public static Layer evaluate(GoldSet gold, Map<String, MemoryCase> byId, CaseJudge judge, String name) {
        List<Agreement.Item> items = new ArrayList<>();
        for (GoldSet.GoldEntry entry : gold.entries()) {
            MemoryCase kase = byId.get(entry.caseId());
            if (kase == null) {
                // 标注指向的题目已不存在：这条对照不成立，不能用它给尺子打分
                continue;
            }
            Judgment judgment = judge.judge(kase, entry.answer());
            items.add(new Agreement.Item(
                    entry.caseId() + "@" + (entry.agent() == null ? "?" : entry.agent()),
                    judgment.verdict(), entry.label(),
                    "判定器: " + oneLine(judgment.reason()) + " ｜ 人工: " + oneLine(entry.rationale())));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("标注集里没有任何条目能对到当前题集，无法自证");
        }
        return new Layer(name, judge.judgeFingerprint(), Agreement.of(items), List.copyOf(items));
    }

    /** 完整自证：规则层必跑，评委层给了 {@code judgeRuler} 才跑 */
    public static Result run(GoldSet gold, List<MemoryCase> pool, CaseJudge judgeRuler,
                             String judgeUnavailable, JudgeCache cache) {
        Map<String, MemoryCase> byId = new LinkedHashMap<>();
        for (MemoryCase kase : pool) {
            byId.putIfAbsent(kase.id(), kase);
        }
        List<String> issues = gold.validate(byId);
        Layer rule = evaluate(gold, byId, new CaseJudge(), "rule");
        Layer judge = judgeRuler == null ? null : evaluate(gold, byId, judgeRuler, "judge");
        return new Result(gold, issues, CaseJudge.RULES_VERSION, rule, judge,
                judgeRuler == null ? judgeUnavailable : null,
                cache == null ? null : cache.statsLine());
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 12 ? sha : sha.substring(0, 12);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
