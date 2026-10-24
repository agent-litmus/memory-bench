package io.github.agentlitmus.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 单条用例的 Markdown 运行产物（transcript）。
 * <p>
 * 每条用例落盘一份 {@code artifacts/<agent>/<sessionId>.md}：注入了什么、更新了什么、问了什么、
 * 答了什么、怎么判的，一个文件看完——对应命题「将对话、记忆记录、行动轨迹、文件或其他运行产物
 * 统一组织为证据」。文件本身由运行契约（manifest）逐个哈希登记，因此它是对账对象而不只是给人看的。
 * <p>
 * 落盘失败返回 {@code null} 而不是抛异常：产物写不进去不该让整轮评测作废，
 * 但调用方会把「没有 transcript 产物」如实留在证据流里（少一条 artifact 证据），
 * 对账时也能看出来。
 */
public final class Transcript {

    private Transcript() {
    }

    /**
     * 把逐条用例的执行记录写成 Markdown。
     *
     * @param artifactDir 产物目录（各被测智能体自己的 {@code artifacts/} 子目录）
     * @param sessionId   会话 ID（形如 {@code case-u4}、重复评测时 {@code case-u4-r2}）
     * @param lines       Markdown 行（调用方已按标题组织好）
     * @return 写出的文件路径（进证据流的 {@code artifact} 内容）；失败返回 {@code null}
     */
    public static String write(Path artifactDir, String sessionId, List<String> lines) {
        try {
            if (artifactDir == null || sessionId == null || sessionId.isBlank()) {
                return null;
            }
            Files.createDirectories(artifactDir);
            Path file = artifactDir.resolve(fileNameOf(sessionId));
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            System.err.println("警告: 用例产物落盘失败（" + sessionId + "）: " + e.getMessage());
            return null;
        }
    }

    /**
     * 会话 ID → 文件名：只保留文件系统安全字符，其余折叠为 {@code -}。
     * 对照臂的会话形如 {@code case-u4:对照}，冒号在 Windows 上非法；
     * 折叠规则对同一批会话是确定性的，运行契约按产物文件名登记哈希，不受影响。
     */
    static String fileNameOf(String sessionId) {
        StringBuilder sb = new StringBuilder();
        char last = 0;
        for (char c : sessionId.toCharArray()) {
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            char mapped = safe ? c : '-';
            if (mapped == '-' && last == '-') {
                continue;    // 连续非法字符折叠成一个 -
            }
            sb.append(mapped);
            last = mapped;
        }
        if (sb.isEmpty() || sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(Math.max(0, sb.length() - 1));
        }
        return (sb.isEmpty() ? "case" : sb.toString()) + ".md";
    }
}
