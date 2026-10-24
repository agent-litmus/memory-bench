package io.github.agentlitmus.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 工具：为「可复现」提供可对账的锚点。
 * <p>
 * 一句"结果完全可复现"是无法验证的；把用例集、证据文件、规则版本各自哈希后写进运行契约，
 * 任何人都能用 {@code litmus verify} 重算并逐项对账——复现从声明变成可检查的事实。
 */
public final class Digests {

    private Digests() {
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256File(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 截断显示用：完整哈希留在文件里，人读的清单只看前 12 位 */
    public static String shortForm(String hex) {
        return hex == null || hex.length() < 12 ? hex : hex.substring(0, 12);
    }
}
