package io.github.agentlitmus.probe;

import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.memory.MemorySnapshotter;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 接入探针：在<b>跑评测之前</b>先问一句「这个平台到底能被测到什么程度」。
 * <p>
 * 为什么需要它：接入一个新智能体时，最容易出的错不是配置写错，而是<b>默认它能被这样测</b>。
 * 例如跨会话用例要求「清空对话上下文、只留长期记忆」——如果一个平台的会话根本复位不了，
 * 这些用例答对是因为窗口里还留着原文，答错则可能是我们没清干净。两种情况下分数都不成立。
 * <p>
 * 探针用<b>虚构标记词</b>做几组极小的对话（每次 2～6 回合），再用字节扫描核对落盘情况，
 * 输出观测到的能力、置信层级和一份可直接用的配置草案。它有两处刻意保守：
 * <ul>
 *   <li>「测不出来」一律记 {@code NOT_TESTABLE}，不记成缺陷；</li>
 *   <li>能力只在<b>观测成立</b>或<b>接入方声明</b>时才授予，探针绝不替被测对象「猜」自己有。</li>
 * </ul>
 * 标记词全部为虚构实体（紫鸢 / 青隼 / 白鹭），不含真实人名、口令或敏感信息，
 * 因此探针本身不会把内容写进任何真实系统之外。
 */
public final class AgentProber {

    /** 标记词命名空间：虚构主体 + 4 位十六进制，足够在一堆记忆里唯一 */
    private static final String[] SUBJECTS = {"紫鸢", "青隼", "白鹭", "赤鸮"};

    private static final int MAX_EXCERPT = 160;

    private final Answerer answerer;
    private final Set<AgentCapability> declared;
    private final List<Path> scanRoots;

    public AgentProber(Answerer answerer, Set<AgentCapability> declared, List<Path> scanRoots) {
        if (answerer == null) {
            throw new IllegalArgumentException("探针必须有一个可对话的被测对象");
        }
        this.answerer = answerer;
        this.declared = declared == null ? Set.of() : Set.copyOf(declared);
        List<Path> roots = new ArrayList<>();
        if (scanRoots != null) {
            for (Path root : scanRoots) {
                if (root != null) {
                    roots.add(root.toAbsolutePath());
                }
            }
        }
        this.scanRoots = List.copyOf(roots);
    }

    /** 探测一个被测对象；{@code agentId} 用于隔离会话，避免与真实评测串会话 */
    public ProbeResult probe(String agentId, String agentName) {
        String id = agentId == null || agentId.isBlank() ? "probed" : agentId;
        List<ProbeStep> steps = new ArrayList<>();
        Map<String, String> markers = new LinkedHashMap<>();
        EnumSet<AgentCapability> observed = EnumSet.noneOf(AgentCapability.class);
        SecureRandom random = new SecureRandom();

        // 1) 标记词能被记住吗（同会话）——后面几步都以此为前提
        String memorySession = "probe-" + id + "-mem";
        String recallMarker = marker(0, random);
        markers.put("recall", recallMarker);
        answer(memorySession, "请记住这个标记词：" + recallMarker + "，稍后我会问它。");
        String sameSession = answer(memorySession, "我让你记住的标记词是什么？");
        boolean recalled = contains(sameSession, recallMarker);
        steps.add(new ProbeStep("同会话能答出自己刚被要求记住的标记词", null,
                recalled ? ProbeStep.Result.YES : ProbeStep.Result.NO,
                "回答：" + excerpt(sameSession)));

        // 2) 重置上下文后还答得出吗——答得出只能来自长期记忆
        answerer.resetSession(memorySession);
        String crossSession = answer(memorySession, "我让你记住的标记词是什么？");
        boolean persisted = contains(crossSession, recallMarker);
        steps.add(new ProbeStep("重置会话后仍能答出标记词（长期记忆生效）", null,
                persisted ? ProbeStep.Result.YES : ProbeStep.Result.NO,
                "回答：" + excerpt(crossSession)
                        + (recalled ? "" : "（注意：同会话也答不出，本步结论不可靠）")));

        // 3) RESETS_CONTEXT：会话边界是否真的能清掉
        ProbeStep resetStep = probeContextReset(id);
        steps.add(resetStep);
        if (resetStep.result() == ProbeStep.Result.YES) {
            observed.add(AgentCapability.RESETS_CONTEXT);
        }

        // 4) MEMORY_INSPECTABLE：标记词是否真的落盘、我们能不能读到
        ProbeStep inspectStep = probeMemoryReadable(recallMarker);
        steps.add(inspectStep);
        if (inspectStep.result() == ProbeStep.Result.YES) {
            observed.add(AgentCapability.MEMORY_INSPECTABLE);
        }

        // 5) STATE_RESETTABLE：评测方有没有权限改写状态目录
        ProbeStep resettableStep = probeStateResettable();
        steps.add(resettableStep);
        if (resettableStep.result() == ProbeStep.Result.YES) {
            observed.add(AgentCapability.STATE_RESETTABLE);
        }

        // 6) ARTIFACTS_VISIBLE：让它写一个带标记词的文件，再看我们能否取回
        String artifactMarker = marker(2, random);
        markers.put("artifact", artifactMarker);
        ProbeStep artifactStep = probeArtifacts(id, artifactMarker);
        steps.add(artifactStep);
        if (artifactStep.result() == ProbeStep.Result.YES) {
            observed.add(AgentCapability.ARTIFACTS_VISIBLE);
        }

        // 7) TOOL_CALLS_VISIBLE：黑盒测不到，只能由接入方声明（如实写出来）
        steps.add(new ProbeStep("工具调用过程可见", AgentCapability.TOOL_CALLS_VISIBLE,
                ProbeStep.Result.NOT_TESTABLE,
                "仅有对话输出时无法观测工具调用；需要 T3 请在配置 capabilities 里声明，"
                        + "或改用能收到 tool_call 事件的适配器（如 ACP）"));

        Set<AgentCapability> granted = EnumSet.noneOf(AgentCapability.class);
        granted.addAll(observed);
        granted.addAll(declared);
        // 适配器自己声明的能力本就成立（例如 CLI 适配器确认过有 resetArgs），一并计入
        granted.addAll(answerer.capabilities());

        List<String> warnings = warnings(observed, granted, persisted);
        return new ProbeResult(id, agentName == null || agentName.isBlank() ? id : agentName,
                steps, observed, declared, Set.copyOf(granted),
                scanRoots.isEmpty() ? null : scanRoots.get(0), warnings, markers);
    }

