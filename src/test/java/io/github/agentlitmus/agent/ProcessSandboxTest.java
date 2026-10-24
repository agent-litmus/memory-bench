package io.github.agentlitmus.agent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程隔离的行为测试。
 * <p>
 * 这一层是安全项，必须用<b>真实进程</b>验证——只看代码会漏掉「套了沙箱但被进程自己覆盖回去」这类问题。
 * 锁死四件事：真实 HOME 不泄漏、凭据环境变量不透传、要求内核级隔离时不静默降级、超时能回收整棵进程树。
 */
class ProcessSandboxTest {

    @TempDir
    Path tempDir;

    @Test
    void scrubsCredentialsAndKeepsRuntimeBasics() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("PATH", "/usr/bin");
        source.put("LANG", "zh_CN.UTF-8");
        source.put("OPENAI_API_KEY", "sk-secret");
        source.put("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus");
        source.put("HOME", "/home/realuser");

        Map<String, String> scrubbed = ProcessSandbox.scrub(source);

        assertEquals("/usr/bin", scrubbed.get("PATH"));
        assertEquals("zh_CN.UTF-8", scrubbed.get("LANG"));
        assertFalse(scrubbed.containsKey("OPENAI_API_KEY"), "凭据绝不允许透传");
        assertFalse(scrubbed.containsKey("DBUS_SESSION_BUS_ADDRESS"), "会话总线给了就等于给了用户会话");
        assertFalse(scrubbed.containsKey("HOME"), "HOME 由沙箱重写，不能沿用宿主值");
    }

    @Test
    void realCommandSeesSandboxHomeNotHostHome() {
        String hostHome = System.getProperty("user.home");
        CommandAnswerer answerer = new CommandAnswerer("sh",
                List.of("-c", "printf '%s' \"$HOME\""), List.of(), Duration.ofSeconds(20),
                tempDir, AgentConfig.SANDBOX_ISOLATED);

        String seen = answerer.answer("s1", "ignore");

        assertEquals(answerer.sandboxHome().toAbsolutePath().toString(), seen,
                "被测进程读到的 HOME 必须是沙箱目录");
        assertNotEquals(hostHome, seen, "宿主主目录不得暴露给被测智能体");
        assertTrue(answerer.sandboxDescription().contains("隔离"), answerer.sandboxDescription());
    }

    @Test
    void credentialsStayInsideTheJvmDuringSpawn() {
        // 通过 /proc/self/environ 读回自己的进程环境（Linux），确认 KEY 类变量没进去
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Assumptions.assumeTrue(os.contains("linux"), "依赖 /proc，仅 Linux 断言");
        CommandAnswerer answerer = new CommandAnswerer("sh",
                List.of("-c", "tr '\\0' '\\n' < /proc/self/environ"), List.of(),
                Duration.ofSeconds(20), tempDir, AgentConfig.SANDBOX_ISOLATED);

        String environ = answerer.answer("s1", "ignore");

        assertFalse(environ.toUpperCase(Locale.ROOT).contains("API_KEY"),
                "被测进程环境里出现了 API_KEY:\n" + environ);
        assertTrue(environ.contains("HOME="), environ);
    }

    @Test
    void strictModeFailsLoudlyWhenNamespaceToolMissing() throws IOException {
        if (ProcessSandbox.detectWrapper() == null) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ProcessSandbox.create(tempDir, Boolean.TRUE));
            assertTrue(e.getMessage().contains("bwrap"), e.getMessage());
        } else {
            ProcessSandbox sandbox = ProcessSandbox.create(tempDir, Boolean.TRUE);
            assertTrue(sandbox.describe().contains(sandbox.namespaceWrapper()), sandbox.describe());
        }
    }

    @Test
    void observeOnlyModeSaysItDidNotIsolate() throws IOException {
        ProcessSandbox sandbox = ProcessSandbox.observeOnly(tempDir);
        assertFalse(sandbox.isolated());
        assertTrue(sandbox.describe().contains("未启用进程隔离"), sandbox.describe());
        assertTrue(sandbox.describe().contains("不应作为安全结论引用"), sandbox.describe());

        ProcessBuilder builder = new ProcessBuilder("true");
        builder.environment().put("FOO", "bar");
        sandbox.apply(builder);
        assertEquals("bar", builder.environment().get("FOO"), "observeOnly 不应改动进程环境");
    }

    @Test
    void killTreeReachesDescendants() throws Exception {
        Assumptions.assumeTrue(Files.exists(Path.of("/proc/self")), "进程树回收断言依赖 /proc（Linux）");
        Path pidFile = tempDir.resolve("grandchild.pid");
        ProcessBuilder builder = new ProcessBuilder("sh", "-c",
                "sleep 40 & echo $! > '" + pidFile + "'; wait");
        builder.directory(tempDir.toFile());
        Process parent = builder.start();
        Thread.sleep(800);

        ProcessSandbox.killTree(parent);

        assertTrue(parent.waitFor(5, TimeUnit.SECONDS), "顶层进程应在 5 秒内退出");
        assertFalse(parent.isAlive(), "顶层进程必须被回收");
        long grandchild = Long.parseLong(Files.readString(pidFile).trim());
        Path procEntry = Path.of("/proc/" + grandchild);
        for (int i = 0; i < 20 && Files.exists(procEntry); i++) {
            Thread.sleep(100);
        }
        assertFalse(Files.exists(procEntry),
                "孙进程残留 = 智能体在评测结束后继续读写；killTree 必须整棵回收");
    }

    @Test
    void capabilitiesMatchDeclaredResetSupport() {
        CommandAnswerer withoutReset = new CommandAnswerer("sh", List.of("-c", "true"), List.of(),
                Duration.ofSeconds(5), tempDir, null);
        assertFalse(withoutReset.capabilities().contains(AgentCapability.RESETS_CONTEXT),
                "没有 resetArgs 却声明能重置，会让跨会话用例的置信度虚高");

        CommandAnswerer withReset = new CommandAnswerer("sh", List.of("-c", "true"),
                List.of("-c", "true"), Duration.ofSeconds(5), tempDir, null);
        assertTrue(withReset.capabilities().contains(AgentCapability.RESETS_CONTEXT));
    }

    @Test
    void sandboxHomePlaceholderResolves() {
        CommandAnswerer answerer = new CommandAnswerer("sh",
                List.of("-c", "printf '%s' \"" + AgentConfig.PH_SANDBOX_HOME + "/x\""), List.of(),
                Duration.ofSeconds(20), tempDir, null);
        assertEquals(answerer.sandboxHome().toAbsolutePath() + "/x", answerer.answer("s1", "q"));
    }

    @Test
    void timeoutReturnsEmptyAndDoesNotLeaveHang() throws Exception {
        CommandAnswerer answerer = new CommandAnswerer("sh",
                List.of("-c", "sleep 30 && echo late"), List.of(), Duration.ofMillis(1500),
                tempDir, null);
        long begin = System.currentTimeMillis();
        String answer = answerer.answer("s1", "q");
        assertTrue(answer.isBlank(), "超时应返回空回答，交由判定记为不可判");
        assertTrue(System.currentTimeMillis() - begin < 12_000, "超时后不应继续等待子进程");
    }
}
