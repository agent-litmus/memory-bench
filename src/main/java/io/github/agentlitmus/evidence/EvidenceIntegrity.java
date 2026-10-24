package io.github.agentlitmus.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentlitmus.util.Digests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 证据流自检：逐行核对 {@code seq} 连续性与 {@code sha256} 内容哈希。
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
    }

    /**
     * 检查一份证据流文件（JSON Lines）。
     *
     * @return 逐行问题清单（行号从 1 起，与 {@code seq} 对齐）；文件级错误也作为一条 issue 记入
     */
    public static Report checkFile(Path file) {
        List<String> issues = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            issues.add("读取失败: " + e.getMessage());
            return new Report(false, issues);
        }

        long expectedSeq = 0;
        for (String raw : lines) {
            String line = raw.trim();
            expectedSeq++;
            if (line.isEmpty()) {
                continue;    // 尾部空行不算断号
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (IOException e) {
                issues.add("第 " + expectedSeq + " 行不是合法 JSON: " + e.getMessage());
                continue;
            }
            if (!node.isObject()) {
                issues.add("第 " + expectedSeq + " 行不是 JSON 对象");
                continue;
            }
            JsonNode seq = node.path("seq");
            if (!seq.isNumber() || seq.asLong() != expectedSeq) {
                issues.add("第 " + expectedSeq + " 行 seq 不连续: 期望 " + expectedSeq
                        + "，实际 " + (seq.isNumber() ? seq.asLong() : "缺失"));
            }
            String content = node.path("content").isTextual() ? node.path("content").asText() : null;
            String expectedHash = Digests.sha256Hex(content == null ? "" : content);
            JsonNode hash = node.path("sha256");
            if (!hash.isTextual() || !expectedHash.equals(hash.asText())) {
                issues.add("第 " + expectedSeq + " 行内容哈希不匹配: 期望 " + Digests.shortForm(expectedHash)
                        + "，实际 " + (hash.isTextual() ? Digests.shortForm(hash.asText()) : "缺失"));
            }
        }
        return new Report(issues.isEmpty(), issues);
    }
}