    // ------------------------------------------------------------------ 各步实现

    /**
     * 会话重置探测：先在上下文里留一句<b>不要求记住</b>的闲话，重置后再问。
     * <p>
     * 只有「重置前答得出、重置后答不出」才说明边界真被清掉了。
     * 重置前就答不出的平台不能据此判 NO——它可能是压根没用上下文，
     * 与「重置失败」是两回事，硬判会把一个测不准的现象写成一个缺陷。
     */
    private ProbeStep probeContextReset(String id) {
        String session = "probe-" + id + "-ctx";
        String marker = marker(1, new SecureRandom());
        String question = "我刚才随口说的那句闲话里的编号是什么？";
        answer(session, "我说一句闲话：" + marker + "，这句不用记。");
        String beforeReset = answer(session, question);
        answerer.resetSession(session);
        String afterReset = answer(session, question);
        boolean before = contains(beforeReset, marker);
        boolean after = contains(afterReset, marker);
        String detail = "重置前回答：" + excerpt(beforeReset)
                + " ｜ 重置后回答：" + excerpt(afterReset);
        ProbeStep.Result result;
        if (!before) {
            result = ProbeStep.Result.NOT_TESTABLE;
        } else {
            result = after ? ProbeStep.Result.NO : ProbeStep.Result.YES;
        }
        return new ProbeStep("上下文可清空且只保留长期记忆", AgentCapability.RESETS_CONTEXT,
                result, before ? detail : detail + "（重置前就答不出，无法据此判断重置是否生效）");
    }

    private ProbeStep probeMemoryReadable(String marker) {
        if (scanRoots.isEmpty()) {
            return new ProbeStep("记忆落盘内容可读取", AgentCapability.MEMORY_INSPECTABLE,
                    ProbeStep.Result.NOT_TESTABLE,
                    "未提供 stateDir：没有可扫描目录，「写没写进去」只能靠回答推断");
        }
        List<String> hits = new ArrayList<>();
        for (Path root : scanRoots) {
            for (MemorySnapshotter.Hit hit : new MemorySnapshotter(root).findText(marker, 3)) {
                hits.add(root.getFileName() + "/" + hit.path() + " ×" + hit.count());
            }
        }
        if (!hits.isEmpty()) {
            return new ProbeStep("记忆落盘内容可读取", AgentCapability.MEMORY_INSPECTABLE,
                    ProbeStep.Result.YES,
                    "在状态目录中扫到标记词：" + excerpt(String.join("；", hits)));
        }
        return new ProbeStep("记忆落盘内容可读取", AgentCapability.MEMORY_INSPECTABLE,
                ProbeStep.Result.NO,
                "扫描 " + scanRoots.size() + " 个目录（" + scanRoots + "）未发现标记词 " + marker
                        + "：可能是目录给错、记忆不落在此处，或它根本没有持久化");
    }

