package io.github.agentlitmus.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 关键词比对的<b>字面归一化</b>：只消除「同一种写法的不同排印」，不做任何语义推断。
 * <p>
 * 为什么需要它：标注集上实测到的一类假阴性纯粹是排印差异——被测对象答"ISO 9001"，
 * 用例期望串写的是"ISO9001"；答"8核16G"，期望写"8 核 16G"。语义完全一致，
 * 规则层却因为一个空格判它未过。这类错误不需要模型就能修，也不该留给模型修。
 * <p>
 * 归一化只做三件事，且都是<b>单向放宽</b>（只会让本来相同的写法匹配上，不会让不同的语义变相同）：
 * <ol>
 *   <li>Unicode NFKC——全角/半角、兼容字符合一（"５４３２" → "5432"）；</li>
 *   <li>去掉所有空白——空格、换行、Markdown 里为了排版插的断行都不该影响判定；</li>
 *   <li>转小写——大小写不是记忆能力的差异。</li>
 * </ol>
 * <b>刻意不做</b>的：同义词替换、词干化、模糊匹配、否定语境识别。
 * 这些都需要理解语义，做了就等于把"尺子"偷偷换成"另一把没校准的尺子"；
 * 语义层面的分歧应该走评委模型（{@code --judge}），并且它的裁决要能被标注集复核。
 */
public final class TextMatch {

    private TextMatch() {
    }

    /** 归一化：NFKC + 去空白 + 转小写 */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 归一化后的包含判定。
     *
     * @return {@code needle} 为空时返回 false（没有约束就不该算命中）
     */
    public static boolean contains(String haystack, String needle) {
        if (needle == null || needle.isBlank()) {
            return false;
        }
        return normalize(haystack).contains(normalize(needle));
    }
}
