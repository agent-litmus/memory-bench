package io.github.agentlitmus.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.agentlitmus.report.RunManifest;
import io.github.agentlitmus.stats.PairedCompare;
import io.github.agentlitmus.util.Digests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 两次运行的逐题回归比较：把「新版本掉了 2 个点」变成<b>逐条可点开</b>的回归/改进/新增不可判清单，
 * 并在真出问题时以非零退出码结束，让它能直接挂在 CI 上。
 * <p>
 * 与 {@link PairedCompare} 的分工：那是<b>同一轮</b>里两款智能体的横向比较，这是<b>跨轮</b>的纵向守门。
 * 两者共用同一套配对与 bootstrap 实现，所以「显著」在两个方向上是一个意思。
 * <p>
 * 三条设计约束，都是为了让门禁<b>不冤枉人也不放过人</b>：
 * <ol>
 *   <li><b>口径必须相同才允许比较</b>。两次运行的判定口径不一致时，差值同时包含「尺子变了」和
 *       「东西变了」，此时任何回归结论都是假的——直接列为红线并要求先 {@code regrade}；</li>
 *   <li><b>题集必须相同</b>。题集哈希不同说明比的是两套题，同上；</li>
 *   <li><b>逐题回归不等于红线</b>。真实智能体本身有抖动，单题翻面是常态；只有
 *       <b>配对差值显著为负</b>或<b>覆盖率下降</b>才判红线，其余照实列出但不阻断。</li>
 * </ol>
 */
public final class RunCompare {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunCompare() {
    }

    /** 一条裁决变化的性质 */
    public enum Kind {
        /** 已判定 通过 → 未过：能力回归 */
        REGRESSION("回归"),
        /** 已判定 未过 → 通过：能力改进 */
        IMPROVEMENT("改进"),
        /** 已判定 → 不可判：覆盖率下降（可能是被测退化，也可能是尺子弃权变多） */
        LOST_JUDGMENT("新增不可判"),
        /** 不可判 → 已判定：覆盖率回升 */
        GAINED_JUDGMENT("不可判消解");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 一条逐题变化 */
    public record CaseChange(String caseId, Verdict before, Verdict after) {

        public Kind kind() {
            if (before.judged() && !after.judged()) {
                return Kind.LOST_JUDGMENT;
            }
            if (!before.judged() && after.judged()) {
                return Kind.GAINED_JUDGMENT;
            }
            return before == Verdict.PASS ? Kind.REGRESSION : Kind.IMPROVEMENT;
        }

        public String toLine() {
            return String.format("%-16s %s → %s   [%s]", caseId, before.label(), after.label(), kind().label());
        }
    }

    /**
     * 一款被测对象在两次运行之间的比较。
     *
     * @param shared      两侧都出现过的题数
     * @param onlyBefore  只在 A 侧出现（B 侧无此题记录）
     * @param onlyAfter   只在 B 侧出现
     * @param paired      两侧都已判定的题上的配对检验
     */
    public record AgentCompare(String agent, Map<String, Verdict> before, Map<String, Verdict> after,
                               int shared, int onlyBefore, int onlyAfter,
                               List<CaseChange> changes, PairedCompare.Result paired) {

        public int countOf(Kind kind) {
            return (int) changes.stream().filter(c -> c.kind() == kind).count();
        }

        public double coverageBefore() {
            return coverage(before);
        }

        public double coverageAfter() {
            return coverage(after);
        }

        private static double coverage(Map<String, Verdict> map) {
            if (map.isEmpty()) {
                return Double.NaN;
            }
            return map.values().stream().filter(Verdict::judged).count() / (double) map.size();
        }
    }

