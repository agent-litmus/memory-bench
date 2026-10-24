package io.github.agentlitmus.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.report.RunManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 离线重评：拿一次<b>已经跑完</b>的运行目录，用<b>当前</b>的判定口径把每条回答重判一遍，
 * 逐条列出裁决变化，并且<b>绝不重跑被测对象</b>。
 * <p>
 * 为什么必须有这一步：判定口径是会改的（归一化、弃权规则、归因分类），而真实智能体的一轮评测
 * 要几十分钟和真金白银的额度。没有离线重评，改一次口径就只有两个选择——
 * 要么重跑（贵，而且被测对象自己也可能已经变了，于是"口径变化"和"能力变化"混在一起分不清），
 * 要么沿用旧数字（等于口径版本号是装饰）。有了它，同一批证据在新旧口径下的差值
 * <b>只能</b>来自口径本身，这才是"换了尺子"和"东西变了"的分界线。
 * <p>
 * 三条硬约束：
 * <ol>
 *   <li><b>只读源目录</b>。产物写进另一个目录——改写源目录会让它自己的运行契约哈希全部失配，
 *       等于为了重评把原始证据毁了；</li>
 *   <li><b>只重评证据流里能定位到回答的条目</b>。定位靠 {@code metadata.role=answer}，
 *       不靠"问答交替所以第二条是回答"这种顺序推断：推断错了不会报错，只会安静地拿提问去判分；</li>
 *   <li><b>旧格式如实拒绝</b>。角色标注之前导出的证据流没有 role 字段，此时报"无法离线重评"
 *       并说明原因，而不是猜。</li>
 * </ol>
 */
public final class Regrade {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VERDICT = Pattern.compile("verdict=(PASS|FAIL|UNKNOWN)");
    private static final Pattern LEGACY_PASSED = Pattern.compile("passed=(true|false)");
    private static final Pattern SESSION_CASE = Pattern.compile("^case-(.+?)(?:-r\\d+)?$");

    private Regrade() {
    }

    /** 一条裁决变化 */
    public record Change(String agent, String caseId, String sessionId,
                         Verdict before, Verdict after, String reason) {
        public String toLine() {
            return String.format("%-14s %-10s %s → %s%n        %s",
                    agent, caseId, before.label(), after.label(), oneLine(reason));
        }
    }

    /**
     * 一个被测对象的重评结果。
     *
     * @param total        证据流里能定位到回答的条数
     * @param changes      裁决发生变化的条目
     * @param beforePass   旧口径下判为通过的条数
     * @param beforeJudged 旧口径下构成有效判定的条数
     * @param afterPass    新口径下判为通过的条数
     * @param afterJudged  新口径下构成有效判定的条数
     */
    public record AgentResult(String agent, int total, List<Change> changes,
                              int beforePass, int beforeJudged, int afterPass, int afterJudged) {

        public double beforeEffective() {
            return beforeJudged == 0 ? Double.NaN : (double) beforePass / beforeJudged;
        }

        public double afterEffective() {
            return afterJudged == 0 ? Double.NaN : (double) afterPass / afterJudged;
        }
    }

