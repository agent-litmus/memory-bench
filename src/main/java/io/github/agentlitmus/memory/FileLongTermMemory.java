package io.github.agentlitmus.memory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 长期记忆的文件持久化实现（JSON Lines，一行一条）。
 * <p>
 * 选择文件而非数据库，是刻意的范围控制：
 * <ul>
 *   <li>零外部依赖，适合在 openKylin 这类环境直接跑，不需要装数据库；</li>
 *   <li>内容人可读，评测时每一行都能当作「证据」直接检查；</li>
 *   <li>数据量是评测级别（几十到几百条），重写整个文件的代价可以接受。</li>
 * </ul>
 * 检索采用确定性的关键词匹配而非向量召回：长期记忆评测考的是「记不记得住、用得对不对」，
 * 不是检索算法本身。确定性匹配可复现、可解释，也避免引入新的不确定性。
 * <p>
 * 本类<b>不依赖任何框架</b>，可直接在 CLI 或测试中使用。
 */
public class FileLongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(FileLongTermMemory.class);

    /**
     * 忽略未知字段：JSON Lines 里若残留旧版本字段，不应导致整条记忆被丢弃。
     * 与 readAll 中「单行损坏只跳过该行」的处理一致——局部问题不影响整体可用。
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path baseDir;
    private final Object lock = new Object();

    /** 默认构造：目录回退到临时目录，遵循「缺配置不能崩」的原则 */
    public FileLongTermMemory() {
        this(resolveBaseDir(null));
    }

    /** 指定目录路径（字符串形式），便于从配置文件读取 */
    public FileLongTermMemory(String dir) {
        this(resolveBaseDir(dir));
    }

    /** 直接指定目录，便于测试使用 {@code @TempDir} */
    public FileLongTermMemory(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建长期记忆目录: " + baseDir, e);
        }
    }

    public Path baseDir() {
        return baseDir;
    }

    @Override
    public String write(String sessionId, MemoryEntry entry) {
        String id = (entry.id() == null || entry.id().isBlank())
                ? UUID.randomUUID().toString()
                : entry.id();
        MemoryEntry stored = entry.withId(id);
        synchronized (lock) {
            List<MemoryEntry> all = new ArrayList<>(readAll(sessionId));
            all.add(stored);
            writeAll(sessionId, all);
        }
        log.debug("长期记忆写入 session={} id={} retainable={}", sessionId, id, stored.retainable());
        return id;
    }

    @Override
    public List<MemoryEntry> recall(String sessionId, String query, int topK) {
        List<String> terms = terms(query);
        synchronized (lock) {
            return readAll(sessionId).stream()
                    // 只召回「有效」且「策略上应保留」的：被取代的与不该留的都不出现
                    .filter(MemoryEntry::isActive)
                    .filter(MemoryEntry::retainable)
                    .map(entry -> new Scored(entry, score(entry.content(), terms)))
                    .filter(scored -> scored.score() > 0)
                    .sorted(Comparator
                            .comparingInt(Scored::score).reversed()
                            .thenComparing(s -> s.entry().updatedAt(), Comparator.reverseOrder()))
                    .limit(Math.max(1, topK))
                    .map(Scored::entry)
                    .toList();
        }
    }

    @Override
    public String update(String sessionId, String targetId, MemoryEntry replacement) {
        synchronized (lock) {
            List<MemoryEntry> all = new ArrayList<>(readAll(sessionId));
            String newId = (replacement.id() == null || replacement.id().isBlank())
                    ? UUID.randomUUID().toString()
                    : replacement.id();
            MemoryEntry created = replacement.withId(newId);

            List<MemoryEntry> next = new ArrayList<>(all.size() + 1);
            boolean found = false;
            for (MemoryEntry entry : all) {
                if (entry.id() != null && entry.id().equals(targetId)) {
                    // 旧条目保留内容但标记为已过时，供「动态更新」评测检查是否被误用
                    next.add(entry.supersededBy(newId));
                    found = true;
                } else {
                    next.add(entry);
                }
            }
            next.add(created);
            writeAll(sessionId, next);
            if (!found) {
                log.warn("长期记忆更新未命中目标 id={}（已直接写入新条目）", targetId);
            }
            return newId;
        }
    }

    @Override
    public boolean forget(String sessionId, String id) {
        synchronized (lock) {
            List<MemoryEntry> all = new ArrayList<>(readAll(sessionId));
            List<MemoryEntry> kept = all.stream()
                    .filter(entry -> entry.id() == null || !entry.id().equals(id))
                    .toList();
            if (kept.size() == all.size()) {
                return false;
            }
            writeAll(sessionId, kept);
            return true;
        }
    }

    @Override
    public List<MemoryEntry> all(String sessionId) {
        synchronized (lock) {
            return readAll(sessionId);
        }
    }

    @Override
    public List<MemoryEntry> active(String sessionId) {
        synchronized (lock) {
            return readAll(sessionId).stream()
                    .filter(MemoryEntry::isActive)
                    .toList();
        }
    }

    @Override
    public void clear(String sessionId) {
        synchronized (lock) {
            try {
                Files.deleteIfExists(fileOf(sessionId));
            } catch (IOException e) {
                log.warn("清除长期记忆失败 session={}: {}", sessionId, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private List<MemoryEntry> readAll(String sessionId) {
        Path file = fileOf(sessionId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            List<MemoryEntry> entries = new ArrayList<>();
            for (String line : (Iterable<String>) lines.filter(l -> !l.isBlank())::iterator) {
                try {
                    entries.add(mapper.readValue(line, MemoryEntry.class));
                } catch (IOException e) {
                    // 单行损坏不应导致整个记忆库不可用——跳过并告警
                    log.warn("长期记忆条目解析失败，已跳过: {}", e.getMessage());
                }
            }
            return entries;
        } catch (IOException e) {
            log.error("读取长期记忆失败 session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private void writeAll(String sessionId, List<MemoryEntry> entries) {
        try {
            List<String> lines = new ArrayList<>(entries.size());
            for (MemoryEntry entry : entries) {
                lines.add(mapper.writeValueAsString(entry));
            }
            Files.write(fileOf(sessionId), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入长期记忆失败 session=" + sessionId, e);
        }
    }

    private Path fileOf(String sessionId) {
        String safe = sessionId == null || sessionId.isBlank() ? "default" : sanitize(sessionId);
        return baseDir.resolve(safe + ".jsonl");
    }

    /** 防止 sessionId 里的路径分隔符写出目录之外 */
    private static String sanitize(String sessionId) {
        return sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Path resolveBaseDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return Paths.get(System.getProperty("java.io.tmpdir"), "agentlitmus-memory");
        }
        return Paths.get(dir);
    }

    /**
     * 查询词切分。
     * <p>
     * 中文没有空格：若整句只作为一个词，「我上周去了杭州」将永远匹配不到含「杭州」的记忆。
     * 因此对含汉字的片段额外生成二字滑窗（bigram），保证短语级别可命中；
     * 纯英文/数字片段仍按整体词处理，避免 bigram 引入「ab」命中「abc」这类误匹配。
     */
    static List<String> terms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String segment : lower.split("[\\s,，。、；;：:！!？?\"'（）()]+")) {
            if (segment.isBlank()) {
                continue;
            }
            out.add(segment);
            if (containsHan(segment)) {
                out.addAll(bigrams(segment));
            }
        }
        if (out.isEmpty()) {
            out.add(lower);
        }
        return out;
    }

    /** 二字滑窗：「我上周去了杭州」→ 我上 / 上周 / 周去 / 去了 / 了杭 / 杭州 */
    private static List<String> bigrams(String segment) {
        List<String> grams = new ArrayList<>();
        if (segment.length() < 2) {
            grams.add(segment);
            return grams;
        }
        for (int i = 0; i + 2 <= segment.length(); i++) {
            grams.add(segment.substring(i, i + 2));
        }
        return grams;
    }

    private static boolean containsHan(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    /** 命中词数即分数——确定性、可解释，不引入任何模型调用 */
    static int score(String content, List<String> terms) {
        if (content == null || content.isBlank() || terms.isEmpty()) {
            return 0;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        int hit = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                hit++;
            }
        }
        return hit;
    }

    private record Scored(MemoryEntry entry, int score) {
    }
}
