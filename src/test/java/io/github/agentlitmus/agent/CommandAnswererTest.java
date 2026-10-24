package io.github.agentlitmus.agent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行智能体接入的回归测试。
 * <p>
 * 重点盯一个曾经真实发生过的故障：沙箱把子进程的工作目录换成隔离根之后，
 * 配置里的相对脚本路径失效，退出码 127，而报告上只显示「无响应」——
 * 接入坏了和被测对象答不出来在结论里长得一模一样。
 */
class CommandAnswererTest {

    @Test
    void 相对脚本路径要锚回启动目录(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("scripts/adapter.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/usr/bin/env bash\necho ok\n", StandardCharsets.UTF_8);

        String anchored = CommandAnswerer.anchor("scripts/adapter.sh", dir);

        assertEquals(script.toString(), anchored);
    }

    @Test
    void 不存在的路径原样保留交给shell报错(@TempDir Path dir) {
        assertEquals("scripts/missing.sh", CommandAnswerer.anchor("scripts/missing.sh", dir));
    }

    @Test
    void 靠PATH解析的程序名与选项与占位符都不许被改写(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("bash"));
        assertEquals("bash", CommandAnswerer.anchor("bash", dir));
        assertEquals("-m", CommandAnswerer.anchor("-m", dir));
        assertEquals("{question}", CommandAnswerer.anchor("{question}", dir));
        assertEquals("/usr/bin/kylin-bot", CommandAnswerer.anchor("/usr/bin/kylin-bot", dir));
    }

    @Test
    void 沙箱换掉工作目录后相对脚本仍能跑通(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("scripts/echo.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/usr/bin/env bash\necho \"$2\"\n", StandardCharsets.UTF_8);

        String anchored = CommandAnswerer.anchor("scripts/echo.sh", dir);
        CommandAnswerer answerer = new CommandAnswerer("bash",
                List.of(anchored, "{sessionId}", "{question}"), List.of(), Duration.ofSeconds(30));

        assertEquals("今天几号", answerer.answer("case-r1", "今天几号"));
    }

    @Test
    void 提问文本里长得像路径的字串不得被改写(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("scripts/echo.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/usr/bin/env bash\necho \"$2\"\n", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("docs"));

        CommandAnswerer answerer = new CommandAnswerer("bash",
                List.of(CommandAnswerer.anchor("scripts/echo.sh", dir), "{sessionId}", "{question}"),
                List.of(), Duration.ofSeconds(30));

        assertEquals("docs/readme.md 是什么", answerer.answer("case-r1", "docs/readme.md 是什么"));
    }

    @Test
    void 行动证据要记下退出码让接入故障可追(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("scripts/fail.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/usr/bin/env bash\nexit 127\n", StandardCharsets.UTF_8);

        CommandAnswerer answerer = new CommandAnswerer("bash",
                List.of(CommandAnswerer.anchor("scripts/fail.sh", dir), "{question}"),
                List.of(), Duration.ofSeconds(30));
        answerer.drainObservedActions();
        answerer.answer("case-r1", "问");

        List<String> actions = answerer.drainObservedActions();
        assertEquals(1, actions.size());
        assertTrue(actions.get(0).contains("退出码=127"), actions.get(0));
    }

    @Test
    void 注入项要落进沙箱家目录供智能体读取(@TempDir Path dir) throws IOException {
        Path seed = dir.resolve("seed/config.toml");
        Files.createDirectories(seed.getParent());
        Files.writeString(seed, "model = \"qwen\"\n", StandardCharsets.UTF_8);
        Path script = dir.resolve("scripts/read-home.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/usr/bin/env bash\ncat \"$HOME/config.toml\"\n", StandardCharsets.UTF_8);

        CommandAnswerer answerer = new CommandAnswerer("bash",
                List.of(CommandAnswerer.anchor("scripts/read-home.sh", dir)),
                List.of(), Duration.ofSeconds(30), dir.resolve("sbx"), "isolated",
                List.of(seed.toString()));

        assertEquals("model = \"qwen\"", answerer.answer("case-r1", "问"));
    }

    @Test
    void 注入的凭据文件不得世界可读(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "需要 POSIX 文件系统");
        Path seed = dir.resolve(".secret_key");
        Files.writeString(seed, "k\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(seed, PosixFilePermissions.fromString("rw-r--r--"));
        Path script = dir.resolve("scripts/noop.sh");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/usr/bin/env bash\necho ok\n", StandardCharsets.UTF_8);

        CommandAnswerer answerer = new CommandAnswerer("bash",
                List.of(CommandAnswerer.anchor("scripts/noop.sh", dir)),
                List.of(), Duration.ofSeconds(30), dir.resolve("sbx"), "isolated",
                List.of(seed.toString()));
        answerer.answer("case-r1", "问");

        Path copied = answerer.sandboxHome().resolve(".secret_key");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(copied),
                "沙箱建在临时区，注入的凭据必须收紧到仅属主可读写");
    }

    @Test
    void 注入行为要在隔离说明里如实披露(@TempDir Path dir) throws IOException {
        Path seed = dir.resolve("config.toml");
        Files.writeString(seed, "x\n", StandardCharsets.UTF_8);

        CommandAnswerer answerer = new CommandAnswerer("echo", List.of("{question}"),
                List.of(), Duration.ofSeconds(30), dir.resolve("sbx"), "isolated",
                List.of(seed.toString(), dir.resolve("不存在.toml").toString()));

        String described = answerer.sandboxDescription();
        assertTrue(described.contains("已从宿主注入 1 项"), described);
        assertTrue(described.contains(seed.toString()), described);
        assertTrue(described.contains("注入未完成 1 项"), described);
        assertTrue(answerer.seedReport().stream().anyMatch(s -> s.contains("未找到")),
                "源不存在要如实报出来，不能静默跳过让接入者以为注入成功了");
    }

    @Test
    void 未隔离时不做注入因为真实家目录本就可见(@TempDir Path dir) throws IOException {
        Path seed = dir.resolve("config.toml");
        Files.writeString(seed, "x\n", StandardCharsets.UTF_8);

        CommandAnswerer answerer = new CommandAnswerer("echo", List.of("{question}"),
                List.of(), Duration.ofSeconds(30), dir.resolve("sbx"), "none",
                List.of(seed.toString()));

        assertTrue(answerer.seedReport().isEmpty());
        assertTrue(answerer.sandboxDescription().contains("未启用进程隔离"));
    }
}
