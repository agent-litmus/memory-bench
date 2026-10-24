package io.github.agentlitmus.dataset;

import io.github.agentlitmus.core.MemoryCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分档题集：同一套评测流程，按<b>目的</b>切换题量。
 * <p>
 * 真实接入里，"跑多少题"取决于要回答什么问题：接一个新智能体只想先验证协议通不通，
 * 不该烧掉全量额度；要给版本回归做门禁，需要一套<b>固定不变</b>的标准集；
 * 要对外说"我们测过安全"，必须有专门的安全档而不是靠 150 道记忆题顺带覆盖。
 * <p>
 * 档位是叠加关系，不是并列的题源：
 * <pre>
 *   basic      = 内置 150（六维各 25）
 *   security   = 安全档（红线 × 同/跨会话）
 *   pressure   = 压力档（长上下文 × 干扰项）
 *   crosscut   = 交叉档（更新×边界、区分×复用）
 *   standard   = basic + security           对外可复现的标准集
 *   full       = basic + security + pressure + crosscut
 * </pre>
 * 每个档位的题目数量与构成都会写进运行报告，避免"换了题集所以分数变好"被当成能力变好。
 */
public final class CaseSuites {

    public static final String BASIC = "basic";
    public static final String SECURITY = "security";
    public static final String PRESSURE = "pressure";
    public static final String CROSSCUT = "crosscut";
    public static final String STANDARD = "standard";
    public static final String FULL = "full";

    private static final Map<String, List<String>> COMPOSITION = composition();

    private CaseSuites() {
    }

    private static Map<String, List<String>> composition() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(BASIC, List.of(BASIC));
        map.put(SECURITY, List.of(SECURITY));
        map.put(PRESSURE, List.of(PRESSURE));
        map.put(CROSSCUT, List.of(CROSSCUT));
        map.put(STANDARD, List.of(BASIC, SECURITY));
        map.put(FULL, List.of(BASIC, SECURITY, PRESSURE, CROSSCUT));
        return map;
    }

    public static List<String> names() {
        return List.copyOf(COMPOSITION.keySet());
    }

    /** @throws IllegalArgumentException 档位名不认识时列出可用档位 */
    public static List<MemoryCase> of(String suite) {
        String key = (suite == null || suite.isBlank()) ? BASIC : suite.trim().toLowerCase();
        List<String> parts = COMPOSITION.get(key);
        if (parts == null) {
            throw new IllegalArgumentException("未知题集档位: " + suite + "（可选 " + String.join("/", names()) + "）");
        }
        List<MemoryCase> cases = new ArrayList<>();
        for (String part : parts) {
            cases.addAll(source(part));
        }
        return List.copyOf(cases);
    }

    /** 单个来源的原始题集（不含档位拼装） */
    private static List<MemoryCase> source(String part) {
        return switch (part) {
            case BASIC -> MemoryCases.defaultCases();
            case SECURITY -> SecurityCases.cases();
            case PRESSURE -> PressureCases.cases();
            case CROSSCUT -> CrossCutCases.cases();
            default -> throw new IllegalArgumentException("未知题集来源: " + part);
        };
    }

    /** 该档位由哪些来源组成，用于报告里说明"题量为什么变了" */
    public static List<String> parts(String suite) {
        String key = (suite == null || suite.isBlank()) ? BASIC : suite.trim().toLowerCase();
        List<String> parts = COMPOSITION.get(key);
        return parts == null ? List.of() : parts;
    }

    public static String describe(String suite) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts(suite)) {
            sb.append(sb.isEmpty() ? "" : " + ").append(part).append('(').append(source(part).size()).append(")");
        }
        return sb.toString();
    }
}
