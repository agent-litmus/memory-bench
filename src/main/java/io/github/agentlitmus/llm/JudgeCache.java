package io.github.agentlitmus.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.agentlitmus.util.Digests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 评委裁决缓存：把 LLM 的判定结果按内容指纹落盘，<b>让"重新评分"变成可复现动作</b>。
 * <p>
 * 没有缓存时，"离线重评"是一个自相矛盾的说法：规则层可以逐字节重算，评委层却每次都在
 * 重新采样，同一批证据两次跑出两个分数，谁也不知道该信哪个。有了缓存，
 * 只要 (判定口径版本 + 评委指纹 + 判定标准 + 回答) 四元组不变，重评结果就<b>必须</b>不变——
 * 变化只能来自这四个输入之一，而这四个都写在运行契约里。
 * <p>
 * 这同时也是一笔成本账：真实智能体一轮评测几百回合，重跑一次要几十分钟和真金白银的额度；
 * 换了报告样式、换了归因文案、只想重算分数时，命中的那些裁决不该再问一次模型。
 * <p>
 * 缓存文件本身也是产物：落在运行目录里，会被 {@code manifest.json} 纳入哈希，
 * 因此"这批分数里有几条来自缓存"是可审计的，不是黑箱。
 */
public final class JudgeCache {

    /** 缓存文件格式版本；结构变了就整体作废，宁可重问也不要读错 */
    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();

    /**
     * 一条被缓存的裁决。
     *
     * @param verdict    评委给出的原文开头（PASS/FAIL/UNKNOWN）
     * @param reason     评委说明
     * @param judgeId    评委指纹（模型 + 端点 + 判词版本）
     * @param cachedAt   写入时间
     */
    public record Entry(String verdict, String reason, String judgeId, String cachedAt) {
    }

    private JudgeCache(Path file) {
        this.file = file;
    }

    /**
     * 打开（不存在则新建）一个缓存文件。读取失败不抛异常——缓存丢了只是多花一次模型调用，
     * 不该让整个评测跑不下去；但会打印一行提示，避免"缓存静默失效导致成本翻倍"没人知道。
     */
    public static JudgeCache open(Path file) {
        JudgeCache cache = new JudgeCache(file);
        if (file != null && Files.exists(file)) {
            try {
                cache.loadFrom(file);
            } catch (IOException | RuntimeException e) {
                System.out.println("警告: 评委缓存不可读，本轮将全部重新询问模型（" + file + ": " + e.getMessage() + "）");
            }
        }
        return cache;
    }

    /** 只在内存里用的缓存（测试、一次性运行）；不落盘 */
    public static JudgeCache inMemory() {
        return new JudgeCache(null);
    }

    private void loadFrom(Path path) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        if (root.path("formatVersion").asInt(-1) != FORMAT_VERSION) {
            System.out.println("提示: 评委缓存格式版本不符，已忽略旧缓存（" + path + "）");
            return;
        }
        JsonNode map = root.path("entries");
        Iterator<Map.Entry<String, JsonNode>> it = map.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            entries.put(e.getKey(), new Entry(v.path("verdict").asText(""), v.path("reason").asText(""),
                    v.path("judgeId").asText(""), v.path("cachedAt").asText("")));
        }
    }

    /**
     * 缓存键：把「同一句话在不同口径/不同评委下可能得到不同裁决」这件事编码进键里。
     * 少任何一项都会造出跨版本串味——例如换了判词版本却命中旧裁决，重评结果就成了假的。
     */
    public static String key(String rulesVersion, String judgeId, String rubric, String answer) {
        return Digests.sha256Hex(String.join("\u0000",
                String.valueOf(FORMAT_VERSION), nz(rulesVersion), nz(judgeId), nz(rubric), nz(answer)));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 评测可并行（--parallel），缓存是共享状态：读写都加锁，避免并行跑时把 LinkedHashMap 写坏 */
    public synchronized Entry get(String key) {
        Entry entry = entries.get(key);
        if (entry != null) {
            hits.incrementAndGet();
        } else {
            misses.incrementAndGet();
        }
        return entry;
    }

    public synchronized void put(String key, String verdict, String reason, String judgeId) {
        entries.put(key, new Entry(verdict, reason, judgeId, OffsetDateTime.now().toString()));
    }

    /** 落盘（原子写：先写临时文件再 move，避免半截 JSON 被下一次运行读到） */
    public synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            ObjectNode root = MAPPER.createObjectNode();
            root.put("formatVersion", FORMAT_VERSION);
            root.put("savedAt", OffsetDateTime.now().toString());
            ObjectNode map = root.putObject("entries");
            Map<String, Entry> snapshot;
            synchronized (this) {
                snapshot = new java.util.LinkedHashMap<>(entries);
            }
            snapshot.forEach((k, v) -> {
                ObjectNode node = map.putObject(k);
                node.put("verdict", v.verdict());
                node.put("reason", v.reason());
                node.put("judgeId", v.judgeId());
                node.put("cachedAt", v.cachedAt());
            });
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.out.println("警告: 评委缓存写入失败，本轮裁决将无法复用（" + file + ": " + e.getMessage() + "）");
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public int hits() {
        return hits.get();
    }

    public int misses() {
        return misses.get();
    }

    public Path file() {
        return file;
    }

    /** 一行统计，写进报告：让"这次有几条裁决来自缓存"是明面上的事实 */
    public String statsLine() {
        int asked = hits.get() + misses.get();
        return String.format("评委缓存: 命中 %d / 询问 %d（命中率 %s，缓存共 %d 条%s）",
                hits.get(), asked,
                asked == 0 ? "不适用" : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * hits.get() / asked),
                size(), file == null ? "，仅内存" : "，" + file);
    }
}