    /**
     * 一次重评。
     *
     * @param runDir          源运行目录（只读）
     * @param beforeRules     源运行契约里记录的判定口径
     * @param afterRules      本次重评用的判定口径
     * @param agents          逐对象结果
     * @param problems        无法重评的原因（旧格式、缺证据流、题目对不上等）；非空时结论不可用
     * @param notes           读数字时必须一起读的口径提示（不影响可用性）
     */
    public record Result(Path runDir, String beforeRules, String afterRules,
                         List<AgentResult> agents, List<String> problems, List<String> notes) {

        public Result {
            agents = agents == null ? List.of() : List.copyOf(agents);
            problems = problems == null ? List.of() : List.copyOf(problems);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public boolean usable() {
            return problems.isEmpty();
        }

        public int totalChanges() {
            return agents.stream().mapToInt(a -> a.changes().size()).sum();
        }

        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("========== 离线重评（同一批证据，换口径重判） ==========\n");
            sb.append("源运行目录 : ").append(runDir.toAbsolutePath()).append("（只读，未被修改）\n");
            sb.append("口径变化   : ").append(beforeRules).append(" → ").append(afterRules).append('\n');
            if (beforeRules.equals(afterRules)) {
                sb.append("提示       : 新旧口径相同，本次重评只验证「同口径重算结果不变」\n");
            }
            sb.append('\n');
            if (!problems.isEmpty()) {
                sb.append("⚠ 无法完成重评（").append(problems.size()).append(" 项）：\n");
                problems.forEach(p -> sb.append("  - ").append(p).append('\n'));
                sb.append('\n');
            }
            if (!notes.isEmpty()) {
                sb.append("口径提示:\n");
                notes.forEach(n -> sb.append("  - ").append(n).append('\n'));
                sb.append('\n');
            }
            for (AgentResult a : agents) {
                sb.append("── ").append(a.agent()).append(" ──\n");
                sb.append(String.format(Locale.ROOT,
                        "可重评条数 : %d    裁决变化 : %d%n", a.total(), a.changes().size()));
                sb.append(String.format(Locale.ROOT,
                        "有效分     : %s → %s    已判定 : %d → %d    通过 : %d → %d%n",
                        pct(a.beforeEffective()), pct(a.afterEffective()),
                        a.beforeJudged(), a.afterJudged(), a.beforePass(), a.afterPass()));
                if (!a.changes().isEmpty()) {
                    sb.append("变化明细:\n");
                    a.changes().forEach(c -> sb.append("  ").append(c.toLine()).append('\n'));
                }
                sb.append('\n');
            }
            if (!agents.isEmpty()) {
                sb.append("说明: 重评只使用源目录里的证据流，不接触被测对象；");
                sb.append("因此上述差值只能来自判定口径，不能来自被测对象的能力变化。\n");
            }
            sb.append("======================================================");
            return sb.toString();
        }

        public String toJson() {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("runDir", runDir.toAbsolutePath().toString());
            root.put("beforeRules", beforeRules);
            root.put("afterRules", afterRules);
            root.put("usable", usable());
            root.put("totalChanges", totalChanges());
            ArrayNode problemArray = root.putArray("problems");
            problems.forEach(problemArray::add);
            ArrayNode noteArray = root.putArray("notes");
            notes.forEach(noteArray::add);
            ArrayNode agentArray = root.putArray("agents");
            for (AgentResult a : agents) {
                ObjectNode node = agentArray.addObject();
                node.put("agent", a.agent());
                node.put("total", a.total());
                node.put("beforePass", a.beforePass());
                node.put("beforeJudged", a.beforeJudged());
                node.put("afterPass", a.afterPass());
                node.put("afterJudged", a.afterJudged());
                node.put("beforeEffective", Double.isNaN(a.beforeEffective()) ? null : round(a.beforeEffective()));
                node.put("afterEffective", Double.isNaN(a.afterEffective()) ? null : round(a.afterEffective()));
                ArrayNode changes = node.putArray("changes");
                for (Change c : a.changes()) {
                    ObjectNode item = changes.addObject();
                    item.put("caseId", c.caseId());
                    item.put("sessionId", c.sessionId());
                    item.put("before", c.before().name());
                    item.put("after", c.after().name());
                    item.put("reason", c.reason());
                }
            }
            try {
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            } catch (Exception e) {
                throw new IllegalStateException("重评结果序列化失败", e);
            }
        }

        private static Double round(double v) {
            return Math.round(v * 10000) / 10000.0;
        }

        private static String pct(double v) {
            return Double.isNaN(v) ? "无定义" : String.format(Locale.ROOT, "%.1f%%", v * 100);
        }
    }

    /**
     * 执行重评。
     *
     * @param runDir 源运行目录，必须含 {@code manifest.json} 与 {@code evidence/*.jsonl}
     * @param pool   当前题集（一般由源契约记录的档位解析而来）
     * @param ruler  本次要用的判定器
     */
    public static Result run(Path runDir, List<MemoryCase> pool, CaseJudge ruler) throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, MemoryCase> byId = new LinkedHashMap<>();
        for (MemoryCase kase : pool) {
            byId.putIfAbsent(kase.id(), kase);
        }
        if (Files.isDirectory(runDir.resolve("oracle")) || Files.isDirectory(runDir.resolve("oracle-control"))) {
            notes.add("该次运行启用过神谕对照（目录里有 oracle/）：报告与运行契约里的有效分是"
                    + "「把基座/执行类失败移出分母之后」的数，而证据流里留的是神谕调整<b>之前</b>的裁决。"
                    + "本重评的 before 列因此会略低于 report.txt——差值来自神谕调整，不是来自口径");
        }

        if (Files.exists(runDir.resolve("attribution.json"))) {
            notes.add("该次运行留有 attribution.json（归因证据化明细）：它按<b>当时的判定结果</b>统计，"
                    + "重评不会重算它。重评后若要引用归因证据化比例，需按新口径重跑一次 run，"
                    + "不要直接拿旧文件里的比例当现状");
        }