    /**
     * 一次比较的结论。
     *
     * @param redLines 阻断项：非空时 {@code compare} 以非零退出码结束
     * @param notes    提示项：照实说明，但不阻断
     */
    public record Result(Path dirA, Path dirB, String rulesA, String rulesB,
                         String caseSetA, String caseSetB,
                         List<AgentCompare> agents, List<String> redLines, List<String> notes) {

        public boolean blocked() {
            return !redLines.isEmpty();
        }

        public int regressions() {
            return agents.stream().mapToInt(a -> a.countOf(Kind.REGRESSION)).sum();
        }

        public int improvements() {
            return agents.stream().mapToInt(a -> a.countOf(Kind.IMPROVEMENT)).sum();
        }

        public int lostJudgments() {
            return agents.stream().mapToInt(a -> a.countOf(Kind.LOST_JUDGMENT)).sum();
        }

        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("========== 跨运行回归比较 ==========\n");
            sb.append("A（基线）  : ").append(dirA.toAbsolutePath()).append('\n');
            sb.append("B（本次）  : ").append(dirB.toAbsolutePath()).append('\n');
            sb.append("判定口径   : ").append(rulesA).append(" → ").append(rulesB)
                    .append(sameRules() ? "（相同）" : "（不同！）").append('\n');
            sb.append("题集       : ").append(shortHash(caseSetA)).append(" → ").append(shortHash(caseSetB))
                    .append(caseSetA != null && caseSetA.equals(caseSetB) ? "（相同）" : "（不同！）").append('\n');
            sb.append('\n');
            for (AgentCompare a : agents) {
                sb.append("── ").append(a.agent()).append(" ──\n");
                sb.append(String.format(Locale.ROOT,
                        "有效分     : %.1f%% → %.1f%%    覆盖率 : %.1f%% → %.1f%%%n",
                        a.paired().rateA() * 100, a.paired().rateB() * 100,
                        nanSafe(a.coverageBefore()), nanSafe(a.coverageAfter())));
                sb.append(String.format(Locale.ROOT,
                        "配对题数   : %d    回归 %d / 改进 %d / 新增不可判 %d / 消解 %d%n",
                        a.paired().pairs(), a.countOf(Kind.REGRESSION), a.countOf(Kind.IMPROVEMENT),
                        a.countOf(Kind.LOST_JUDGMENT), a.countOf(Kind.GAINED_JUDGMENT)));
                sb.append(String.format(Locale.ROOT,
                        "差值(B−A)  : %+.1f 个百分点  95%%CI [%+.1f, %+.1f]  %s%n",
                        signed(-a.paired().delta() * 100), signed(-a.paired().ciHigh() * 100),
                        signed(-a.paired().ciLow() * 100),
                        a.paired().significant() ? "显著" : "不显著"));
                if (a.onlyBefore() > 0 || a.onlyAfter() > 0) {
                    sb.append(String.format(Locale.ROOT, "题集差异   : 仅 A 有 %d 题 / 仅 B 有 %d 题%n",
                            a.onlyBefore(), a.onlyAfter()));
                }
                if (!a.changes().isEmpty()) {
                    sb.append("变化明细:\n");
                    a.changes().forEach(c -> sb.append("  ").append(c.toLine()).append('\n'));
                }
                sb.append('\n');
            }
            if (!notes.isEmpty()) {
                sb.append("提示:\n");
                notes.forEach(n -> sb.append("  - ").append(n).append('\n'));
                sb.append('\n');
            }
            sb.append("结论       : ");
            if (blocked()) {
                sb.append("不通过（").append(redLines.size()).append(" 项红线）\n");
                redLines.forEach(r -> sb.append("  ✗ ").append(r).append('\n'));
            } else {
                sb.append("通过——未见显著回归，无新增不可判\n");
            }
            sb.append("====================================");
            return sb.toString();
        }

