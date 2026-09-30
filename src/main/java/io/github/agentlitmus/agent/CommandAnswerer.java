package io.github.agentlitmus.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通过命令行调用智能体的回答器。
 * <p>
 * 很多智能体是 CLI 形态（尤其系统级智能体与本地 Agent），提供 HTTP 服务反而是额外负担。
 * 本类用 JDK 的 {@code ProcessBuilder} 直接调用可执行文件，把提问作为参数或标准输入传入，
 * 再读取标准输出作为回答——<b>零依赖</b>，且不要求被测智能体做任何改造。
 * <p>
 * 参数模板支持两个占位符：{@code {sessionId}} 与 {@code {question}}。
 */
public class CommandAnswerer implements Answerer {

    private final String command;
    private final List<String> args;
    private final List<String> resetArgs;
    private final Duration timeout;

    public CommandAnswerer(String command, List<String> args) {
        this(command, args, Duration.ofSeconds(120));
    }

    public CommandAnswerer(String command, List<String> args, Duration timeout) {
        this(command, args, List.of(), timeout);
    }

    public CommandAnswerer(String command, List<String> args, List<String> resetArgs, Duration timeout) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("命令行智能体必须提供 command");
        }
        this.command = command;
        this.args = args == null ? List.of() : List.copyOf(args);
        this.resetArgs = resetArgs == null ? List.of() : List.copyOf(resetArgs);
        this.timeout = timeout;
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
        try {
            ProcessBuilder builder = new ProcessBuilder(commandLine);
            Process process = builder.start();
            readAll(process.getInputStream());
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            // 重置失败不应中断评测：退化为本轮仍带上下文，由判定结果体现
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

        try {
            ProcessBuilder builder = new ProcessBuilder(commandLine);
            builder.redirectErrorStream(false);
            Process process = builder.start();

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
                process.destroyForcibly();
                return "";
            }
            String output = readAll(process.getInputStream());
            return output == null ? "" : output.trim();
        } catch (Exception e) {
            // 调用失败不应中断评测：返回空串，由判定器记为"遗漏"
            return "";
        }
    }

    private static String render(String template, String sessionId, String question) {
        if (template == null) {
            return "";
        }
        return template
                .replace(AgentConfig.PH_SESSION, sessionId == null ? "" : sessionId)
                .replace(AgentConfig.PH_QUESTION, question == null ? "" : question);
    }

    private static String readAll(InputStream in) {
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
