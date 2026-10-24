package io.github.agentlitmus.report;

import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.agent.CapabilityOverlay;
import io.github.agentlitmus.agent.CommandAnswerer;
import io.github.agentlitmus.agent.HttpAnswerer;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.dataset.MemoryCases;
import io.github.agentlitmus.evidence.EvidenceIntegrity;
import io.github.agentlitmus.util.Digests;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 运行契约（manifest）：一次评测的<b>可对账指纹</b>。
 * <p>
 * 「完全可复现」如果不能被第三方检查，就只是一句自我声明。本文件把三件事钉死：
 * <ul>
 *   <li><b>输入</b>——用例集哈希、被测配置三元组、判定规则版本、运行参数、环境指纹；</li>
 *   <li><b>输出</b>——运行目录下每个产物文件的 sha256；</li>
 *   <li><b>结论</b>——各智能体的有效分 / 覆盖率 / 悲观下界。</li>
 * </ul>
 * {@code litmus verify --from <dir>} 会重算并逐项比对：改动过任何一条用例、一份证据或一个数字，
 * 对账就会失败。规则版本（{@code rulesVersion}）单独记录，因为口径变更会让同一批证据得到不同结论，
 * 必须能看出"是规则变了，不是数据变了"。
 */
public record RunManifest(String schemaVersion,
                          String generatedAt,
                          String litmusVersion,
                          String gitCommit,
                          String rulesVersion,
                          String caseSetSha256,
                          int caseCount,
                          String suite,
                          Environment environment,
                          Params params,
                          List<AgentEntry> agents,
                          Map<String, AgentScore> scores,
                          Map<String, String> files) {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String FILE_NAME = "manifest.json";
    /** 构建时过滤生成的版本戳；见 pom.xml 的 filtered-resources */
    private static final String VERSION_RESOURCE = "/litmus-version.properties";

    public record Environment(String osName, String osVersion, String osArch,
                              String javaVersion, String javaVendor) {
    }

    public record Params(int repeat, int parallel) {
    }

    public record AgentEntry(String id, String name, String mode, String role,
                             String adapter, String description,
                             List<String> capabilities, String confidenceTier, String isolation) {
    }

    public record AgentScore(String name, double effectiveRate, double coverage, double lowerBound,
                             int total, int judged, int unknown) {
    }

    /** 构建运行契约；会哈希 runDir 下<b>当前已存在</b>的产物，因此应在证据导出之后调用 */
    public static RunManifest of(List<MemoryCase> cases, List<AgentUnderTest> agents,
                                 BenchmarkResult result, Path runDir,
                                 int repeat, int parallel, String rulesVersion) throws IOException {
        return of(cases, agents, result, runDir, repeat, parallel, rulesVersion, null);
    }

    /**
     * 构建运行契约。
     *
     * @param suite 题集档位名（如 {@code full}）；外置题集传 null。
     *              记进契约是为了 {@code verify} 能拿<b>同一档</b>题重算——
     *              用默认档位去对一个 full 档的目录，只会得到假的「题集不一致」。
     */
    public static RunManifest of(List<MemoryCase> cases, List<AgentUnderTest> agents,
                                 BenchmarkResult result, Path runDir,
                                 int repeat, int parallel, String rulesVersion,
                                 String suite) throws IOException {
        Map<String, String> fileHashes = new LinkedHashMap<>();
        for (Path file : productFiles(runDir)) {
            fileHashes.put(runDir.relativize(file).toString().replace('\\', '/'), Digests.sha256File(file));
        }

        Map<String, AgentScore> scores = new LinkedHashMap<>();
        if (result != null) {
            for (AgentUnderTest agent : agents) {
                BenchmarkReport report = result.reportOf(agent);
                if (report == null) {
                    continue;
                }
                scores.put(agent.name(), new AgentScore(agent.name(), round(report.passRate()),
                        round(report.coverage()), round(report.lowerBound()),
                        report.total(), report.judgedCount(), report.unknownCount()));
            }
        }

        List<AgentEntry> entries = new ArrayList<>();
        for (AgentUnderTest agent : agents) {
            List<String> capabilities = agent.answerer().capabilities().stream()
                    .map(Enum::name).sorted().toList();
            // 适配器名与隔离层级都按「剥掉能力叠加层之后」的真实实现记录
            String adapter = CapabilityOverlay.unwrap(agent.answerer()).getClass().getSimpleName();
            entries.add(new AgentEntry(agent.id(), agent.name(), agent.mode().name(), agent.role().name(),
                    adapter, agent.description(),
                    capabilities, agent.answerer().confidenceTier().name(),
                    isolationOf(agent.answerer())));
        }

        return new RunManifest(SCHEMA_VERSION, Instant.now().toString(), detectLitmusVersion(), detectGitCommit(),
                rulesVersion, Digests.sha256Hex(MemoryCases.canonicalJson(cases)), cases.size(), suite,
                new Environment(System.getProperty("os.name", ""), System.getProperty("os.version", ""),
                        System.getProperty("os.arch", ""),
                        System.getProperty("java.version", ""), System.getProperty("java.vendor", "")),
                new Params(repeat, parallel), entries, scores, fileHashes);
    }

    /**
     * 隔离层级如实入档：命令行智能体记沙箱说明；HTTP 与内置被测不启动外部进程，
     * 隔离责任在被测服务自身，这里写清楚而不是留空——留空会被读成「已隔离」。
     */
    private static String isolationOf(Answerer answerer) {
        Answerer real = CapabilityOverlay.unwrap(answerer);
        if (real instanceof CommandAnswerer command) {
            return command.sandboxDescription();
        }
        if (real instanceof HttpAnswerer) {
            return "HTTP 接入：评测侧不启动被测进程，进程/主目录隔离由被测服务自身负责";
        }
        return "内置确定性被测：不启动外部进程";
    }

    /** 参与哈希的产物：报告、图表、证据、逐条产物；不含 manifest 自身 */
    private static List<Path> productFiles(Path runDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(runDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(FILE_NAME))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    /**
     * 证据流文件（JSON Lines）：逐条自检的对象。
     * <p>
     * 只认 {@code evidence/} 目录下的文件——运行目录里还有别的 {@code .jsonl}
     * （例如内置被测自己的记忆落盘），它们不是证据流，没有序号与内容哈希，
     * 拿证据契约去查它们只会产出一堆假问题。
     */
    private static List<Path> evidenceFiles(Path runDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(runDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(p -> p.getParent() != null
                            && "evidence".equals(p.getParent().getFileName().toString()))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    public Path writeTo(Path runDir) throws IOException {
        Files.createDirectories(runDir);
        ObjectMapper mapper = new ObjectMapper();
        Files.writeString(runDir.resolve(FILE_NAME),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this), StandardCharsets.UTF_8);
        return runDir.resolve(FILE_NAME);
    }

    public static RunManifest read(Path file) {
        try {
            return new ObjectMapper().readValue(file.toFile(), RunManifest.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取运行契约失败: " + file + " —— " + e.getMessage(), e);
        }
    }

    /**
     * 重算并逐项对账。
     *
     * @return 问题清单；为空表示该目录与 manifest 完全一致
     */
    public List<String> verify(Path runDir) {
        List<String> issues = new ArrayList<>();
        try {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                Path file = runDir.resolve(entry.getKey());
                if (!Files.exists(file)) {
                    issues.add("产物缺失: " + entry.getKey());
                    continue;
                }
                String actual = Digests.sha256File(file);
                if (!actual.equals(entry.getValue())) {
                    issues.add("产物被改动: " + entry.getKey()
                            + "（契约 " + Digests.shortForm(entry.getValue())
                            + " ≠ 实际 " + Digests.shortForm(actual) + "）");
                }
            }
            // 反向检查：契约里没有、但目录里有的产物，说明这份目录混入了别的运行结果
            for (Path file : productFiles(runDir)) {
                String relative = runDir.relativize(file).toString().replace('\\', '/');
                if (!files.containsKey(relative)) {
                    issues.add("契约未登记的文件: " + relative);
                }
            }
            // 证据流逐条自检：文件哈希只能证明「整个文件没被换掉」，
            // 序号与内容哈希才能发现「改一行、删一行」这类更隐蔽的手脚
            for (Path file : evidenceFiles(runDir)) {
                EvidenceIntegrity.Report report = EvidenceIntegrity.checkFile(file);
                if (!report.ok()) {
                    issues.add(runDir.relativize(file).toString().replace('\\', '/')
                            + " 证据流自检未通过：" + report.issues().get(0)
                            + (report.issues().size() > 1 ? "（共 " + report.issues().size() + " 项）" : ""));
                }
            }
        } catch (IOException e) {
            issues.add("对账过程失败: " + e.getMessage());
        }
        return issues;
    }

    /**
     * 构建时钉进产物的版本戳（{@code src/main/filtered-resources/litmus-version.properties}）。
     * <p>
     * 没有它，运行契约里的 {@code litmusVersion} 永远是 {@code unknown}——「这批数字出自哪个版本」
     * 就没有答案，而跨版本回归比较恰恰依赖这个答案。从工作树里跑（IDE / {@code mvn test}）时
     * 占位符没被替换，因此还要挡掉未过滤的字面量。
     */
    private static Properties versionStamp() {
        Properties props = new Properties();
        try (InputStream in = RunManifest.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // 版本戳读不出来不影响评测本身，契约里如实记 unknown
        }
        return props;
    }

    /** 取一个可用的版本戳字段；未过滤（值还是 {@code ${...}}）或缺失都算不可用 */
    private static String stamp(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() || value.startsWith("${") ? null : value;
    }

    private static String detectLitmusVersion() {
        String stamped = stamp(versionStamp(), "version");
        if (stamped != null) {
            return stamped;
        }
        String version = RunManifest.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }

    /** 读取当前提交号；构建时已钉过就用钉的，否则读工作树，都取不到返回 unknown，不影响评测本身 */
    private static String detectGitCommit() {
        String stamped = stamp(versionStamp(), "commit");
        if (stamped != null && !"unknown".equals(stamped)) {
            return stamped;
        }
        Path gitDir = Path.of(".git");
        try {
            if (!Files.exists(gitDir)) {
                return "unknown";
            }
            Path head = Files.isDirectory(gitDir) ? gitDir.resolve("HEAD") : resolveExternalGitDir(gitDir);
            if (head == null || !Files.exists(head)) {
                return "unknown";
            }
            String ref = Files.readString(head, StandardCharsets.UTF_8).trim();
            if (ref.startsWith("ref: ")) {
                Path refFile = head.resolveSibling(ref.substring(5));
                return Files.exists(refFile) ? Files.readString(refFile, StandardCharsets.UTF_8).trim() : ref;
            }
            return ref;
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static Path resolveExternalGitDir(Path dotGit) throws IOException {
        String text = Files.readString(dotGit, StandardCharsets.UTF_8).trim();   // "gitdir: <path>"
        if (!text.startsWith("gitdir:")) {
            return null;
        }
        return Path.of(text.substring(7).trim()).resolve("HEAD");
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }
}
