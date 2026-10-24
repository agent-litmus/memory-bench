package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.AgentCapability;
import io.github.agentlitmus.agent.Answerer;
import io.github.agentlitmus.evidence.Evidence;
import io.github.agentlitmus.evidence.EvidenceCollector;
import io.github.agentlitmus.evidence.Transcript;
import io.github.agentlitmus.memory.MemorySnapshotter;
import io.github.agentlitmus.report.BenchmarkReport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Set;

/**
 * 对话式评测执行器——面向<b>真实智能体</b>。
 * <p>
 * 与 {@link MemoryBenchmarkRunner} 的区别在于「记忆如何建立」：
 * <ul>
 *   <li>{@link MemoryBenchmarkRunner}：直接把事实写入被测对象背后的记忆存储，适合评测记忆系统本身；</li>
 *   <li>本执行器：把事实<b>当作用户说的话</b>发给智能体，由它自己在对话中记住——
 *       这正是真实智能体建立记忆的方式，外部无法（也不应）代劳。</li>
 * </ul>
 * 因此本执行器<b>不依赖任何记忆实现</b>，只要被测对象能对话即可评测，
 * 可用于 HTTP 服务、命令行智能体、任意 LLM 应用。
 */
public class DialogueBenchmarkRunner {

    private final Answerer agent;
    private final CaseJudge judge;
    private final EvidenceCollector collector;
    private final Path artifactDir;
    private final List<Path> memoryRoots;

    public DialogueBenchmarkRunner(Answerer agent, CaseJudge judge) {
        this(agent, judge, new EvidenceCollector());
    }

    public DialogueBenchmarkRunner(Answerer agent, CaseJudge judge, EvidenceCollector collector) {
        this(agent, judge, collector, null);
    }

    /**
     * @param artifactDir 运行产物目录（每条用例一个 Markdown 文件）；为 null 表示不落盘
     */
    public DialogueBenchmarkRunner(Answerer agent, CaseJudge judge,
                                   EvidenceCollector collector, Path artifactDir) {
        this(agent, judge, collector, artifactDir, List.of());
    }

    /**
     * @param memoryRoots 被测对象的记忆落盘目录（来自配置的 {@code stateDir}）。
     *                    非空且被测对象具备 {@link AgentCapability#MEMORY_INSPECTABLE} 时，
     *                    每条用例会附带<b>字节级状态差异</b>作为记忆证据——「没写进去」不再靠猜。
     */
    public DialogueBenchmarkRunner(Answerer agent, CaseJudge judge, EvidenceCollector collector,
                                   Path artifactDir, List<Path> memoryRoots) {
        this.agent = agent;
        this.judge = judge;
        this.collector = collector == null ? new EvidenceCollector() : collector;
        this.artifactDir = artifactDir;
        this.memoryRoots = memoryRoots == null ? List.of() : List.copyOf(memoryRoots);
    }

    public EvidenceCollector collector() {
        return collector;
    }

    public BenchmarkReport run(List<MemoryCase> cases) {
        return run(cases, 1);
    }

    /**
     * 并行执行用例。
     * <p>
     * 用例之间彼此独立——各自独占 sessionId 与隔离的记忆目录，因此可安全并发。
     * 瓶颈在等待模型响应（I/O 密集），并发能把墙钟时间近乎线性地压下来；
     * 而<b>总 token 消耗不变</b>：回合数不变，只是从「排队等」变成「同时等」。
     * <p>
     * 结果严格按用例原顺序收集，输出与串行完全一致，可复现性不受影响。
     *
     * @param parallelism 并行度（{@code <=1} 表示串行）
     */
    public BenchmarkReport run(List<MemoryCase> cases, int parallelism) {
        int n = Math.max(1, parallelism);
        if (n == 1 || cases.size() <= 1) {
            List<Judgment> judgments = new ArrayList<>();
            for (MemoryCase kase : cases) {
                judgments.add(runOne(kase));
            }
            return new BenchmarkReport(judgments);
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Judgment>> futures = new ArrayList<>(cases.size());
            for (MemoryCase kase : cases) {
                futures.add(pool.submit(() -> runOne(kase)));
            }
            List<Judgment> judgments = new ArrayList<>(cases.size());
            for (Future<Judgment> f : futures) {
                judgments.add(f.get());
            }
            return new BenchmarkReport(judgments);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并行评测被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("并行评测失败: " + cause.getMessage(), cause);
        } finally {
            pool.shutdown();
        }
    }