    private ProbeStep probeStateResettable() {
        if (scanRoots.isEmpty()) {
            return new ProbeStep("状态目录可由评测方复位", AgentCapability.STATE_RESETTABLE,
                    ProbeStep.Result.NOT_TESTABLE, "未提供 stateDir");
        }
        List<String> writable = new ArrayList<>();
        for (Path root : scanRoots) {
            if (new MemorySnapshotter(root).writable()) {
                writable.add(root.toString());
            }
        }
        return new ProbeStep("状态目录可由评测方复位", AgentCapability.STATE_RESETTABLE,
                writable.isEmpty() ? ProbeStep.Result.NO : ProbeStep.Result.YES,
                (writable.isEmpty() ? "所有状态目录都不可写" : "可增删文件的目录：" + writable)
                        + "（判据是权限可行，不等于复位后等于出厂干净状态）");
    }

    /**
     * 产物可取回：要求它把一个带标记词的文件写进工作目录，再扫描我们可见的根目录。
     * 对命令行智能体，工作目录就是隔离沙箱家目录，因此扫描得到即证明「产物在我们手里」。
     */
    private ProbeStep probeArtifacts(String id, String marker) {
        String session = "probe-" + id + "-art";
        answer(session, "请把标记词 " + marker + " 写进一个名为 litmus-probe.txt 的文件，放在你的工作目录里。");
        if (scanRoots.isEmpty()) {
            return new ProbeStep("任务产物可取回", AgentCapability.ARTIFACTS_VISIBLE,
                    ProbeStep.Result.NOT_TESTABLE, "未提供可扫描目录，无法确认产物是否产生");
        }
        for (Path root : scanRoots) {
            List<MemorySnapshotter.Hit> hits = new MemorySnapshotter(root).findText(marker, 1);
            if (!hits.isEmpty()) {
                return new ProbeStep("任务产物可取回", AgentCapability.ARTIFACTS_VISIBLE,
                        ProbeStep.Result.YES,
                        "在 " + root + " 取回产物：" + hits.get(0).path() + "，内容含标记词");
            }
        }
        return new ProbeStep("任务产物可取回", AgentCapability.ARTIFACTS_VISIBLE,
                ProbeStep.Result.NO,
                "已要求在可见目录写出 " + marker + "，但扫描后未取回该文件（该维度用例在此平台应移出分母）");
    }

    private List<String> warnings(EnumSet<AgentCapability> observed,
                                  Set<AgentCapability> granted, boolean persistedCrossSession) {
        List<String> warnings = new ArrayList<>();
        if (scanRoots.isEmpty()) {
            warnings.add("未提供 stateDir：本次探测只能给对话级证据（T1）。"
                    + "补上记忆目录可把「没写进去」从推断变观测。");
        }
        if (!granted.contains(AgentCapability.RESETS_CONTEXT)) {
            warnings.add("上下文无法确认复位：跨会话用例将被判为不可判并移出分母，"
                    + "而不是算失败——这些题在该平台测不出「长期」二字。");
        }
        if (declared.contains(AgentCapability.TOOL_CALLS_VISIBLE)
                && !observed.contains(AgentCapability.TOOL_CALLS_VISIBLE)) {
            warnings.add("TOOL_CALLS_VISIBLE 来自配置声明，探针无法验证（黑盒看不到工具调用），按声明采信。");
        }
        if (!persistedCrossSession) {
            warnings.add("重置会话后答不出标记词：若同会话能答出，说明信息只存在于上下文，"
                    + "长期记忆链路可能未写入——这正是本工具要量化的缺陷。");
        }
        return warnings;
    }

    // ------------------------------------------------------------------ 小工具

    private String answer(String session, String text) {
        try {
            String reply = answerer.answer(session, text);
            return reply == null ? "" : reply;
        } catch (Exception e) {
            // 探针里任何一次调用失败都只让该步结论降级，不让整个探测中断
            return "";
        }
    }

    private static String marker(int subjectIndex, SecureRandom random) {
        String hex = Integer.toHexString(random.nextInt(0xFFFF) & 0xFFFF).toUpperCase(Locale.ROOT);
        return SUBJECTS[subjectIndex % SUBJECTS.length] + "-" + "0000".substring(hex.length()) + hex;
    }

    private static boolean contains(String answer, String marker) {
        return answer != null && marker != null && !marker.isBlank() && answer.contains(marker);
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "（空）";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return "（空回答）";
        }
        return flat.length() <= MAX_EXCERPT ? flat : flat.substring(0, MAX_EXCERPT) + "…";
    }
}