        public String toJson() {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("dirA", dirA.toAbsolutePath().toString());
            root.put("dirB", dirB.toAbsolutePath().toString());
            root.put("rulesA", rulesA);
            root.put("rulesB", rulesB);
            root.put("sameRules", sameRules());
            root.put("caseSetA", caseSetA);
            root.put("caseSetB", caseSetB);
            root.put("blocked", blocked());
            root.put("regressions", regressions());
            root.put("improvements", improvements());
            root.put("lostJudgments", lostJudgments());
            ArrayNode red = root.putArray("redLines");
            redLines.forEach(red::add);
            ArrayNode noteArray = root.putArray("notes");
            notes.forEach(noteArray::add);
            ArrayNode agentArray = root.putArray("agents");
            for (AgentCompare a : agents) {
                ObjectNode node = agentArray.addObject();
                node.put("agent", a.agent());
                node.put("shared", a.shared());
                node.put("onlyA", a.onlyBefore());
                node.put("onlyB", a.onlyAfter());
                node.put("pairs", a.paired().pairs());
                node.put("regressions", a.countOf(Kind.REGRESSION));
                node.put("improvements", a.countOf(Kind.IMPROVEMENT));
                node.put("lostJudgments", a.countOf(Kind.LOST_JUDGMENT));
                node.put("gainedJudgments", a.countOf(Kind.GAINED_JUDGMENT));
                node.put("deltaBMinusA", round(-a.paired().delta()));
                node.put("ciLow", round(-a.paired().ciHigh()));
                node.put("ciHigh", round(-a.paired().ciLow()));                node.put("significant", a.paired().significant());
                ArrayNode changes = node.putArray("changes");
                for (CaseChange c : a.changes()) {
                    ObjectNode item = changes.addObject();
                    item.put("caseId", c.caseId());
                    item.put("before", c.before().name());
                    item.put("after", c.after().name());
                    item.put("kind", c.kind().name());
                }
            }
            try {
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            } catch (Exception e) {
                throw new IllegalStateException("比较结果序列化失败", e);
            }
        }

        private boolean sameRules() {
            return rulesA != null && rulesA.equals(rulesB);
        }

        private static double nanSafe(double v) {
            return Double.isNaN(v) ? 0 : v * 100;
        }

        private static double round(double v) {
            return signed(Math.round(v * 10000) / 10000.0);
        }

        private static String shortHash(String sha) {
            return sha == null ? "无契约" : Digests.shortForm(sha);
        }
    }

    /**
     * 比较两个运行目录。两侧的裁决都从各自的证据流里<b>照抄</b>（见
     * {@link Regrade#readVerdicts}），不重判——重判是 {@code regrade} 的职责。
     */
    public static Result of(Path dirA, Path dirB) throws IOException {
        List<String> redLines = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        RunManifest manifestA = readManifest(dirA, "A", redLines);
        RunManifest manifestB = readManifest(dirB, "B", redLines);
        String rulesA = manifestA == null ? "无契约" : manifestA.rulesVersion();
        String rulesB = manifestB == null ? "无契约" : manifestB.rulesVersion();
        String caseSetA = manifestA == null ? null : manifestA.caseSetSha256();
        String caseSetB = manifestB == null ? null : manifestB.caseSetSha256();

        if (manifestA != null && manifestB != null) {
            if (!rulesA.equals(rulesB)) {
                redLines.add("判定口径不同（" + rulesA + " vs " + rulesB + "）：差值同时包含「尺子变了」与"
                        + "「东西变了」，任何回归结论都不成立。请先对较早的一侧执行 litmus regrade 把口径对齐");
            }
            if (!caseSetA.equals(caseSetB)) {
                redLines.add("题集不同（" + Digests.shortForm(caseSetA) + " vs " + Digests.shortForm(caseSetB)
                        + "）：比的不是同一套题");
            }
            if (!String.valueOf(manifestA.suite()).equals(String.valueOf(manifestB.suite()))) {
                notes.add("题集档位不同（" + manifestA.suite() + " vs " + manifestB.suite() + "）");
            }
            if (manifestA.params().repeat() != manifestB.params().repeat()) {
                notes.add("重复次数不同（" + manifestA.params().repeat() + " vs " + manifestB.params().repeat()
                        + "）；本比较只用每次运行的第一轮判定");
            }
        }

        Map<String, Map<String, Verdict>> tableA = Regrade.readVerdicts(dirA);
        Map<String, Map<String, Verdict>> tableB = Regrade.readVerdicts(dirB);
        if (Files.isDirectory(dirA.resolve("oracle")) || Files.isDirectory(dirB.resolve("oracle"))) {
            notes.add("有一侧启用过神谕对照（目录里有 oracle/）：本比较逐题用的是证据流里的裁决，"
                    + "那是神谕调整<b>之前</b>的读数；两侧口径一致所以可比，但不会与各自 report.txt 的总分完全对上");
        }
        if (tableA.isEmpty() || tableB.isEmpty()) {
            redLines.add("有一侧读不出逐题裁决（A " + tableA.size() + " 个对象 / B " + tableB.size()
                    + " 个对象）：证据流缺失，或是角色标注之前的旧格式");
        }

        Set<String> agents = new LinkedHashSet<>(tableA.keySet());
        agents.addAll(tableB.keySet());
        List<AgentCompare> compares = new ArrayList<>();
        for (String agent : new TreeSet<>(agents)) {
            Map<String, Verdict> before = tableA.getOrDefault(agent, Map.of());
            Map<String, Verdict> after = tableB.getOrDefault(agent, Map.of());
            if (before.isEmpty() || after.isEmpty()) {
                notes.add("【" + agent + "】只在" + (before.isEmpty() ? " B " : " A ") + "侧存在，无可比数据");
                continue;
            }
            compares.add(compareAgent(agent, before, after));
        }

        for (AgentCompare a : compares) {
            int lost = a.countOf(Kind.LOST_JUDGMENT);
            if (lost > 0) {
                redLines.add("【" + a.agent() + "】新增不可判 " + lost + " 条：覆盖率由 "
                        + pct(a.coverageBefore()) + " 降到 " + pct(a.coverageAfter())
                        + "。已判定的题变少意味着分数口径被移动过，必须先查清是尺子弃权变多还是被测退化");
            }
            if (a.paired().pairs() > 0 && a.paired().significant() && a.paired().delta() > 0) {
                // delta 是 A−B，为正即 B 更低
                redLines.add("【" + a.agent() + "】显著回归：有效分 " + pct(a.paired().rateA()) + " → "
                        + pct(a.paired().rateB()) + "，配对差值 95%CI ["
                        + pct(-a.paired().ciHigh()) + ", " + pct(-a.paired().ciLow()) + "] 不含 0");
            } else if (a.countOf(Kind.REGRESSION) > 0) {
                notes.add("【" + a.agent() + "】有 " + a.countOf(Kind.REGRESSION)
                        + " 条逐题回归，但配对差值未达显著——可能是抖动，不阻断；明细见报告");
            }
            if (a.onlyBefore() > 0 || a.onlyAfter() > 0) {
                notes.add("【" + a.agent() + "】两侧题目集合不完全重合（仅 A " + a.onlyBefore()
                        + " 题 / 仅 B " + a.onlyAfter() + " 题）；配对只在共同题上进行");
            }
        }

        return new Result(dirA, dirB, rulesA, rulesB, caseSetA, caseSetB, compares, redLines, notes);
    }

