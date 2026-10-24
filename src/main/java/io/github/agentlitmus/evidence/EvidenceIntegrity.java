package io.github.agentlitmus.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.agentlitmus.util.Digests;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 证据流自检：逐行核对 {@code seq} 连续性、{@code sha256} 内容哈希与字段合法性。
 * <p>
 * 运行契约（manifest）对每个产物文件记整文件哈希，能证明「整个文件没被换掉」；
 * 但「改一行、删一行」之后再整体重算文件哈希是不自洽的——那两刀正好落在
 * 序号与内容哈希这两条<b>跨行约束</b>上：
 * <ul>
 *   <li>删一行 ⇒ {@code seq} 断号（序号从 1 连续递增是协议，见 {@code docs/schema/evidence.schema.json}）；</li>
 *   <li>改一行内容（不同步改哈希） ⇒ 该行 {@code sha256 ≠ sha256(content)}；</li>
 *   <li>改内容并重算该行哈希 ⇒ 挡不住（能改行的人也能改哈希），
 *       那是整文件哈希与 {@code manifest.json} 的职责——这套机制挡的是悄悄篡改，不是恶意伪造。</li>
 * </ul>
 * 结果由 {@code litmus verify} 汇总进对账结论。
 */
public final class EvidenceIntegrity {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvidenceIntegrity() {
    }

    /** 自检结论；issues 为空即通过 */
    public record Report(boolean ok, List<String> issues) {
        public Report {
            issues = List.copyOf(issues);
        }

        /** 给人读的单行结论，直接进 {@code litmus verify} 的输出与断言消息 */
        public String toText() {
            return ok() ? "证据流自检通过" : String.join("；", issues);
        }
    }

    /**
     * 检查一份证据流文件（JSON Lines）。
     * <p>
     * 报的是<b>物理行号</b>（从 1 起），因为要拿去 {@code sed -n '<N>p'} 打开现场；
     * 空行只跳过、<b>不占用序号期望值</b>——否则中间多一个空行会让其后每一行都误报断号与哈希不匹配。
     *
     * @return 逐行问题清单；文件级错误与「整份被删空」也作为 issue 记入
     */
    public static Report checkFile(Path file) {
        List<String> issues = new ArrayList<>();
        long rowsSeen = 0;
        long physicalLine = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                physicalLine++;
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                rowsSeen++;
                JsonNode node;
                try {
                    node = MAPPER.readTree(line);
                } catch (IOException e) {
                    issues.add("第 " + physicalLine + " 行不是合法 JSON: " + e.getMessage());
                    continue;
                }
                if (!node.isObject()) {
                    issues.add("第 " + physicalLine + " 行不是 JSON 对象");
                    continue;
                }
                checkRow(node, rowsSeen, "第 " + physicalLine + " 行", issues);
            }
        } catch (IOException e) {
            issues.add("读取失败: " + e.getMessage());
            return new Report(false, issues);
        }
        if (rowsSeen == 0) {
            issues.add("证据流为空（一行证据都没有，无从证明发生过什么）");
        }
        return new Report(issues.isEmpty(), issues);
    }

    /** 检查内存里的证据流（收集器刚盖章完的当场自检） */
    public static Report check(List<Evidence> stream) {
        List<String> issues = new ArrayList<>();
        if (stream == null || stream.isEmpty()) {
            issues.add("证据流为空（一行证据都没有，无从证明发生过什么）");
            return new Report(false, issues);
        }
        long expected = 0;
        for (Evidence item : stream) {
            expected++;
            checkRow(toJson(item), expected, "第 " + expected + " 条", issues);
        }
        return new Report(issues.isEmpty(), issues);
    }

    /**
     * 显式落成与 JSON Lines 一行同构的节点，让内存与落盘两条路共用同一套判据——
     * 判据不一致时，「当场自检通过、离线复核报错」这类分歧永远查不清。
     */
    private static JsonNode toJson(Evidence e) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", e.type());
        node.put("sessionId", e.sessionId());
        node.put("timestamp", e.timestamp());
        node.put("content", e.content());
        node.put("seq", e.seq());
        node.put("sha256", e.sha256());
        return node;
    }

    /** 单条证据的跨行约束与字段合法性；{@code where} 决定问题里怎么定位这一条 */
    private static void checkRow(JsonNode node, long expectedSeq, String where, List<String> issues) {
        String type = node.path("type").isTextual() ? node.path("type").asText() : null;
        if (type == null || !isKnownType(type)) {
            issues.add(where + " type 非法: " + (type == null ? "缺失" : type));
        }
        String sessionId = node.path("sessionId").isTextual() ? node.path("sessionId").asText() : null;
        if (sessionId == null || sessionId.isBlank()) {
            issues.add(where + " sessionId 缺失（证据无法归到任何一次会话）");
        }
        if (!node.path("timestamp").isNumber() || node.path("timestamp").asLong() <= 0) {
            issues.add(where + " timestamp 非法（必须是正的 Unix 毫秒）");
        }

        JsonNode seq = node.path("seq");
        if (!seq.isNumber() || seq.asLong() != expectedSeq) {
            issues.add(where + " 序号断裂: 期望 " + expectedSeq
                    + "，实际 " + (seq.isNumber() ? seq.asLong() : "缺失"));
        }

        JsonNode content = node.path("content");
        if (!content.isTextual()) {
            issues.add(where + " content 缺失或非文本（协议要求字符串，空回答写空串）");
            return;
        }
        String expectedHash = Digests.sha256Hex(content.asText());
        JsonNode hash = node.path("sha256");
        if (!hash.isTextual() || !expectedHash.equals(hash.asText())) {
            issues.add(where + " 内容哈希不匹配: 期望 " + Digests.shortForm(expectedHash)
                    + "，实际 " + (hash.isTextual() ? Digests.shortForm(hash.asText()) : "缺失"));
        }
    }

    private static boolean isKnownType(String type) {
        return Evidence.DIALOGUE.equals(type) || Evidence.MEMORY.equals(type)
                || Evidence.ACTION.equals(type) || Evidence.ARTIFACT.equals(type);
    }
}
