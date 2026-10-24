package io.github.agentlitmus.cli;

import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.RunnerMode;
import io.github.agentlitmus.core.AttributionEvidence;
import io.github.agentlitmus.core.CaseJudge;
import io.github.agentlitmus.core.Judgment;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.core.OracleRecheck;
import io.github.agentlitmus.dataset.CaseSuites;
import io.github.agentlitmus.dataset.MemoryCases;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.llm.JudgeCache;
import io.github.agentlitmus.llm.OpenAiCompatClient;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.report.BenchmarkResult;
import io.github.agentlitmus.report.DeltaByMemory;
import io.github.agentlitmus.report.RunManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * run / run-http / selftest / regrade / verify 共用的组装逻辑。
 * <p>
 * 它们共享的不只是工具方法，更是<b>同一套口径</b>：题集怎么取、评委层怎么启用、
 * 归因怎么落盘——任何一处只在某个命令里生效，就会造出「同一批证据在不同命令下
 * 得到不同结论」的口径裂缝。
 */
final class Runs {

    private static final com.fasterxml.jackson.databind.ObjectWriter PRETTY =
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter();

    private Runs() {
    }

    static String pretty(Object value) {
        try {
            return PRETTY.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 取本轮要用的题集：外置文件优先，其次分档题集，最后内置基础档。
     * <p>
     * 三者互斥而不是叠加——把"外置文件"和"档位"混着跑会让题集哈希对不上，
     * 运行契约也就失去了"这批分数出自哪套题"的唯一答案。
     */
    static List<MemoryCase> loadCases(CliOptions options) {
        if (options.cases != null && !options.cases.isBlank()) {
            return MemoryCases.loadFrom(Path.of(options.cases));
        }
        return CaseSuites.of(options.suite);
    }

    /**
     * 题集对账该用哪一份题：契约里记了档位就按<b>那个档位</b>重算。
     * <p>
     * 否则对着 full 档的输出目录跑一次默认档位的对账，只会得到假的「题集不一致」——
     * 假的失败比没有检查更糟，它会让人以为对账这个动作本身不可信。
     */
    static List<MemoryCase> reconcileCases(CliOptions options, RunManifest manifest) {
        if (options.cases != null && !options.cases.isBlank()) {
            return MemoryCases.loadFrom(Path.of(options.cases));
        }
        String suite = manifest.suite();
        if (suite == null || suite.isBlank()) {
            return CaseSuites.of(options.suite);
        }
        try {
            return CaseSuites.of(suite);
        } catch (IllegalArgumentException e) {
            System.out.println("题集档位   : 契约记录的 " + suite + " 在当前代码里已不存在，按 --suite "
                    + options.suite + " 对账（题集哈希大概率不一致，这是真的不一致）");
            return CaseSuites.of(options.suite);
        }
    }

    /**
     * 组装本轮用的判定器。
     * <p>
     * 评委层是<b>可选增强</b>，不是前提：拿不到密钥就退回纯规则层，并把原因写进报告。
     * 这条路径必须始终可用——评测工具如果必须有 API Key 才能跑，那它在离线环境、
     * 内网、以及"我不想为了复核一个数字去申请额度"的场景里就等于不存在。
     * <p>
     * {@code --judge-model} / {@code --judge-endpoint} 支持逗号分隔多评委：每位评委独立请求、
     * 独立缓存（缓存键含各自指纹），裁决按<b>严格多数</b>聚合——平票或「不可判」占多数时
     * 记不可判。评委人数建议取奇数，否则平票必然弃权。
     *
     * @param notes 出参：把"评委层为什么没启用 / 启用了哪几位评委"写成人读的行
     */
    static CaseJudge buildRuler(CliOptions options, JudgeCache cache, List<String> notes) {
        if (!options.judge) {
            notes.add("未启用（规则层判定，口径 " + CaseJudge.RULES_VERSION
                    + "）；加 --judge 可让语义层接管「关键词分不开」的用例");
            return new CaseJudge();
        }
        String key = OpenAiCompatClient.keyFromEnv();
        if (key == null) {
            notes.add("已请求但不可用——环境变量里没有密钥（LITMUS_LLM_API_KEY / AI_DASHSCOPE_API_KEY），"
                    + "本轮退回规则层");
            return new CaseJudge();
        }
        List<String> models = splitList(options.judgeModel);
        List<String> endpoints = splitList(options.judgeEndpoint);
        if (models.isEmpty()) {
            models = List.of(OpenAiCompatClient.DEFAULT_MODEL);
        }
        if (!endpoints.isEmpty() && endpoints.size() != 1 && endpoints.size() != models.size()) {
            throw new IllegalArgumentException("--judge-endpoint 给了 " + endpoints.size()
                    + " 个端点，但评委有 " + models.size() + " 位：要么只给一个（全体共用），要么一一对应");
        }
        List<CaseJudge.Judge> judges = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            // 单端点全体共用；多端点与模型一一对应
            String endpoint = endpoints.isEmpty() ? null : endpoints.get(Math.min(i, endpoints.size() - 1));
            OpenAiCompatClient client = new OpenAiCompatClient(endpoint, models.get(i), key);
            String id = client.model() + "@" + client.fingerprint();
            notes.add("已启用 " + client.model() + " @ " + client.endpoint()
                    + "，指纹 " + client.fingerprint()
                    + "，密钥来源 " + OpenAiCompatClient.keySourceName()
                    + "（密钥本身不写入任何产物）");
            judges.add(new CaseJudge.Judge(client, id));
        }
        if (judges.size() > 1) {
            notes.add("评委团 " + judges.size() + " 位，多数决：平票或「不可判」占多数 ⇒ 记不可判，不硬判");
        }
        return new CaseJudge(judges, cache);
    }

    /** 逗号分隔列表：trim、去空；null/空返回空列表 */
    static List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 合并主臂与对照臂的判定，算该智能体的记忆增量 */
    static DeltaByMemory deltaOf(BenchmarkReport main, BenchmarkReport control) {
        List<Judgment> all = new ArrayList<>(main.judgments());
        all.addAll(control.judgments());
        return DeltaByMemory.of(new BenchmarkReport(all));
    }

    /**
     * 逐智能体统计「归因证据化比例」：带归因的判定里，有多少条能用<b>回答文本之外</b>的证据坐实。
     * <p>
     * 记忆注入模式下记忆由评测框架写入、逐条可读，因此该模式本身就等于「记忆可读」——
     * 这是模式带来的观测事实，不需要适配器再声明一次。对话模式下记忆在被测对象内部，
     * 只有配置里显式声明了 {@code MEMORY_INSPECTABLE}（例如给了 {@code stateDir}）才算可读。
     */
    static Map<AgentUnderTest, AttributionEvidence.Result> attributionOf(
            BenchmarkResult result, List<MemoryCase> cases,
            Map<AgentUnderTest, Set<String>> oracleCases) {
        Map<String, MemoryCase> casesById = new LinkedHashMap<>();
        for (MemoryCase kase : cases) {
            casesById.put(kase.id(), kase);
        }
        Map<AgentUnderTest, AttributionEvidence.Result> out = new LinkedHashMap<>();
        for (AgentUnderTest agent : result.ranked()) {
            EvidenceCollector collector = result.evidence().get(agent);
            Set<AgentCapability> caps = new LinkedHashSet<>(agent.answerer().capabilities());
            if (agent.mode() == RunnerMode.MEMORY) {
                caps.add(AgentCapability.MEMORY_INSPECTABLE);
            }
            out.put(agent, AttributionEvidence.evaluate(
                    result.reportOf(agent).judgments(), casesById,
                    collector == null ? List.of() : collector.all(), caps,
                    oracleCases.getOrDefault(agent, Set.of())));
        }
        return out;
    }

    static String renderAttribution(Map<AgentUnderTest, AttributionEvidence.Result> attribution) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 归因证据化（归因有多少条由独立证据支撑）==========\n");
        for (Map.Entry<AgentUnderTest, AttributionEvidence.Result> entry : attribution.entrySet()) {
            AttributionEvidence.Result r = entry.getValue();
            sb.append("── ").append(entry.getKey().name()).append(" ──\n");
            sb.append(r.headline()).append('\n');
            sb.append("可用证据: ").append(r.basis()).append('\n');
            r.byCause().forEach((cause, tally) -> sb.append("  ").append(cause.label())
                    .append("  ").append(tally[0]).append('/').append(tally[1]).append(" 条有证据\n"));
            if (!r.unbacked().isEmpty()) {
                sb.append("  未证据化 ").append(r.unbacked().size()).append(" 条（引用这些归因时需注明是推断）:\n");
                r.unbacked().stream().limit(20).forEach(u -> sb.append("    ").append(u).append('\n'));
                if (r.unbacked().size() > 20) {
                    sb.append("    …另有 ").append(r.unbacked().size() - 20).append(" 条，完整清单见 attribution.json\n");
                }
            }
        }
        // 全体的最低值决定这次运行的归因可信度：只要有一款以推断为主，就不能笼统说「归因有据」
        attribution.values().stream()
                .filter(r -> r.attributed() > 0)
                .mapToDouble(AttributionEvidence.Result::ratio)
                .min()
                .ifPresent(min -> sb.append("全体最低: ")
                        .append(String.format(Locale.ROOT, "%.1f%%", min * 100))
                        .append(min >= AttributionEvidence.TARGET
                                ? "（≥ 目标 80%，验收项 #3 达标）\n"
                                : "（< 目标 80%，验收项 #3 未达标——补 --with-oracle 或声明 stateDir 可提升）\n"));
        return sb.toString();
    }

