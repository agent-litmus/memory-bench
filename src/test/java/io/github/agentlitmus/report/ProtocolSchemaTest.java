package io.github.agentlitmus.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.agent.AgentRole;
import io.github.agentlitmus.agent.AgentUnderTest;
import io.github.agentlitmus.agent.ConfidenceTier;
import io.github.agentlitmus.agent.RunnerMode;
import io.github.agentlitmus.agent.Agents;
import io.github.agentlitmus.core.CaseJudge;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.core.MultiAgentBenchmark;
import io.github.agentlitmus.core.Verdict;
import io.github.agentlitmus.dataset.CaseSuites;
import io.github.agentlitmus.dataset.MemoryCases;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.evidence.EvidenceCollector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议漂移测试：{@code docs/schema/*.json} 必须与代码里的实际取值一一对应。
 * <p>
 * Schema 是给<b>别的语言的 runner</b> 看的契约。它一旦与实现脱节，后果不是我们自己的测试变红，
 * 而是第三方按 schema 写出合格证据、却被我们的评分器拒收——而且他们查不出原因，
 * 因为文档说他们是对的。所以这里把 schema 当成被测对象：给 manifest 加一个字段而不更新 schema，
 * 本测试必须失败。
 * <p>
 * 本仓库不引入 JSON Schema 校验库（依赖只有 Jackson + slf4j，这是刻意的），
 * 因此校验的是<b>结构对应关系</b>而非完整 schema 语义：键集合、枚举取值、正则。
 * 完整语义校验留给接入了 ajv / python-jsonschema 的 CI。
 */
class ProtocolSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SCHEMA_DIR = Path.of("docs", "schema");

    private static JsonNode schema(String name) throws IOException {
        Path file = SCHEMA_DIR.resolve(name);
        assertTrue(Files.exists(file), "协议 schema 必须随仓库发布: " + file.toAbsolutePath());
        return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static Set<String> propertyNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.get("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> enumValues(JsonNode node) {
        Set<String> values = new HashSet<>();
        node.get("enum").forEach(v -> values.add(v.isNull() ? null : v.asText()));
        return values;
    }

    private static JsonNode manifestSchema() throws IOException {
        return schema("manifest.schema.json");
    }

    /** 跑一次真实离线评测并写出契约，用它的实际字段去对 schema */
    private static JsonNode realManifest(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<MemoryCase> cases = MemoryCases.defaultCases().subList(0, 6);
        List<String> warnings = new ArrayList<>();
        List<AgentUnderTest> agents = Agents.resolve(List.of("reference"), dir.resolve("memory"), warnings);
        BenchmarkResult result = MultiAgentBenchmark.run(cases, agents, new CaseJudge(),
                dir.resolve("artifacts"), 1);
        RunManifest.of(cases, agents, result, dir, 1, 1, CaseJudge.RULES_VERSION, "basic").writeTo(dir);
        return MAPPER.readTree(Files.readString(dir.resolve(RunManifest.FILE_NAME), StandardCharsets.UTF_8));
    }

    @Test
    void 契约schema的字段必须与实际产物完全一致(@TempDir Path dir) throws IOException {
        JsonNode manifest = realManifest(dir);
        JsonNode schema = manifestSchema();

        Set<String> actual = new HashSet<>();
        manifest.fieldNames().forEachRemaining(actual::add);
        assertEquals(propertyNames(schema), actual,
                "manifest 字段与 schema 不一致：加字段必须同步改 docs/schema/manifest.schema.json");

        Set<String> required = new HashSet<>();
        schema.get("required").forEach(r -> required.add(r.asText()));
        assertTrue(actual.containsAll(required), "required 里有实际不存在的字段: " + required);
        // suite 可空但必须存在，因此也在 required 之外单独钉一条
        assertTrue(actual.contains("suite"), "档位必须入档，否则 verify 只能拿默认档位去对 full 档目录");
        assertFalse(schema.get("additionalProperties").asBoolean(true),
                "契约不允许未声明字段：多出来的键会让第三方 runner 以为自己写对了");
    }

    @Test
    void 判定口径版本号必须符合schema里的格式() throws IOException {
        String pattern = manifestSchema().get("properties").get("rulesVersion").get("pattern").asText();
        assertTrue(Pattern.compile(pattern).matcher(CaseJudge.RULES_VERSION).matches(),
                "当前口径 " + CaseJudge.RULES_VERSION + " 不符合协议规定的格式 " + pattern);
    }

    @Test
    void 题集档位枚举必须覆盖所有档位() throws IOException {
        Set<String> declared = enumValues(manifestSchema().get("properties").get("suite"));
        assertTrue(declared.containsAll(List.of(CaseSuites.BASIC, CaseSuites.SECURITY, CaseSuites.PRESSURE,
                        CaseSuites.CROSSCUT, CaseSuites.STANDARD, CaseSuites.FULL)),
                "schema 少了档位: " + declared);
        assertTrue(declared.contains(null), "外置题集记 null，schema 必须允许");
    }

    @Test
    void 能力置信档位模式角色枚举必须覆盖代码取值(@TempDir Path dir) throws IOException {
        JsonNode agentSchema = manifestSchema().get("properties").get("agents").get("items");
        JsonNode props = agentSchema.get("properties");

        Set<String> capabilities = enumValues(props.get("capabilities").get("items"));
        for (AgentCapability c : AgentCapability.values()) {
            assertTrue(capabilities.contains(c.name()), "schema 少了能力: " + c);
        }
        Set<String> tiers = enumValues(props.get("confidenceTier"));
        for (ConfidenceTier t : ConfidenceTier.values()) {
            assertTrue(tiers.contains(t.name()), "schema 少了置信档位: " + t);
        }
        Set<String> modes = enumValues(props.get("mode"));
        for (RunnerMode m : RunnerMode.values()) {
            assertTrue(modes.contains(m.name()), "schema 少了运行模式: " + m);
        }
        Set<String> roles = enumValues(props.get("role"));
        for (AgentRole r : AgentRole.values()) {
            assertTrue(roles.contains(r.name()), "schema 少了对象角色: " + r);
        }

        // isolation 不得留空：留空会被读成「已隔离」
        assertEquals(1, props.get("isolation").get("minLength").asInt());

        JsonNode actual = realManifest(dir).get("agents").get(0);
        assertEquals(propertyNames(agentSchema), fieldSet(actual),
                "agents[] 字段与 schema 不一致");
    }

    @Test
    void 证据schema的类型角色裁决枚举必须覆盖代码常量() throws IOException {
        JsonNode schema = schema("evidence.schema.json");
        JsonNode props = schema.get("properties");

        assertEquals(Set.of(Evidence.DIALOGUE, Evidence.MEMORY, Evidence.ACTION, Evidence.ARTIFACT),
                enumValues(props.get("type")), "证据类型枚举与代码常量不一致");

        Set<String> roles = enumValues(props.get("metadata").get("properties").get("role"));
        assertEquals(Set.of(Evidence.ROLE_SEED, Evidence.ROLE_UPDATE, Evidence.ROLE_QUESTION,
                Evidence.ROLE_ANSWER, Evidence.ROLE_NOTE), roles,
                "对话角色枚举与代码常量不一致：离线重评靠 role 定位回答");

        Set<String> verdicts = enumValues(props.get("metadata").get("properties").get("verdict"));
        Set<String> declared = new HashSet<>();
        for (Verdict v : Verdict.values()) {
            declared.add(v.name());
        }
        assertEquals(declared, verdicts, "裁决枚举与三值语义不一致");

        Set<String> kinds = enumValues(props.get("metadata").get("properties").get("kind"));
        assertTrue(kinds.contains(Evidence.KIND_JUDGMENT), "裁决产物种类必须在协议里");

        Set<String> required = new HashSet<>();
        schema.get("required").forEach(r -> required.add(r.asText()));
        assertEquals(Set.of("type", "sessionId", "timestamp", "content", "metadata", "seq", "sha256"),
                required, "证据条目必需字段与 Evidence 记录不一致");
        assertEquals(propertyNames(schema), required, "证据条目的每个声明字段都是必需的");
    }

    @Test
    void 真实证据行必须落在schema声明的字段集合内(@TempDir Path dir) throws IOException {
        EvidenceCollector collector = new EvidenceCollector();
        collector.collect(Evidence.dialogue("case-r1", "问题", Evidence.ROLE_QUESTION));
        collector.collect(Evidence.dialogue("case-r1", "回答", Evidence.ROLE_ANSWER));
        collector.collect(Evidence.judgment("case-r1", "Judgment[verdict=PASS]", Verdict.PASS.name()));
        Path file = collector.exportTo(dir.resolve("evidence").resolve("agentA.jsonl"));

        Set<String> declared = propertyNames(schema("evidence.schema.json"));
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            assertEquals(declared, fieldSet(MAPPER.readTree(line)),
                    "证据行出现了 schema 未声明的字段: " + line);
        }
    }

    @Test
    void 三份协议文件都必须自洽() throws IOException {
        for (String name : List.of("evidence.schema.json", "manifest.schema.json")) {
            JsonNode node = schema(name);
            assertEquals("https://json-schema.org/draft/2020-12/schema", node.get("$schema").asText(),
                    name + " 必须声明所用草案版本，否则校验器行为不可预期");
            assertTrue(node.get("$id").asText().endsWith(name), name + " 的 $id 要与文件名对应");
            assertTrue(node.get("description").asText().contains("SPEC-EVIDENCE"),
                    name + " 要指回人读的协议正文");
        }
        Path spec = Path.of("docs", "SPEC-EVIDENCE.md");
        assertTrue(Files.exists(spec), "协议正文必须随仓库发布");
        String text = Files.readString(spec, StandardCharsets.UTF_8);
        assertTrue(text.contains(CaseJudge.RULES_VERSION),
                "协议正文必须写明当前判定口径，否则读者不知道这批数字出自哪把尺子");
        assertTrue(text.contains("schema/evidence.schema.json") && text.contains("schema/manifest.schema.json"),
                "协议正文必须链到两份 schema");
    }

    private static Set<String> fieldSet(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
