package io.github.agentlitmus.cli;

import io.github.agentlitmus.report.RunManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 榜单命令的排序与防误读规则。
 *
 * <p>榜单的价值不在「排出名次」，而在<b>每个数字都能回溯到一份可对账的运行</b>：
 * 分数取自运行契约原值（不重算）、口径混用立即警告、无契约目录明确标注未验证。
 * 这些规则断在测试里，因为它们是榜单可信的前提，不是锦上添花的文案。
 */
class LeaderboardCommandTest {

    private static RunManifest manifest(String rulesVersion, String caseSetSha, String agentName,
                                        double effective, double coverage, double lower, int total, int unknown) {
        return new RunManifest(RunManifest.SCHEMA_VERSION, "2026-10-24T10:00:00Z", "1.0.0", "abc1234",
                rulesVersion, caseSetSha, 283, "full",
                new RunManifest.Environment("Linux", "3.0", "amd64", "21", "Temurin"),
                new RunManifest.Params(1, 4),
                List.of(new RunManifest.AgentEntry(agentName, agentName, "DIALOGUE", "PRIMARY",
                        "RecallAnswerer", "", List.of(), "T1", "内置确定性被测：不启动外部进程")),
                Map.of(agentName, new RunManifest.AgentScore(agentName, effective, coverage, lower,
                        total, total - unknown, unknown)),
                Map.of());
    }

    @Test
    void ranksByEffectiveScoreAndFlagsMixedRules(@TempDir Path tmp) throws IOException {
        Path dirA = Files.createDirectories(tmp.resolve("run-a"));
        manifest("2026.10-c", "a".repeat(64), "甲智能体", 0.85, 0.99, 0.84, 283, 3).writeTo(dirA);
        Path dirB = Files.createDirectories(tmp.resolve("run-b"));
        manifest("2026.10-b", "b".repeat(64), "乙智能体", 0.42, 0.95, 0.40, 283, 14).writeTo(dirB);
        Path dirC = Files.createDirectories(tmp.resolve("run-c"));
        Files.writeString(dirC.resolve("report.txt"), """
                ========== 多智能体横向对比 ==========
                智能体                   长期保持  记忆调用  动态更新  相近区分  边界识别  任务复用  总体
                丙智能体                     92%      56%      44%      72%      60%      76%     67%
                ====================================
                """, StandardCharsets.UTF_8);
        Path board = tmp.resolve("board");

        CliOptions options = CliOptions.parse(List.of());
        options.positional.addAll(List.of(dirA.toString(), dirB.toString(), dirC.toString()));
        options.out = board.toString();
        new LeaderboardCommand().run(options);

        String text = Files.readString(board.resolve("leaderboard.txt"), StandardCharsets.UTF_8);
        int rankA = text.indexOf("甲智能体");
        int rankC = text.indexOf("丙智能体");
        int rankB = text.indexOf("乙智能体");
        assertTrue(rankA >= 0 && rankC > rankA && rankB > rankC,
                "排序应为 甲(85%) > 丙(六维均 66.7%) > 乙(42%):\n" + text);
        assertTrue(text.contains("混用判定口径"), "口径 2026.10-b/c 混用必须警告:\n" + text);
        assertTrue(text.contains("无运行契约"), "report.txt 来源必须标注未验证:\n" + text);
        assertTrue(text.contains("对账通过"), "契约完整且产物未动的目录应标对账通过:\n" + text);
        assertTrue(Files.exists(board.resolve("leaderboard.html")));
        assertTrue(text.contains("--judge-model") || text.contains("regrade"),
                "混用口径的行应提示先 regrade 再比较:\n" + text);
    }

    @Test
    void refuseToWriteIntoSourceRunDir(@TempDir Path tmp) throws IOException {
        Path dirA = Files.createDirectories(tmp.resolve("run-a"));
        manifest("2026.10-c", "a".repeat(64), "甲智能体", 0.85, 0.99, 0.84, 283, 3).writeTo(dirA);

        CliOptions options = CliOptions.parse(List.of());
        options.positional.add(dirA.toString());
        options.out = dirA.toString();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new LeaderboardCommand().run(options));
        assertTrue(e.getMessage().contains("榜单只读"), e.getMessage());
    }

    @Test
    void directoryWithoutAnyUsableFileIsReportedNotSilent(@TempDir Path tmp) throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Path board = tmp.resolve("board");

        CliOptions options = CliOptions.parse(List.of());
        options.positional.add(empty.toString());
        options.out = board.toString();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new LeaderboardCommand().run(options));
        assertTrue(e.getMessage().contains("没有任何目录产出"), e.getMessage());
    }
}