        String beforeRules = "未记录";
        Path manifestFile = runDir.resolve(RunManifest.FILE_NAME);
        if (Files.exists(manifestFile)) {
            try {
                beforeRules = RunManifest.read(manifestFile).rulesVersion();
            } catch (RuntimeException e) {
                problems.add("运行契约读不出来（" + e.getMessage() + "），旧口径未知");
            }
        } else {
            problems.add("源目录没有 " + RunManifest.FILE_NAME + "，无法确认这批数字出自哪个口径");
        }

        Path evidenceDir = runDir.resolve("evidence");
        List<Path> streams = evidenceStreams(runDir);
        if (streams.isEmpty()) {
            problems.add("源目录没有证据流（" + evidenceDir + "），没有可重评的回答");
            return new Result(runDir, beforeRules, CaseJudge.RULES_VERSION, List.of(), problems, notes);
        }

        List<AgentResult> results = new ArrayList<>();
        for (Path stream : streams) {
            String agent = stream.getFileName().toString().replaceFirst("\\.jsonl$", "");
            AgentResult result = regradeStream(agent, stream, byId, ruler, problems);
            if (result != null) {
                results.add(result);
            }
        }
        return new Result(runDir, beforeRules, CaseJudge.RULES_VERSION, results, problems, notes);
    }

    private static AgentResult regradeStream(String agent, Path stream, Map<String, MemoryCase> byId,
                                             CaseJudge ruler, List<String> problems) throws IOException {
        Map<String, String> answers = new LinkedHashMap<>();
        Map<String, Verdict> before = new LinkedHashMap<>();
        int dialogueWithoutRole = 0;

        for (String line : Files.readAllLines(stream, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Evidence evidence;
            try {
                evidence = MAPPER.readValue(line, Evidence.class);
            } catch (IOException e) {
                problems.add("证据流有一行读不出来（" + stream.getFileName() + "）：" + firstChars(line));
                continue;
            }
            String sessionId = evidence.sessionId();
            if (Evidence.DIALOGUE.equals(evidence.type())) {
                String role = evidence.metadata() == null ? null : evidence.metadata().get("role");
                if (role == null) {
                    dialogueWithoutRole++;
                } else if (Evidence.ROLE_ANSWER.equals(role)) {
                    answers.put(sessionId, evidence.content() == null ? "" : evidence.content());
                }
            } else if (Evidence.ARTIFACT.equals(evidence.type())
                    && Evidence.KIND_JUDGMENT.equals(evidence.metadata().get("kind"))) {
                Verdict verdict = verdictOf(evidence);
                if (verdict != null) {
                    before.put(sessionId, verdict);
                }
            }
        }

        if (dialogueWithoutRole > 0 && answers.isEmpty()) {
            problems.add("【" + agent + "】这份证据流是角色标注之前的旧格式（" + dialogueWithoutRole
                    + " 条对话没有 role 字段），无法可靠定位回答——不重评，也不猜。"
                    + "请用当前版本重跑一次，之后的运行都支持离线重评");
            return null;
        }
        if (answers.isEmpty()) {
            problems.add("【" + agent + "】证据流里没有 role=answer 的条目，没有可重评的回答");
            return null;
        }

        List<Change> changes = new ArrayList<>();
        int beforePass = 0;
        int beforeJudged = 0;
        int afterPass = 0;
        int afterJudged = 0;
        int total = 0;
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            String sessionId = entry.getKey();
            String caseId = caseIdOf(sessionId);
            MemoryCase kase = caseId == null ? null : byId.get(caseId);
            if (kase == null) {
                problems.add("【" + agent + "】会话 " + sessionId + " 对不上当前题集里的任何用例，已跳过");
                continue;
            }
            total++;
            Verdict old = before.get(sessionId);
            if (old != null) {
                if (old.judged()) {
                    beforeJudged++;
                }
                if (old == Verdict.PASS) {
                    beforePass++;
                }
            }
            Judgment fresh = ruler.judge(kase, entry.getValue());
            if (fresh.judged()) {
                afterJudged++;
            }
            if (fresh.verdict() == Verdict.PASS) {
                afterPass++;
            }
            if (old != null && old != fresh.verdict()) {
                changes.add(new Change(agent, caseId, sessionId, old, fresh.verdict(), fresh.reason()));
            } else if (old == null) {
                problems.add("【" + agent + "】用例 " + caseId + " 找不到当时的裁决记录，只统计新结果不列变化");
            }
        }
        return new AgentResult(agent, total, changes, beforePass, beforeJudged, afterPass, afterJudged);
    }

    /**
     * 读出一次运行<b>当时</b>的逐题裁决，不做任何重判。
     * <p>
     * 与 {@link #run} 的区别：run 是「用今天的尺子重量昨天的证据」，本方法是「把昨天量出来的读数抄下来」。
     * {@code compare} 用它，因为跨运行比较必须比<b>各自当时</b>的读数，
     * 否则两次运行的口径差异会被算成能力差异。
     * <p>
     * 返回结构为 {@code 智能体 → 用例 → 裁决}；对照臂单独成一项（键为 {@code <id>-control}）。
     * 重复评测（{@code --repeat N}）时同一用例有多条裁决，这里取<b>第一次</b>——
     * 逐次是否翻面是 {@code Consistency} 的职责，不是比较的职责。
     */
    public static Map<String, Map<String, Verdict>> readVerdicts(Path runDir) throws IOException {
        Map<String, Map<String, Verdict>> table = new LinkedHashMap<>();
        for (Path stream : evidenceStreams(runDir)) {
            String agent = stream.getFileName().toString().replaceFirst("\\.jsonl$", "");
            Map<String, Verdict> byCase = new LinkedHashMap<>();
            for (String line : Files.readAllLines(stream, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Evidence evidence;
                try {
                    evidence = MAPPER.readValue(line, Evidence.class);
                } catch (IOException e) {
                    continue;
                }
                if (!Evidence.ARTIFACT.equals(evidence.type())
                        || !Evidence.KIND_JUDGMENT.equals(evidence.metadata().get("kind"))) {
                    continue;
                }
                Verdict verdict = verdictOf(evidence);
                String caseId = caseIdOf(evidence.sessionId());
                if (verdict != null && caseId != null) {
                    byCase.putIfAbsent(caseId, verdict);
                }
            }
            if (!byCase.isEmpty()) {
                table.put(agent, byCase);
            }
        }
        return table;
    }

    /**
     * 证据流文件：只认 {@code evidence/} 目录下的 {@code .jsonl}。
     * 运行目录里还有别的 {@code .jsonl}（例如内置被测自己的记忆落盘），它们不是证据流。
     */
    private static List<Path> evidenceStreams(Path runDir) throws IOException {
        Path evidenceDir = runDir.resolve("evidence");
        try (Stream<Path> walk = Files.exists(evidenceDir) ? Files.list(evidenceDir) : Stream.empty()) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    // 对照臂的证据流单独成文件，一并处理，否则 Δ 的口径会一半新一半旧
                    .sorted()
                    .toList();
        }
    }

    /** 会话 ID 形如 {@code case-r1} 或（重复评测时）{@code case-r1-r3}，去掉前后缀才是用例 ID */
    static String caseIdOf(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Matcher matcher = SESSION_CASE.matcher(sessionId);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * 读回一条裁决产物当时的三态。
     * <p>
     * 优先用 metadata 里的结构化字段（{@link Evidence#META_VERDICT}）；只有它缺失时——
     * 即结构化字段之前导出的旧证据流——才退回去解析人读文本。后者是<b>兼容</b>而非常规路径，
     * 所以两种历史文本格式都要认。
     */
    private static Verdict verdictOf(Evidence evidence) {
        String raw = evidence.metadata() == null ? null : evidence.metadata().get(Evidence.META_VERDICT);
        if (raw != null) {
            try {
                return Verdict.valueOf(raw);
            } catch (IllegalArgumentException ignored) {
                // 取值不认识：当作没有结构化字段，继续走文本兼容路径，不猜
            }
        }
        return parseVerdict(evidence.content());
    }

    /** 文本兼容路径：认三值的 {@code verdict=PASS} 与两值时代的 {@code passed=true} */
    static Verdict parseVerdict(String judgmentText) {
        if (judgmentText == null) {
            return null;
        }
        Matcher matcher = VERDICT.matcher(judgmentText);
        if (matcher.find()) {
            return Verdict.valueOf(matcher.group(1));
        }
        Matcher legacy = LEGACY_PASSED.matcher(judgmentText);
        if (legacy.find()) {
            return Boolean.parseBoolean(legacy.group(1)) ? Verdict.PASS : Verdict.FAIL;
        }
        return null;
    }

    private static String firstChars(String line) {
        String trimmed = line.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) + "…" : trimmed;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
