package io.github.agentlitmus.core;

import io.github.agentlitmus.agent.RecallAnswerer;
import io.github.agentlitmus.dataset.MemoryCases;
import io.github.agentlitmus.memory.FileLongTermMemory;
import io.github.agentlitmus.report.BenchmarkReport;
import io.github.agentlitmus.report.DeltaByMemory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「同题双臂」对照臂的行为测试。
 * <p>
 * 这是本工具里唯一能回答「这道题到底在不在测记忆」的机制，
 * 因此必须锁死两件事：对照臂<b>确实没有留下任何可回忆的事实</b>，以及双臂差值<b>确实为正</b>。
 * 任何一处走样（比如忘了抹掉 updateTo，或对照臂 ID 与主臂撞车），本测试都会红。
 */
class ControlArmTest {

    @TempDir
    Path tempDir;

    @Test
    void controlTwinsCarryNoInjectedFacts() {
        List<MemoryCase> cases = MemoryCases.defaultCases();
        List<MemoryCase> twins = ControlArms.twinsOf(cases);

        assertFalse(twins.isEmpty());
        for (MemoryCase twin : twins) {
            assertTrue(ControlArms.isControl(twin.id()), "对照臂 ID 必须可识别: " + twin.id());
            assertEquals(1, twin.seeds().size(), "对照臂只应保留中性开场: " + twin.id());
            assertFalse(twin.seeds().get(0).isBlank());
            assertTrue(twin.updateTo() == null || twin.updateTo().isBlank(),
                    "对照臂必须抹掉动态更新，否则新事实仍会被注入: " + twin.id());
            assertEquals(ControlArms.baseId(twin.id()) + ":对照", twin.id());
            assertFalse(twin.sessionId().contains(":"), "会话 ID 会被用作文件名，不能含冒号");
        }
        // 边界识别类（有「不应出现」期望）没有记忆也天然通过，不进对照组
        long boundary = cases.stream().filter(c -> c.dimension() == Dimension.BOUNDARY).count();
        assertTrue(twins.size() < cases.size(), "带反向期望的用例应被排除");
        assertTrue(twins.size() <= cases.size() - boundary, "边界识别类不应出现在对照组");
    }

    @Test
    void deltaIsPositiveWhenMemoryActuallyUsed() {
        List<MemoryCase> cases = MemoryCases.defaultCases().stream()
                .filter(c -> c.dimension() == Dimension.RETENTION
                        || c.dimension() == Dimension.RECALL)
                .toList();
        FileLongTermMemory memory = new FileLongTermMemory(tempDir);
        MemoryBenchmarkRunner runner = new MemoryBenchmarkRunner(
                memory, new RecallAnswerer(memory), new CaseJudge());

        BenchmarkReport main = runner.run(cases);
        BenchmarkReport control = runner.run(ControlArms.twinsOf(cases));

        List<Judgment> all = new ArrayList<>(main.judgments());
        all.addAll(control.judgments());
        DeltaByMemory delta = DeltaByMemory.of(new BenchmarkReport(all));

        assertTrue(delta.hasControls());
        // 主臂靠记忆全对，对照臂无记忆可召回 ⇒ Δ 必须显著为正
        assertTrue(delta.overallDelta() > 0.5,
                "记忆增量应显著为正，否则说明题目不依赖记忆:\n" + delta.toText());
        assertTrue(delta.toText().contains("Δ"), delta.toText());
    }

    @Test
    void noDeltaWithoutControlArm() {
        FileLongTermMemory memory = new FileLongTermMemory(tempDir);
        BenchmarkReport report = new MemoryBenchmarkRunner(
                memory, new RecallAnswerer(memory), new CaseJudge())
                .run(MemoryCases.defaultCases().subList(0, 5));

        DeltaByMemory delta = DeltaByMemory.of(report);
        assertFalse(delta.hasControls());
        assertTrue(delta.toText().contains("未跑对照臂"), delta.toText());
    }
}
