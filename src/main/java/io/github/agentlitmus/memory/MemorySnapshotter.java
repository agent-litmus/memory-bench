package io.github.agentlitmus.memory;

import io.github.agentlitmus.util.Digests;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 记忆状态快照：把「到底写没写进去」从<b>推断</b>变成<b>观测</b>。
 * <p>
 * 黑盒评测只看得到回答，因此「遗漏」有两种完全不同的原因——没写进去，或写进去没答出来——
 * 从对话里分不开。本类在被测对象的落盘目录前后各取一次快照，用字节级差异直接回答这一层：
 * <ul>
 *   <li>对话注入后目录<b>毫无变化</b> → 未持久化是被观测到的事实，不是猜测；</li>
 *   <li>文件确实变了但答案仍缺 → 问题在检索/应用环节。</li>
 * </ul>
 * 实现取舍：<b>不引入 JDBC、不解析对方格式</b>。SQLite 之类的存储只按原始字节做哈希与
 * 关键词扫描——能证明「变了 / 变没变 / 里面有没有这句话」，看不到行级语义。
 * 这条边界会在证据里如实写明，避免把字节观测夸大成结构观测。
 */
public final class MemorySnapshotter {

    /** 单文件读取上限：评测目录里的记忆文件通常只有 KB 级，设上限是为了不被一个巨型日志拖死 */
    public static final long DEFAULT_MAX_FILE_BYTES = 8L * 1024 * 1024;

    /** 差异清单最多列几行——报告是给人读的，超限只给数量 */
    private static final int MAX_DIFF_LINES = 20;

    /**
     * @param path     相对 root 的路径（用 / 分隔，跨平台可比）
     * @param size     字节数
     * @param sha256   内容哈希；超大文件为 {@code null}（只记大小，不读内容）
     */
    public record FileState(String path, long size, String sha256) {
    }

    /**
     * @param takenAtMillis 采集时刻
     * @param rootExisted   目录当时是否存在（不存在与存在但为空是两件事，必须分开记）
     * @param files         相对路径 → 状态（有序，保证同一目录两次快照可逐键对比）
     * @param skipped       因超过大小上限而未哈希的文件数
     */
    public record Snapshot(long takenAtMillis, boolean rootExisted,
                           Map<String, FileState> files, int skipped) {

        public static Snapshot empty(boolean rootExisted) {
            return new Snapshot(System.currentTimeMillis(), rootExisted, Map.of(), 0);
        }
    }

    /** 关键词命中：落在哪个文件、几次、附近一段原文 */
    public record Hit(String path, int count, String excerpt) {
    }

    private final Path root;
    private final long maxFileBytes;

    public MemorySnapshotter(Path root) {
        this(root, DEFAULT_MAX_FILE_BYTES);
    }

    public MemorySnapshotter(Path root, long maxFileBytes) {
        if (root == null) {
            throw new IllegalArgumentException("记忆快照必须指定目录");
        }
        this.root = root.toAbsolutePath();
        this.maxFileBytes = maxFileBytes <= 0 ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
    }

    public Path root() {
        return root;
    }

