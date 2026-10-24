package io.github.agentlitmus.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 无特权进程隔离：给被测智能体一个<b>独立的临时家目录 + 白名单环境变量 + 独立临时目录</b>，
 * 并且超时时杀掉<b>整棵进程树</b>。
 * <p>
 * 为什么必须有它：评测时把提问交给外部智能体执行，等于把当前账户的整份主目录和环境变量
 * （里头通常有各类 API Key）交出去。行业里真实发生过的事故就是「被测智能体与评测同账户，
 * 并在宿主主目录里做了破坏性操作」。本类的目标是把同类事故的爆炸半径压到临时目录里：
 * <ul>
 *   <li>{@code HOME} 与 {@code XDG_*} 指向沙箱目录 ⇒ 智能体读写的是临时目录，不是真实主目录；</li>
 *   <li>环境变量走<b>白名单</b>，任何名字里含 KEY/TOKEN/SECRET/PASSWORD/AUTH 的一律不透传；</li>
 *   <li>DBus / 会话总线地址默认不透传 ⇒ 拿不到用户会话级服务；</li>
 *   <li>超时后连同子孙进程一起强杀，不留挂起进程。</li>
 * </ul>
 * <p>
 * <b>能力边界（照实说明，不做无凭据的宣称）</b>：本类是「无特权隔离」，不是安全沙箱——
 * 没有 mount/pid/network namespace，进程仍可读世界可读文件、仍可联网。真要隔离到内核层，
 * 需要 root 或 bubblewrap，见 {@link #namespaceWrapper()}：只有探测到可用工具时才提供该选项，
 * 且调用方要求强制隔离而工具缺失时必须直接失败，<b>不允许静默退化成本类这种弱隔离</b>。
 */
public final class ProcessSandbox {

    /** 允许透传的环境变量：只保留"程序能跑起来"所必需的最小集合 */
    private static final Set<String> ENV_WHITELIST = Set.of(
            "PATH", "LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE", "TERM", "TERM_PROGRAM",
            "SHELL", "TZ", "JAVA_HOME", "LD_LIBRARY_PATH", "NODE_PATH", "PYTHONPATH",
            "DISPLAY", "WAYLAND_DISPLAY", "COLUMNS", "LINES");

    /** 命中即拒：白名单之外再兜一层，避免别名/前缀化的凭据泄漏 */
    private static final List<String> ENV_DENY_WORDS =
            List.of("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "AUTH", "CREDENTIAL", "COOKIE");

    private final Path root;
    private final Path home;
    private final Path tmp;
    private final Map<String, String> env;
    private final String wrapper;
    private final boolean isolate;
    private final List<String> seeded = new ArrayList<>();
    private final List<String> seedIssues = new ArrayList<>();

    private ProcessSandbox(Path root, Map<String, String> env, String wrapper, boolean isolate)
            throws IOException {
        this.root = root;
        this.home = root.resolve("home");
        this.tmp = root.resolve("tmp");
        Files.createDirectories(home);
        Files.createDirectories(tmp);
        this.env = env;
        this.wrapper = wrapper;
        this.isolate = isolate;
    }

    /** 在系统临时区创建一份沙箱（每个被测对象一份，互不共享） */
    public static ProcessSandbox create() throws IOException {
        return create(null, null);
    }

    /**
     * @param baseDir    沙箱根；null 表示用系统临时目录（评测产物目录内更利于归档）
     * @param requireNs  要求内核级隔离（bwrap / sandbox-exec）；探测不到则直接失败，不静默降级
     */
    public static ProcessSandbox create(Path baseDir, Boolean requireNs) throws IOException {
        Path root = baseDir != null
                ? Files.createDirectories(baseDir.resolve("sandbox-" + System.nanoTime()))
                : Files.createTempDirectory("litmus-sandbox-");
        String wrapper = detectWrapper();
        if (Boolean.TRUE.equals(requireNs) && wrapper == null) {
            throw new IllegalStateException(
                    "要求内核级隔离，但未找到 bwrap 或 sandbox-exec。请安装 bubblewrap，或改用非强制隔离运行。");
        }
        Map<String, String> source = new LinkedHashMap<>(System.getenv());
        return new ProcessSandbox(root, scrub(source), wrapper, true);
    }

    /**
     * 只做「有沙箱目录、但不套用隔离」的实例：智能体确实需要真实 HOME 或会话总线时才用，
     * 且报告会<b>写明未隔离</b>——把风险留在纸面上，而不是藏在默认行为里。
     */
    public static ProcessSandbox observeOnly(Path baseDir) throws IOException {
        Path root = baseDir != null
                ? Files.createDirectories(baseDir.resolve("sandbox-" + System.nanoTime()))
                : Files.createTempDirectory("litmus-nosandbox-");
        return new ProcessSandbox(root, Map.of(), null, false);
    }

    /** 白名单 + 拒词双层过滤，并改写 HOME/TMPDIR/XDG_* 指向沙箱 */
    static Map<String, String> scrub(Map<String, String> source) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            String upper = key.toUpperCase(Locale.ROOT);
            if (!ENV_WHITELIST.contains(upper)) {
                continue;
            }
            boolean denied = ENV_DENY_WORDS.stream().anyMatch(upper::contains);
            if (!denied) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    /** 探测可用的内核级隔离工具，返回调用前缀；都没有则返回 null */
    static String detectWrapper() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") && executableOnPath("sandbox-exec")) {
            return "sandbox-exec";
        }
        return executableOnPath("bwrap") ? "bwrap" : null;
    }

    private static boolean executableOnPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, binary))) {
                return true;
            }
        }
        return false;
    }

    /** 供报告/契约使用的一句话说明：隔离做到了哪一层，一目了然 */
    public String describe() {
        if (!isolate) {
            return "未启用进程隔离（sandbox=none）：被测进程继承宿主 HOME 与环境变量，"
                    + "主目录与凭据对其可见，本次结果不应作为安全结论引用";
        }
        String level = wrapper == null ? "无特权隔离（独立 HOME/TMP + 环境变量白名单 + 进程树回收）"
                : wrapper + " 内核级隔离 + 环境变量白名单 + 进程树回收";
        String seed = seeded.isEmpty() ? ""
                : "；已从宿主注入 " + seeded.size() + " 项到家目录（" + String.join(", ", seeded)
                + "）——注入项可能含凭据，评测结束后随沙箱目录一并处置";
        // 注入失败必须一起披露：只报成功项的话，「凭据没进去所以智能体起不来」
        // 在报告上会退化成一句「无响应」，接入者查不到根因
        String issues = seedIssues.isEmpty() ? ""
                : "；注入未完成 " + seedIssues.size() + " 项（" + String.join(", ", seedIssues) + "）";
        return level + seed + issues + "；root=" + root.toAbsolutePath();
    }

    /** 内核级隔离前缀（null 表示当前环境只能用无特权隔离） */
    public String namespaceWrapper() {
        return wrapper;
    }

    public Path home() {
        return home;
    }

    public Path workingDirectory() {
        return root;
    }

    public boolean isolated() {
        return isolate;
    }

    /**
     * 把沙箱套到进程构造器上：工作目录、整套环境变量（清空后重建）、临时目录。
     * <p>
     * 刻意 {@code environment().clear()}：默认继承是主目录泄漏的常见来源，这里反过来要求显式声明。
     */
    public void apply(ProcessBuilder builder) {
        if (!isolate) {
            return;
        }
        builder.directory(root.toFile());
        Map<String, String> target = builder.environment();
        target.clear();
        target.putAll(env);
        target.put("HOME", home.toAbsolutePath().toString());
        target.put("TMPDIR", tmp.toAbsolutePath().toString());
        target.put("XDG_CACHE_HOME", home.resolve(".cache").toAbsolutePath().toString());
        target.put("XDG_CONFIG_HOME", home.resolve(".config").toAbsolutePath().toString());
        target.put("XDG_DATA_HOME", home.resolve(".local/share").toAbsolutePath().toString());
        target.put("XDG_STATE_HOME", home.resolve(".local/state").toAbsolutePath().toString());
        target.put("USER", "litmus");
        target.put("LOGNAME", "litmus");
    }

    /**
     * 把宿主上的文件/目录复制进沙箱家目录（按末级名字落位，如 {@code ~/.kylinbot} → {@code <home>/.kylinbot}）。
     * <p>
     * 为什么需要它：本地大模型智能体几乎都要读自己的凭据文件才能启动，而沙箱刻意把 {@code HOME}
     * 指向空目录——不注入就直接跑不起来，跑不起来在报告里只表现为「无响应」，查不出是接入问题。
     * 注入是<b>显式配置</b>且会被 {@link #describe()} 如实披露：给了什么进沙箱，纸面上看得见。
     * <p>
     * 复制后一律收紧权限（目录 700 / 文件 600）：沙箱建在临时区，默认权限会让同机其他用户读到凭据。
     *
     * @return 实际注入的条目描述；源不存在的条目被跳过并记为「未找到」，不静默失败
     */
    public List<String> seed(List<Path> sources) {
        List<String> report = new ArrayList<>();
        if (!isolate || sources == null) {
            return report;
        }
        for (Path source : sources) {
            if (source == null) {
                continue;
            }
            Path abs = source.toAbsolutePath().normalize();
            Path name = abs.getFileName();
            if (name == null) {
                continue;
            }
            Path target = home.resolve(name.toString());
            if (!Files.exists(abs)) {
                String missing = abs + " → 未找到，已跳过";
                seedIssues.add(missing);
                report.add(missing);
                continue;
            }
            try {
                copyTree(abs, target);
                tightenPermissions(target);
                String item = abs + " → " + target;
                seeded.add(item);
                report.add(item);
            } catch (IOException e) {
                String failed = abs + " → 注入失败: " + e.getMessage();
                seedIssues.add(failed);
                report.add(failed);
            }
        }
        return report;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path item : walk.toList()) {
                    Path dest = target.resolve(source.relativize(item).toString());
                    if (Files.isDirectory(item)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(item, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void tightenPermissions(Path target) throws IOException {
        Set<java.nio.file.attribute.PosixFilePermission> dir = Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        Set<java.nio.file.attribute.PosixFilePermission> file = Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        List<Path> items = Files.isDirectory(target)
                ? Files.walk(target).toList()
                : List.of(target);
        for (Path item : items) {
            try {
                Files.setPosixFilePermissions(item, Files.isDirectory(item) ? dir : file);
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统（Windows）不支持，跳过即可
            }
        }
    }

    /**
     * 结束进程<b>及其全部子孙</b>。
     * <p>
     * {@code Process.destroyForcibly()} 只杀直接子进程；智能体普遍是「启动器 → 真身 → 工具进程」，
     * 只杀顶层会留下挂起进程继续读写。
     */
    public static void killTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>();
        process.descendants().forEach(descendants::add);
        // 由深到浅：先杀工具进程，再杀启动器，避免父进程先死导致子进程被重新挂载到 init 后失去追踪
        descendants.sort(Comparator.comparingInt(ProcessSandbox::depth).reversed());
        for (ProcessHandle handle : descendants) {
            handle.destroyForcibly();
        }
        process.destroyForcibly();
        try {
            // 回收不回来也要继续评测，但最多只等 2 秒——否则超时用例会被挂死的子进程拖住
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int depth(ProcessHandle handle) {
        int depth = 0;
        Optional<ProcessHandle> parent = handle.parent();
        while (parent.isPresent()) {
            depth++;
            parent = parent.get().parent();
        }
        return depth;
    }

    /**
     * 尽力删除沙箱目录。
     * <p>
     * 默认不删：产物目录里的沙箱是「这次评测在隔离环境里跑过」的证据，归档后才由人工处置。
     * 传 {@code delete=true}（例如智能体在沙箱里留下大文件）时才递归清空，但仍保留目录本身。
     */
    public void dispose(boolean delete) {
        if (!delete) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.filter(p -> !p.equals(root))
                    .sorted(Comparator.reverseOrder()).toList();
            paths.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件删不掉不影响评测结论
                }
            });
        } catch (IOException ignored) {
            // 目录已不存在或被占用
        }
    }
}
