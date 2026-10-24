package io.github.agentlitmus;

import io.github.agentlitmus.cli.Usage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令分发的完整性：注册表里的每个命令都必须有实现、都必须出现在帮助文本里。
 * <p>
 * 这两道检查防的都是同一件事——新加子命令时漏了一环（注册了没文档，或文档里写了
 * 一个不存在命令），用户要么发现不了新功能，要么照着帮助敲一个不存在的命令吃退出码 2。
 */
class CommandDispatchTest {

    @Test
    void registryCoversAllDocumentedCommands() {
        Map<String, io.github.agentlitmus.cli.Command> commands = Cli.commands();
        for (String name : new String[]{"run", "run-http", "export-cases", "validate-cases",
                "rerender", "verify", "leaderboard", "probe", "selftest", "regrade", "compare"}) {
            assertNotNull(commands.get(name), "命令未注册: " + name);
            assertEquals(name, commands.get(name).name());
        }
    }

    @Test
    void everyRegisteredCommandAppearsInUsage() {
        String usage = Usage.text();
        Cli.commands().keySet().forEach(name ->
                assertTrue(usage.contains("litmus " + name + " ") || usage.contains("litmus " + name + "\n"),
                        "帮助文本缺少命令用法: litmus " + name));
    }

    @Test
    void unknownCommandNamesAreNotRegistered() {
        assertNull(Cli.commands().get("run-fast"));
    }
}
