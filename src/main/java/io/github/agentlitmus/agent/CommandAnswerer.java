package io.github.agentlitmus.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 通过命令行调用智能体的回答器。
 * <p>
 * 很多智能体是 CLI 形态（尤其系统级智能体与本地 Agent），提供 HTTP 服务反而是额外负担。
 * 本类用 JDK 的 {@code ProcessBuilder} 直接调用可执行文件，把提问作为参数或标准输入传入，
 * 再读取标准输出作为回答——<b>零依赖</b>，且不要求被测智能体做任何改造。
 * <p>
 * 参数模板支持的占位符：{@code {sessionId}}、{@code {question}}、{@code {sandboxHome}}
 * （沙箱家目录，用它把智能体的读写显式引到隔离区）。
 * 配置里的相对路径（如 {@code scripts/foo.sh}）按<b>启动 litmus 时的目录</b>解析，
 * 不会因为进程被放进沙箱工作目录而失效。
 * <p>
 * <b>默认开启进程隔离</b>：被测智能体是外部程序，不该看到评测账户的整份主目录与环境变量。
 * 隔离层级见 {@link ProcessSandbox#describe()}，其能力边界（无特权 ≠ 内核沙箱）也在同处如实说明。
 */
public class CommandAnswerer implements Answerer {

    private final String command;
    private final List<String> args;
    private final List<String> resetArgs;
    private final Duration timeout;
    private final ProcessSandbox sandbox;
    private final Path launchDir;
    private final List<String> seedReport;

    public CommandAnswerer(String command, List<String> args) {
        this(command, args, Duration.ofSeconds(120));
    }

    public CommandAnswerer(String command, List<String> args, Duration timeout) {
        this(command, args, List.of(), timeout);
    }

    public CommandAnswerer(String command, List<String> args, List<String> resetArgs, Duration timeout) {
        this(command, args, resetArgs, timeout, null, null);
    }

    /**
     * @param sandboxBase 沙箱根目录；null 用系统临时目录
     * @param sandboxMode {@code isolated}（默认，无特权隔离）/ {@code strict}（要求 bwrap 等内核级隔离，
     *                    探测不到直接失败）/ {@code none}（不隔离，仅个别智能体需要真实主目录时用）
     */
    public CommandAnswerer(String command, List<String> args, List<String> resetArgs, Duration timeout,
                           Path sandboxBase, String sandboxMode) {
        this(command, args, resetArgs, timeout, sandboxBase, sandboxMode, List.of());
    }

    /**
     * @param sandboxSeed 需要注入沙箱家目录的宿主路径（支持 {@code ~} 与相对启动目录的相对路径）
     */
    public CommandAnswerer(String command, List<String> args, List<String> resetArgs, Duration timeout,
                           Path sandboxBase, String sandboxMode, List<String> sandboxSeed) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("命令行智能体必须提供 command");
        }
        this.launchDir = Path.of("").toAbsolutePath();
        this.command = anchor(command);
        this.args = anchorAll(args);
        this.resetArgs = anchorAll(resetArgs);
        this.timeout = timeout;
        boolean strict = AgentConfig.SANDBOX_STRICT.equals(sandboxMode);
        try {
            this.sandbox = AgentConfig.SANDBOX_NONE.equals(sandboxMode)
                    ? ProcessSandbox.observeOnly(sandboxBase)
                    : ProcessSandbox.create(sandboxBase, strict ? Boolean.TRUE : null);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建进程隔离目录: " + e.getMessage(), e);
        }
        List<Path> seeds = new ArrayList<>();
        for (String raw : sandboxSeed == null ? List.<String>of() : sandboxSeed) {
            Path path = expandSeed(raw);
            if (path != null) {
                seeds.add(path);
            }
        }
        this.seedReport = List.copyOf(sandbox.seed(seeds));
    }

    /** 注入项披露：谁被复制进了沙箱，报告与运行契约里要看得见 */
    public List<String> seedReport() {
        return seedReport;
    }

    /** {@code ~/x} 展开成家目录，相对路径按启动目录解析——沙箱内的 HOME 是空的，不能用它当基准 */
    private Path expandSeed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.equals("~")) {
            return Path.of(System.getProperty("user.home", ""));
        }
        if (text.startsWith("~/")) {
            return Path.of(System.getProperty("user.home", ""), text.substring(2));
        }
        Path path = Path.of(text);
        return path.isAbsolute() ? path : launchDir.resolve(path).normalize();
    }

    /** 一句话说明被测进程运行在什么隔离层级（写入报告与运行契约） */
    public String sandboxDescription() {
        return sandbox.describe();
    }

    public Path sandboxHome() {
        return sandbox.home();
    }

    /**
     * 能力声明：CLI 形态默认只保证「能重置会话」。
     * 记忆是否可外部检视、工具调用是否可见都不做保证——宁可低报置信度，也不虚报。
     */
    @Override
    public Set<AgentCapability> capabilities() {
        Set<AgentCapability> caps = EnumSet.noneOf(AgentCapability.class);
        if (!resetArgs.isEmpty()) {
            caps.add(AgentCapability.RESETS_CONTEXT);
            caps.add(AgentCapability.STATE_RESETTABLE);
        }
        return caps;
    }

    /**
     * 进程级行动观测：评测方<b>自己看得见</b>的事实（命令行、退出码、耗时、是否超时），
     * 不依赖被测对象自报。按线程累积，由评测执行器在每条用例结束时取走。
     */
    private static final ThreadLocal<List<String>> ACTIONS = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public List<String> drainObservedActions() {
        List<String> drained = List.copyOf(ACTIONS.get());
        ACTIONS.get().clear();
        return drained;
    }

    private void recordAction(String commandLine, long startedAt, Integer exitCode, boolean timedOut) {
        long millis = System.currentTimeMillis() - startedAt;
        ACTIONS.get().add(String.join(" ", commandLine.split("\n"))
                + " ｜ 退出码=" + (timedOut ? "超时未退出" : String.valueOf(exitCode))
                + " ｜ 耗时=" + millis + "ms" + (timedOut ? " ｜ 已连同子孙进程强杀" : ""));
    }

    /**
     * 重置会话上下文：执行配置里声明的 resetArgs 命令（通常为删除会话状态文件）。
     * 长期记忆不在会话状态里，因此不受影响——这正是跨会话用例要测的边界。
     */
    @Override
    public void resetSession(String sessionId) {
        if (resetArgs.isEmpty()) {
            return;
        }
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        for (String arg : resetArgs) {
            commandLine.add(render(arg, sessionId, null));
        }
        long startedAt = System.currentTimeMillis();
        try {
            Process process = start(commandLine);
            readAll(process.getInputStream());
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                ProcessSandbox.killTree(process);
            }
            recordAction(String.join(" ", commandLine), startedAt,
                    finished ? process.exitValue() : null, !finished);
        } catch (Exception e) {
            // 重置失败不应中断评测：退化为本轮仍带上下文，由判定结果体现
            recordAction(String.join(" ", commandLine), startedAt, null, false);
        }
    }

    @Override
    public String answer(String sessionId, String question) {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        boolean hasQuestionPlaceholder = false;
        for (String arg : args) {
            if (arg != null && arg.contains(AgentConfig.PH_QUESTION)) {
                hasQuestionPlaceholder = true;
            }
            commandLine.add(render(arg, sessionId, question));
        }
        // 没有占位符时，把提问作为最后一个参数追加
        if (!hasQuestionPlaceholder) {
            commandLine.add(question == null ? "" : question);
        }

        long startedAt = System.currentTimeMillis();
        String rendered = String.join(" ", commandLine);
        try {
            Process process = start(commandLine);

            // 未声明 {question} 占位符时，同时把提问写入标准输入，兼容从 stdin 读取的智能体
            if (!hasQuestionPlaceholder && question != null) {
                try (java.io.OutputStream os = process.getOutputStream()) {
                    os.write(question.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 部分命令不读 stdin，忽略
                }
            }

            // 先等待进程在超时内结束，再读取输出：readAll 基于 readAllBytes，无法被中断，
            // 必须先 waitFor(timeout) 才能避免子进程挂起时永久阻塞（HttpAnswerer 有 timeout，此处需对齐）
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                ProcessSandbox.killTree(process);
                recordAction(rendered, startedAt, null, true);
                return "";
            }
            String output = readAll(process.getInputStream());
            recordAction(rendered, startedAt, process.exitValue(), false);
            return output == null ? "" : output.trim();
        } catch (Exception e) {
            // 调用失败不应中断评测：返回空串，由判定器记为「不可判（无响应）」
            recordAction(rendered, startedAt, null, false);
            return "";
        }
    }

    /** 统一在这里套用沙箱：任何一条命令都必须经过隔离，不留「忘了套」的口子 */
    private Process start(List<String> commandLine) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.redirectErrorStream(false);
        sandbox.apply(builder);
        return builder.start();
    }

    /**
     * 把配置里的相对路径锚回启动目录。
     * <p>
     * 沙箱会把子进程的工作目录换成隔离根（见 {@link ProcessSandbox#apply(ProcessBuilder)}），
     * 于是 {@code bash scripts/foo.sh} 这种写法找不到脚本，退出码 127。坏在这里的接入
     * 在报告上只表现为「无响应」，和「智能体本身答不出来」长得一模一样，很难查——所以干脆不让它发生。
     * <p>
     * 只锚<b>模板里</b>的 token，且要求它含路径分隔符并在启动目录下真实存在：
     * {@code bash} 这类靠 PATH 解析的程序名不动，渲染后的提问文本更不会动（锚定发生在渲染之前）。
     */
    private String anchor(String token) {
        return anchor(token, launchDir);
    }

    static String anchor(String token, Path launchDir) {
        if (token == null || token.isBlank() || token.startsWith("-")
                || !token.contains("/") || Path.of(token).isAbsolute()) {
            return token;
        }
        Path resolved = launchDir.resolve(token).normalize();
        return Files.exists(resolved) ? resolved.toString() : token;
    }

    private List<String> anchorAll(List<String> tokens) {
        if (tokens == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            out.add(anchor(token));
        }
        return List.copyOf(out);
    }

    private String render(String template, String sessionId, String question) {
        if (template == null) {
            return "";
        }
        return template
                .replace(AgentConfig.PH_SESSION, sessionId == null ? "" : sessionId)
                .replace(AgentConfig.PH_QUESTION, question == null ? "" : question)
                .replace(AgentConfig.PH_SANDBOX_HOME, sandbox.home().toAbsolutePath().toString());
    }

    private static String readAll(InputStream in) {
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
