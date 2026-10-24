package io.github.agentlitmus.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 非答案识别：被测端返回的<b>状态占位文本</b>不是回答，判定不应发生。
 * <p>
 * 为什么这属于导管问题而不是语义问题：这类文本和 HTTP 429、超时、空串是同一类事实——
 * 它们描述的是"这一轮没拿到回答"，与记忆能力无关。把它们算成失败，等于让被测端的
 * 排队机制冒充记忆缺陷；算成通过，等于什么都没测。所以走和空回答完全相同的处理：
 * 判为<b>不可判</b>，移出分母并公示。
 * <p>
 * 标注集上实测到这一类：Hermes 的 ACP 客户端在被测对象还在排队时会返回
 * {@code "Queued for the next turn. (1 queued)"}，规则层把它当成有效回答，
 * 3 条样本因此被误判（1 条假阳、2 条假阴）。
 * <p>
 * <b>刻意收窄的两条边界</b>：
 * <ol>
 *   <li>只做<b>整串匹配</b>——状态文本一定是整条回答的全部内容，绝不会是一段真回答里的一句话。
 *       子串匹配会让"我帮你把任务排队了（Queued for the next turn）"这种正常回答被误杀；</li>
 *   <li>词表是<b>经验性的、逐条注明来源</b>，不做任何语义推断。识别"这段话没有实质内容"
 *       需要理解语义，那该交给评委层，不该由一个正则偷偷替它做决定。</li>
 * </ol>
 */
public final class NonAnswer {

    /**
     * 已知状态占位文本。每条都注明它是在哪个被测端上观测到的。
     */
    private static final List<Pattern> PATTERNS = List.of(
            // Hermes ACP：被测对象仍在排队时客户端返回的占位行
            Pattern.compile("queued for the next turn\\.?\\s*\\(\\d+ queued\\)"),
            // 评测产物里用来表示"这一轮没有回答"的占位（导出/回填时被当成回答读进来）
            Pattern.compile("（空回答）|\\(空回答\\)|\\(no response\\)|\\[no response\\]")
    );

    private NonAnswer() {
    }

    /** @return 命中的状态文本（已归一为小写去空白的形式）；未命中返回 {@code null} */
    public static String detect(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = trimmed.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(normalized).matches()) {
                return trimmed;
            }
        }
        return null;
    }

    public static boolean isNonAnswer(String text) {
        return detect(text) != null;
    }
}
