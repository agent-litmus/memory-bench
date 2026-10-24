package io.github.agentlitmus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台编码探测。
 *
 * <p>被测的目标不是「会不会编码」，而是<b>什么时候该换、什么时候不该换</b>：
 * POSIX locale 下必须换（否则整份报告变问号），Windows 的 GBK 控制台绝不能换
 * （换了才是真乱码）。判据只有一个：当前编码能不能编出「测」。
 */
class CliConsoleEncodingTest {

    private static final String[] KEYS = {
            "stdout.encoding", "sun.stdout.encoding", "native.encoding", "file.encoding"
    };

    private final String[] saved = new String[KEYS.length];

    @BeforeEach
    void backup() {
        for (int i = 0; i < KEYS.length; i++) {
            saved[i] = System.getProperty(KEYS[i]);
            System.clearProperty(KEYS[i]);
        }
    }

    @AfterEach
    void restore() {
        for (int i = 0; i < KEYS.length; i++) {
            if (saved[i] == null) {
                System.clearProperty(KEYS[i]);
            } else {
                System.setProperty(KEYS[i], saved[i]);
            }
        }
    }

    @Test
    void fallsBackToUtf8WhenJvmReportsNothing() {
        assertEquals(StandardCharsets.UTF_8, Cli.consoleCharset());
    }

    @Test
    void prefersStdoutEncodingOverNativeEncoding() {
        System.setProperty("native.encoding", "US-ASCII");
        System.setProperty("stdout.encoding", "UTF-8");
        assertEquals(StandardCharsets.UTF_8, Cli.consoleCharset());
    }

    @Test
    void picksUpSunStdoutEncodingOnJava17() {
        // Java 17 没有 stdout.encoding，只有 sun.stdout.encoding；漏掉它就等于在 17 上不生效
        System.setProperty("sun.stdout.encoding", "ANSI_X3.4-1968");
        assertEquals(Charset.forName("US-ASCII"), Cli.consoleCharset());
    }

    @Test
    void ignoresUnparsableValueAndKeepsLooking() {
        System.setProperty("stdout.encoding", "这不是一个编码名");
        System.setProperty("native.encoding", "US-ASCII");
        assertEquals(Charset.forName("US-ASCII"), Cli.consoleCharset());
    }

    @Test
    void asciiConsoleIsTheOneThatNeedsReplacing() {
        assertFalse(Charset.forName("US-ASCII").newEncoder().canEncode('测'),
                "POSIX locale 下编不出中文——正是要换成 UTF-8 的那种环境");
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode('测'));
    }

    @Test
    void gbkConsoleMustNotBeReplaced() {
        // Windows 中文控制台是 GBK：它编得出中文，若也强行换成 UTF-8，
        // 字节流与控制台代码页不符，反而变成真乱码
        assertTrue(Charset.forName("GBK").newEncoder().canEncode('测'),
                "GBK 能表示中文，不应触发替换");
    }
}
