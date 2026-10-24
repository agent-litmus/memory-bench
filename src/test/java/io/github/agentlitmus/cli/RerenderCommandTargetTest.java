package io.github.agentlitmus.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rerender} 的落盘目标选择。
 *
 * <p>它保护的不是图好不好看，而是<b>对账能力</b>：雷达图和 HTML 里的内嵌 SVG
 * 都在 {@code manifest.json} 的产物哈希清单里。就地重渲染等于改写完再让
 * {@code verify} 报「产物被改动」——用户只是想换张图，却把自己的运行记录弄坏了。
 */
class RerenderCommandTargetTest {

    @Test
    void explicitOutIsUsedVerbatim(@TempDir Path from, @TempDir Path out) {
        assertEquals(out.toAbsolutePath().normalize(),
                RerenderCommand.rerenderTarget(from, out, true));
    }

    @Test
    void sameOutAndFromIsRefused(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RerenderCommand.rerenderTarget(dir, dir, true));
        assertTrue(e.getMessage().contains("源目录需要只读"), e.getMessage());
    }

    @Test
    void inPlaceRefusedWhenManifestAlreadyRecordsHashes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RerenderCommand.rerenderTarget(dir, dir, false));
        assertTrue(e.getMessage().contains("--out"), e.getMessage());
    }

    @Test
    void inPlaceAllowedWhenNothingToReconcile(@TempDir Path dir) {
        assertEquals(dir.toAbsolutePath().normalize(), RerenderCommand.rerenderTarget(dir, dir, false));
    }

    /** 回归位：一次真实运行留下的目录（含 manifest）必须守住自己的哈希 */
    @Test
    void rerenderOnVerifiedRunLeavesSourceUntouched(@TempDir Path from, @TempDir Path out)
            throws IOException {
        Files.writeString(from.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(from.resolve("report.txt"), "表头\n参考智能体 100% 100%\n", StandardCharsets.UTF_8);
        Files.writeString(from.resolve("report.html"),
                "<html><svg a=\"1\">old</svg></html>", StandardCharsets.UTF_8);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> RerenderCommand.rerenderTarget(from, out, false));
        assertTrue(refused.getMessage().contains("画图命令"), refused.getMessage());

        Path target = RerenderCommand.rerenderTarget(from, out, true);
        assertEquals("<svg a=\"1\">old</svg>",
                extractFirst(Files.readString(from.resolve("report.html"), StandardCharsets.UTF_8)));
        assertTrue(Files.notExists(target.resolve("report.html")), "产物只能落在 --out 里");
    }

    @Test
    void replaceFirstSvgDistinguishesMissingBlockFromNoChange() {
        // 没有 <svg> 块时必须返回 null，不能和「替换后内容与原文一致」混为一谈
        assertNull(RerenderCommand.replaceFirstSvg("<html>无图</html>", "<svg>n</svg>"));
        assertNull(RerenderCommand.replaceFirstSvg("<html><svg>没闭合", "<svg>n</svg>"));
        assertEquals("<html><svg>n</svg></html>",
                RerenderCommand.replaceFirstSvg("<html><svg>o</svg></html>", "<svg>n</svg>"));
        assertEquals("<html><svg>o</svg></html>",
                RerenderCommand.replaceFirstSvg("<html><svg>o</svg></html>", "<svg>o</svg>"));
    }

    private static String extractFirst(String html) {
        int start = html.indexOf("<svg");
        int end = html.indexOf("</svg>", start) + "</svg>".length();
        return html.substring(start, end);
    }
}