    private static AgentCompare compareAgent(String agent, Map<String, Verdict> before,
                                             Map<String, Verdict> after) {
        Set<String> universe = new LinkedHashSet<>(before.keySet());
        universe.addAll(after.keySet());
        List<CaseChange> changes = new ArrayList<>();
        int shared = 0;
        int onlyBefore = 0;
        int onlyAfter = 0;
        for (String caseId : new TreeSet<>(universe)) {
            Verdict a = before.get(caseId);
            Verdict b = after.get(caseId);
            if (a == null) {
                onlyAfter++;
                continue;
            }
            if (b == null) {
                onlyBefore++;
                continue;
            }
            shared++;
            if (a != b) {
                changes.add(new CaseChange(caseId, a, b));
            }
        }
        return new AgentCompare(agent, before, after, shared, onlyBefore, onlyAfter, changes,
                PairedCompare.ofVerdicts(before, after));
    }

    private static RunManifest readManifest(Path dir, String side, List<String> redLines) {
        Path file = dir.resolve(RunManifest.FILE_NAME);
        if (!Files.exists(file)) {
            redLines.add(side + " 侧（" + dir.toAbsolutePath() + "）没有 " + RunManifest.FILE_NAME
                    + "：无法确认这批数字出自哪个口径与哪套题，比较前提不成立");
            return null;
        }
        try {
            return RunManifest.read(file);
        } catch (RuntimeException e) {
            redLines.add(side + " 侧运行契约读不出来：" + e.getMessage());
            return null;
        }
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    /** 负零没有方向也没有含义，只会让读者以为「差值是负的」 */
    private static double signed(double v) {
        return v == 0 ? 0 : v;
    }
}