    /** 取一次快照；目录不存在返回空快照（不抛异常——「没有目录」本身就是重要观测） */
    public Snapshot capture() {
        if (!Files.isDirectory(root)) {
            return Snapshot.empty(false);
        }
        Map<String, FileState> files = new TreeMap<>();
        int skipped = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> regular = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                    .toList();
            for (Path file : regular) {
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException e) {
                    continue;
                }
                String key = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                if (size > maxFileBytes) {
                    files.put(key, new FileState(key, size, null));
                    skipped++;
                } else {
                    try {
                        files.put(key, new FileState(key, size, Digests.sha256File(file)));
                    } catch (IOException e) {
                        files.put(key, new FileState(key, size, null));
                        skipped++;
                    }
                }
            }
        } catch (IOException e) {
            // 遍历失败不阻断评测：已收集的部分仍然有效，缺的部分由 skipped 体现
        }
        return new Snapshot(System.currentTimeMillis(), true, files, skipped);
    }

    /**
     * 前后两次快照的差异，写成一行结论 + 逐文件明细（最多 {@value #MAX_DIFF_LINES} 行）。
     * 输出确定性排序，便于直接进证据文件与报告。
     */
    public static String describeDiff(Snapshot before, Snapshot after) {
        Map<String, FileState> oldFiles = before == null ? Map.of() : before.files();
        Map<String, FileState> newFiles = after == null ? Map.of() : after.files();
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, FileState> entry : newFiles.entrySet()) {
            FileState previous = oldFiles.get(entry.getKey());
            if (previous == null) {
                added.add(entry.getKey());
            } else if (!sameContent(previous, entry.getValue())) {
                changed.add(entry.getKey() + " " + previous.size() + "→" + entry.getValue().size() + " B");
            }
        }
        for (String key : oldFiles.keySet()) {
            if (!newFiles.containsKey(key)) {
                removed.add(key);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("记忆状态变化：新增 ").append(added.size())
                .append(" / 变更 ").append(changed.size())
                .append(" / 删除 ").append(removed.size())
                .append(" ｜ 未变 ").append(newFiles.size() - added.size() - changed.size())
                .append("（快照仅字节级，不解析对方存储格式）");
        if (before != null && !before.rootExisted()) {
            sb.append(" ｜ 采集前目录不存在");
        }
        if (after != null && after.skipped() > 0) {
            sb.append(" ｜ 超限未哈希 ").append(after.skipped()).append(" 个");
        }
        appendLines(sb, "+", added);
        appendLines(sb, "~", changed);
        appendLines(sb, "-", removed);
        return sb.toString();
    }

    private static boolean sameContent(FileState a, FileState b) {
        if (a.sha256() != null && b.sha256() != null) {
            return a.sha256().equals(b.sha256());
        }
        return a.size() == b.size();
    }

    private static void appendLines(StringBuilder sb, String sign, List<String> items) {
        int shown = Math.min(items.size(), MAX_DIFF_LINES);
        for (int i = 0; i < shown; i++) {
            sb.append('\n').append(' ').append(sign).append(' ').append(items.get(i));
        }
        if (items.size() > shown) {
            sb.append('\n').append(' ').append(sign).append(" …其余 ").append(items.size() - shown).append(" 项略");
        }
    }

    /**
     * 按字节扫描目录里是否出现过某段文本（探针用它验证「标记词真的落盘了」）。
     * 二进制文件同样能命中：字符串以 UTF-8 写入后就是确定的字节序列。
     */
    public List<Hit> findText(String needle, int maxHits) {
        List<Hit> hits = new ArrayList<>();
        if (needle == null || needle.isEmpty() || !Files.isDirectory(root)) {
            return List.of();
        }
        byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> regular = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : regular) {
                if (hits.size() >= Math.max(1, maxHits)) {
                    break;
                }
                Hit hit = scanOne(file, pattern);
                if (hit != null) {
                    hits.add(hit);
                }
            }
        } catch (IOException e) {
            // 扫描失败当作「没找到」：探针据此保守降级，不会虚报能力
        }
        return List.copyOf(hits);
    }

    private Hit scanOne(Path file, byte[] pattern) {
        try {
            long size = Files.size(file);
            if (size > maxFileBytes) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readAllBytes();
            }
            int count = 0;
            int firstAt = -1;
            int from = 0;
            while (true) {
                int at = indexOf(bytes, pattern, from);
                if (at < 0) {
                    break;
                }
                if (firstAt < 0) {
                    firstAt = at;
                }
                count++;
                from = at + pattern.length;
            }
            if (count == 0) {
                return null;
            }
            int start = Math.max(0, firstAt - 40);
            int end = Math.min(bytes.length, firstAt + pattern.length + 40);
            String excerpt = new String(bytes, start, end - start, StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
            return new Hit(root.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                    count, excerpt);
        } catch (IOException e) {
            return null;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * 状态目录是否可由评测方改写（{@code STATE_RESETTABLE} 的判据）。
     * <p>
     * 如实说明：这里验证的是<b>目录可增删文件</b>，即外部复位在权限上可行；
     * 不保证复位后的状态等于出厂干净状态——那需要接入方给出复位命令，由 {@code resetArgs} 承担。
     */
    public boolean writable() {
        Path probe = root.resolve(".litmus-probe.tmp");
        try {
            Files.createDirectories(root);
            Files.writeString(probe, "probe", StandardCharsets.UTF_8);
            boolean written = Files.exists(probe);
            Files.deleteIfExists(probe);
            return written;
        } catch (IOException e) {
            return false;
        }
    }
}
