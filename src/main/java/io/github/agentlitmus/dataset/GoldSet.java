package io.github.agentlitmus.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.core.Verdict;
import io.github.agentlitmus.util.Digests;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 标注集（gold set）：一批<b>真实运行产出的回答</b>加上人工给出的三值标签。
 * <p>
 * 它存在的唯一目的是回答一个问题：<b>尺子自己准不准</b>。评测工具最容易犯的错误是
 * 用一把没校准的尺子去量别人，然后把量出来的偏差说成被测对象的记忆缺陷。
 * 有了标注集，规则层的每一次判定都能和人工标签对账，一致率与 κ 就是这把尺子的误差棒。
 * <p>
 * 三条刻意的设计约束：
 * <ul>
 *   <li><b>样本来自真实运行，不是为了让规则通过而写的</b>——每条都带 {@code source} 指回产物文件；</li>
 *   <li><b>用例 ID 必须在当前题集里能解析</b>，且期望/排除关键词必须与标注时一致，
 *       否则 {@link #validate} 报错。题目改了而标注没改，等于拿旧答案给新尺子打分，必须吵起来；</li>
 *   <li><b>单人标注就写单人标注</b>。这里的 κ 是「规则判定 vs 人工标注」，
 *       不是两个独立标注者之间的 κ，不能与论文里的 inter-annotator κ 并列比较。</li>
 * </ul>
 */
public record GoldSet(String version,
                      String annotatedAt,
                      String annotator,
                      String protocol,
                      String honestyNote,
                      List<GoldEntry> entries) {

    /** 内置标注集在 classpath 里的位置（随 jar 发布，离线可跑） */
    public static final String BUNDLED_RESOURCE = "/goldset.json";

    public GoldSet {
        Objects.requireNonNull(entries, "标注集不能为空");
        entries = List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("标注集至少要有一条");
        }
    }

    /**
     * 一条标注：某个用例上某个真实回答，以及人工认为它应得的判定。
     *
     * @param caseId                 用例 ID（必须能在当前题集里解析）
     * @param agent                  产出这条回答的被测对象标识（只做溯源，不参与判定）
     * @param question               标注时的提问（人读用）
     * @param answer                 被测对象的原始回答；空串表示当时就没有回答
     * @param expectedAtAnnotation   标注时该用例的期望包含串；{@code null} 表示无
     * @param excludedAtAnnotation   标注时该用例的期望排除串；{@code null} 表示无
     * @param label                  人工标签（三值）
     * @param focus                  这条样本被收进来的理由，见标注集里的 focusLegend
     * @param rationale              为什么给这个标签——分歧出现时，这行是唯一能判断谁错的依据
     * @param source                 回答出自哪个产物文件
     */
    public record GoldEntry(String caseId,
                            String agent,
                            String question,
                            String answer,
                            String expectedAtAnnotation,
                            String excludedAtAnnotation,
                            Verdict label,
                            String focus,
                            String rationale,
                            String source) {

        public GoldEntry {
            if (caseId == null || caseId.isBlank()) {
                throw new IllegalArgumentException("标注条目必须指明用例 ID");
            }
            if (label == null) {
                throw new IllegalArgumentException("标注条目必须给出三值标签: " + caseId);
            }
            if (rationale == null || rationale.isBlank()) {
                throw new IllegalArgumentException("标注条目必须写清标签理由: " + caseId);
            }
            answer = answer == null ? "" : answer;
        }

        /** 当时就没有回答（空串），人工标签只能是「不可判」 */
        public boolean blankAnswer() {
            return answer.isBlank();
        }
    }

    /** 读内置标注集 */
    public static GoldSet bundled() {
        try (InputStream in = GoldSet.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("内置标注集缺失: " + BUNDLED_RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "classpath:" + BUNDLED_RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException("内置标注集读取失败: " + e.getMessage(), e);
        }
    }

    /** 读外置标注集（用于扩充或替换内置集） */
    public static GoldSet load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new IllegalArgumentException("标注集读取失败 " + file + ": " + e.getMessage(), e);
        }
    }

    private static GoldSet parse(String json, String origin) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("标注集不是合法 JSON（" + origin + "）: " + e.getMessage(), e);
        }
        JsonNode list = root.path("entries");
        if (!list.isArray() || list.isEmpty()) {
            throw new IllegalArgumentException("标注集缺少 entries 数组（" + origin + "）");
        }
        List<GoldEntry> entries = new ArrayList<>();
        for (JsonNode node : list) {
            String caseId = text(node, "caseId");
            entries.add(new GoldEntry(caseId, text(node, "agent"), text(node, "question"),
                    text(node, "answer"), text(node, "expectedAtAnnotation"),
                    text(node, "excludedAtAnnotation"),
                    verdict(text(node, "label"), caseId, origin),
                    text(node, "focus"), text(node, "rationale"), text(node, "source")));
        }
        return new GoldSet(text(root, "version"), text(root, "annotatedAt"), text(root, "annotator"),
                text(root, "protocol"), text(root, "honestyNote"), entries);
    }

    private static Verdict verdict(String raw, String caseId, String origin) {
        try {
            return Verdict.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("标注标签必须是 PASS/FAIL/UNKNOWN 之一（"
                    + origin + " 用例 " + caseId + " 写的是 " + raw + "）");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 标注集内容的 sha256——selftest 报告里要写，换了标注集必须能看出来 */
    public String sha256() {
        StringBuilder sb = new StringBuilder(version).append('\n');
        for (GoldEntry e : entries) {
            sb.append(e.caseId()).append('\t').append(e.agent()).append('\t')
                    .append(e.label()).append('\t').append(Digests.sha256Hex(e.answer())).append('\n');
        }
        return Digests.sha256Hex(sb.toString());
    }

    /**
     * 把每条标注对到当前题集上。
     *
     * @return 用例 ID → 用例（当前代码里的定义）
     */
    public Map<String, MemoryCase> resolveCases() {
        return resolveCases(CaseSuites.of(CaseSuites.FULL));
    }

    public Map<String, MemoryCase> resolveCases(List<MemoryCase> pool) {
        Map<String, MemoryCase> byId = new LinkedHashMap<>();
        for (MemoryCase kase : pool) {
            byId.putIfAbsent(kase.id(), kase);
        }
        return byId;
    }

    /**
     * 标注集与当前题集的一致性自检。返回的问题列表非空即表示<b>标注已失效</b>：
     * 要么用例被删了，要么判定关键词被改了。两种情况下继续算 κ 都是在拿旧答案给新尺子打分，
     * 得到的数字看着像结论，其实是噪声——所以这里选择报错，不选择静默继续。
     */
    public List<String> validate() {
        return validate(resolveCases());
    }

    public List<String> validate(Map<String, MemoryCase> byId) {
        List<String> issues = new ArrayList<>();
        for (GoldEntry entry : entries) {
            MemoryCase kase = byId.get(entry.caseId());
            if (kase == null) {
                issues.add("用例 " + entry.caseId() + " 已不在题集中（标注来源 " + entry.source() + "）");
                continue;
            }
            if (!Objects.equals(nz(entry.expectedAtAnnotation()), nz(kase.expectedContains()))) {
                issues.add("用例 " + entry.caseId() + " 的期望包含串已变更：标注时 ["
                        + nz(entry.expectedAtAnnotation()) + "] 现在 [" + nz(kase.expectedContains())
                        + "]——该条标注需要重新人工确认");
            }
            if (!Objects.equals(nz(entry.excludedAtAnnotation()), nz(kase.expectedNotContains()))) {
                issues.add("用例 " + entry.caseId() + " 的期望排除串已变更：标注时 ["
                        + nz(entry.excludedAtAnnotation()) + "] 现在 [" + nz(kase.expectedNotContains())
                        + "]——该条标注需要重新人工确认");
            }
        }
        return issues;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 按 focus 分组计数，用于说明这批标注覆盖了哪些难点 */
    public Map<String, Integer> focusCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GoldEntry entry : entries) {
            counts.merge(entry.focus() == null ? "未分类" : entry.focus(), 1, Integer::sum);
        }
        return counts;
    }

    /** 按标签计数 */
    public Map<Verdict, Integer> labelCounts() {
        Map<Verdict, Integer> counts = new LinkedHashMap<>();
        for (Verdict v : Verdict.values()) {
            counts.put(v, 0);
        }
        for (GoldEntry entry : entries) {
            counts.merge(entry.label(), 1, Integer::sum);
        }
        return counts;
    }
}
