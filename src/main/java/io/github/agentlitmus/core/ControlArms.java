package io.github.agentlitmus.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 同题双臂：把一道题拆成「有记忆」与「无记忆」两条臂，差值才是记忆的贡献。
 * <p>
 * 只报绝对通过率会有一类无法反驳的质疑：<b>这道题它不靠记忆也能答对</b>（常识、上下文残留、
 * 预训练知识蒙对）。加上一条"同样的提问、同样的判定，但从未注入任何事实"的对照臂，
 * 超出对照组的那部分才归因于记忆。这是把 67% 这种数字变成有因果含义的唯一办法。
 * <p>
 * 对照臂的开场白刻意与提问无任何词面重合，避免检索词偶然命中——否则对照组会虚高，
 * Δ 被压小，反而低估记忆的作用。
 */
public final class ControlArms {

    /** 与任何提问都不沾边的中性开场，只用于维持"对话发生过"这一事实 */
    private static final List<String> NEUTRAL_SEEDS =
            List.of("先随便说一句，今天下午我在整理桌面文件。");

    private ControlArms() {
    }

    /** 用例 ID 后缀：对照臂与主臂可区分、又可配对 */
    public static String controlId(String caseId) {
        return caseId + ":对照";
    }

    public static boolean isControl(String caseId) {
        return caseId != null && caseId.endsWith(":对照");
    }

    public static String baseId(String caseId) {
        return isControl(caseId) ? caseId.substring(0, caseId.length() - ":对照".length()) : caseId;
    }

    /**
     * 为每条用例生成对照臂副本（去掉全部注入事实与更新），保持提问与判定完全一致。
     * <p>
     * 边界识别类用例的期望是"不该出现的别出现"，没有记忆时它天然满足，放进对照组只会把 Δ 拉成负的噪声，
     * 因此这类题不参与双臂差值统计。
     */
    public static List<MemoryCase> twinsOf(List<MemoryCase> cases) {
        List<MemoryCase> controls = new ArrayList<>();
        for (MemoryCase kase : cases) {
            if (isControl(kase.id()) || kase.expectedNotContains() != null && !kase.expectedNotContains().isBlank()) {
                continue;
            }
            controls.add(kase.withId(controlId(kase.id()))
                    .withSeeds(NEUTRAL_SEEDS)
                    .withoutUpdate()
                    .withSessionId("case-" + kase.id() + "-control"));
        }
        return controls;
    }

    /** 把主臂与对照臂合并成一次运行的用例清单 */
    public static List<MemoryCase> withControls(List<MemoryCase> cases) {
        List<MemoryCase> all = new ArrayList<>(cases);
        all.addAll(twinsOf(cases));
        return all;
    }
}