    public Judgment runOne(MemoryCase kase) {
        String sessionId = kase.sessionId();
        Set<AgentCapability> capabilities = agent.capabilities();
        AgentCapability required = kase.requiredCapability();
        if (required != null && !capabilities.contains(required)) {
            return skipForCapability(kase, required);
        }
        boolean snapshotMemory = !memoryRoots.isEmpty()
                && capabilities.contains(AgentCapability.MEMORY_INSPECTABLE);
        List<MemorySnapshotter.Snapshot> before = snapshotMemory ? captureAll() : List.of();

        List<String> transcript = new ArrayList<>();
        transcript.add("# 用例 " + kase.id() + "（" + kase.dimension().label() + "）"
                + (kase.crossSession() ? " ｜ 跨会话" : ""));
        transcript.add("");

        // 1) 通过对话注入事实（用户先把信息告诉智能体）
        transcript.add("## 注入事实");
        for (String seed : kase.seeds()) {
            collector.collect(Evidence.dialogue(sessionId, seed, Evidence.ROLE_SEED));
            transcript.add("- " + seed);
            agent.answer(sessionId, seed);
        }
        transcript.add("");

        // 2) 动态更新：告知新事实，看它是否覆盖旧信息
        if (kase.updateTo() != null && !kase.updateTo().isBlank()) {
            collector.collect(Evidence.dialogue(sessionId, kase.updateTo(), Evidence.ROLE_UPDATE));
            transcript.add("## 更新事实");
            transcript.add("- " + kase.updateTo());
            transcript.add("");
            agent.answer(sessionId, kase.updateTo());
        }

        // 2.5) 跨会话：重置对话上下文（长期记忆保留），确保答对只能来自长期记忆
        if (kase.crossSession()) {
            collector.collect(Evidence.dialogue(sessionId,
                    "[评测] 重置会话上下文：清空当前对话，仅保留长期记忆", Evidence.ROLE_NOTE));
            transcript.add("## 会话重置");
            transcript.add("- 已清空对话上下文，仅保留长期记忆");
            transcript.add("");
            agent.resetSession(sessionId);
        }

        // 3) 提问并判定
        collector.collect(Evidence.dialogue(sessionId, kase.question(), Evidence.ROLE_QUESTION));
        String answer = agent.answer(sessionId, kase.question());
        collector.collect(Evidence.dialogue(sessionId, answer == null ? "" : answer, Evidence.ROLE_ANSWER));

        Judgment judgment = judge.judge(kase, answer);
        collector.collect(Evidence.judgment(sessionId, judgment.toString(), judgment.verdict().name()));

        // 3.5) 记忆状态证据：本条用例前后，落盘目录到底变了什么
        if (snapshotMemory) {
            transcript.add("## 记忆状态变化");
            transcript.addAll(stateDiffLines(sessionId, before, captureAll()));
            transcript.add("");
        }

        // 3.6) 行动证据：只收评测方<b>自己观测到</b>的进程级事实（命令行/退出码/耗时）。
        //      被测对象自报的「我调用了某工具」不进这一层——自报无法核对，混进来等于把证据变成声明。
        List<String> actions = agent.drainObservedActions();
        if (!actions.isEmpty()) {
            transcript.add("## 行动轨迹（评测方观测）");
            for (String action : actions) {
                collector.collect(Evidence.action(sessionId, action, "process"));
                transcript.add("- " + action);
            }
            transcript.add("");
        }

        // 4) 把整条执行过程落盘为文件产物，并登记为证据
        transcript.add("## 提问");
        transcript.add("- " + kase.question());
        transcript.add("");
        transcript.add("## 智能体回答");
        transcript.add(answer == null || answer.isBlank() ? "（空回答）" : answer);
        transcript.add("");
        transcript.add("## 判定");
        transcript.add("- " + judgment);
        String path = Transcript.write(artifactDir, sessionId, transcript);
        if (path != null) {
            collector.collect(Evidence.artifact(sessionId, path, "transcript"));
        }
        return judgment;
    }

    /**
     * 前提不成立的用例直接判<b>不可判</b>并移出分母，同时把这件事写成产物留证。
     * <p>
     * 这是全项目唯一一处「不提问就出结论」的路径，理由是：这类题在该平台上<b>问不出有效信息</b>。
     * 例如跨会话题要求清空上下文只留长期记忆，若平台复位不了，答对可能只是窗口里还留着原文——
     * 判它通过是虚高，判它失败是给它扣一顶「我们测不了」的帽子。两个都是假数字。
     */
    private Judgment skipForCapability(MemoryCase kase, AgentCapability required) {
        String reason = "该用例要求「" + required.label() + "」，此平台未确认具备（由 probe 探测或配置声明）："
                + "提问无法区分「靠上下文」与「靠长期记忆」，故判不可判、移出分母，不计为失败";
        Judgment judgment = Judgment.degraded(kase.id(), kase.dimension(), reason,
                FailureCause.NOT_OBSERVABLE);
        collector.collect(Evidence.dialogue(kase.sessionId(), "[评测] " + reason, Evidence.ROLE_NOTE));
        // 裁决产物必须留一条：能力门控跳过是「不可判」，若证据流里没有这条记录，
        // 离线重评与跨运行比较都会把这道题看成「压根没这道题」，于是覆盖率下降变得不可见。
        collector.collect(Evidence.judgment(kase.sessionId(), judgment.toString(), judgment.verdict().name()));
        List<String> transcript = new ArrayList<>();
        transcript.add("# 用例 " + kase.id() + "（" + kase.dimension().label() + "）｜ 未执行");
        transcript.add("");
        transcript.add("## 原因");
        transcript.add("- " + reason);
        transcript.add("");
        transcript.add("## 判定");
        transcript.add("- " + judgment);
        String path = Transcript.write(artifactDir, kase.sessionId(), transcript);
        if (path != null) {
            collector.collect(Evidence.artifact(kase.sessionId(), path, "transcript"));
        }
        return judgment;
    }

    private List<MemorySnapshotter.Snapshot> captureAll() {
        List<MemorySnapshotter.Snapshot> snapshots = new ArrayList<>(memoryRoots.size());
        for (Path root : memoryRoots) {
            snapshots.add(new MemorySnapshotter(root).capture());
        }
        return snapshots;
    }

    /** 每个状态目录一行差异；同时登记为记忆证据，让「写没写进去」在证据流里可直接查到 */
    private List<String> stateDiffLines(String sessionId,
                                        List<MemorySnapshotter.Snapshot> before,
                                        List<MemorySnapshotter.Snapshot> after) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < memoryRoots.size(); i++) {
            MemorySnapshotter.Snapshot previous = i < before.size() ? before.get(i) : null;
            MemorySnapshotter.Snapshot current = i < after.size() ? after.get(i) : null;
            String diff = MemorySnapshotter.describeDiff(previous, current)
                    + " ｜ 目录=" + memoryRoots.get(i);
            lines.add("- " + diff);
            collector.collect(Evidence.memory(sessionId, diff, "state-delta"));
        }
        return lines;
    }
}