    /**
     * 归因证据化明细落盘为 {@code attribution.json}。
     * <p>
     * 报告正文只列前 20 条未证据化归因，完整清单在这里；它必须在 {@code RunManifest.of} 之前写完，
     * 这样它自己也会被计入产物哈希——否则「归因有多少条没证据」这句话本身就没有对账依据。
     */
    static void writeAttribution(Map<AgentUnderTest, AttributionEvidence.Result> attribution,
                                 Path outDir) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("target", AttributionEvidence.TARGET);
        List<Map<String, Object>> agents = new ArrayList<>();
        double min = 1.0;
        boolean any = false;
        for (Map.Entry<AgentUnderTest, AttributionEvidence.Result> entry : attribution.entrySet()) {
            AttributionEvidence.Result r = entry.getValue();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("agent", entry.getKey().name());
            node.put("attributed", r.attributed());
            node.put("backed", r.backed());
            node.put("ratio", r.ratio());
            node.put("meetsTarget", r.meetsTarget());
            node.put("basis", r.basis());
            Map<String, Object> byCause = new LinkedHashMap<>();
            r.byCause().forEach((cause, tally) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("backed", tally[0]);
                item.put("attributed", tally[1]);
                byCause.put(cause.name(), item);
            });
            node.put("byCause", byCause);
            node.put("unbacked", r.unbacked());
            agents.add(node);
            if (r.attributed() > 0) {
                any = true;
                min = Math.min(min, r.ratio());
            }
        }
        root.put("agents", agents);
        root.put("minRatio", any ? min : 1.0);
        root.put("meetsTarget", !any || min >= AttributionEvidence.TARGET);
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("attribution.json"),
                pretty(root), StandardCharsets.UTF_8);
    }

    /**
     * 对结果里的每个智能体做神谕重问，返回调整后的结果，并把逐条结论写入
     * {@code <out>/oracle<tag>/<agent>.md}。
     * <p>
     * 调整只有一种方向：把「附上正确事实仍答错」的失败改成不可判（BASE_OR_EXEC）。
     * 因此有效分是<b>排除非记忆成因失败后</b>的记忆得分——报告必须同时给出重问条数，
     * 否则读者无从知道分母被移动过多少条。
     */
    static BenchmarkResult applyOracle(BenchmarkResult source, List<MemoryCase> cases,
                                       Path outDir, String tag, int maxCases,
                                       StringBuilder sink, CaseJudge ruler,
                                       Map<AgentUnderTest, Set<String>> oracleCases) throws IOException {
        Map<AgentUnderTest, BenchmarkReport> adjusted = new LinkedHashMap<>();
        Path oracleDir = outDir.resolve("oracle" + (tag.isBlank() ? "" : tag));
        Files.createDirectories(oracleDir);
        for (Map.Entry<AgentUnderTest, BenchmarkReport> entry : source.reports().entrySet()) {
            AgentUnderTest agent = entry.getKey();
            String text;
            if (!OracleRecheck.applicableTo(agent)) {
                adjusted.put(agent, entry.getValue());
                text = "【" + agent.name() + "】" + OracleRecheck.skipReason(agent);
            } else {
                OracleRecheck recheck = new OracleRecheck(agent.answerer(), ruler, maxCases,
                        source.evidence().get(agent));
                OracleRecheck.Result recheckResult = recheck.run(cases, entry.getValue());
                adjusted.put(agent, recheckResult.report());
                if (oracleCases != null) {
                    Set<String> ids = new LinkedHashSet<>();
                    recheckResult.checks().forEach(c -> ids.add(c.caseId()));
                    oracleCases.put(agent, ids);
                }
                text = "【" + agent.name() + "】\n" + recheckResult.toText();
            }
            sink.append(text).append('\n');
            Files.writeString(oracleDir.resolve(agent.id() + ".md"), text, StandardCharsets.UTF_8);
        }
        return new BenchmarkResult(adjusted, source.evidence());
    }
}
