package io.github.agentlitmus.cli;

import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.dataset.MemoryCases;
import io.github.agentlitmus.report.RunManifest;
import io.github.agentlitmus.util.Digests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code litmus verify}——对账一次运行：重算运行契约里的每一项哈希并与目录比对。
 * <p>
 * 这是「可复现」从口号变成可检查动作的那一步——题集、规则版本、环境、产物文件任何一处被改动，
 * 本命令都会指出是哪一处，并以非零退出码结束（可用于材料归档校验）。
 */
public final class VerifyCommand implements Command {

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public void run(CliOptions options) throws IOException {
        Path runDir = Path.of(options.from == null ? CliOptions.DEFAULT_OUT : options.from);
        Path manifestFile = runDir.resolve(RunManifest.FILE_NAME);
        if (!Files.exists(manifestFile)) {
            System.err.println("未找到运行契约: " + manifestFile + "（请用 --from 指定评测输出目录）");
            System.exit(1);
        }
        RunManifest manifest = RunManifest.read(manifestFile);
        System.out.println("========== 运行契约对账 ==========");
        System.out.println("运行目录   : " + runDir.toAbsolutePath());
        System.out.println("生成时间   : " + manifest.generatedAt());
        System.out.println("判定口径   : " + manifest.rulesVersion());
        System.out.println("用例集     : " + manifest.caseCount() + " 条  sha256=" + Digests.shortForm(manifest.caseSetSha256())
                + "  档位=" + (manifest.suite() == null || manifest.suite().isBlank()
                    ? "未记录（旧契约，按 --suite 对账）" : manifest.suite()));
        System.out.println("环境       : " + manifest.environment().osName() + " " + manifest.environment().osVersion()
                + " (" + manifest.environment().osArch() + ") / JDK " + manifest.environment().javaVersion());
        System.out.println("被测对象   : " + manifest.agents().size() + " 款");
        manifest.agents().forEach(entry -> System.out.println("  - " + entry.name()
                + "  置信层级 " + entry.confidenceTier()
                + "  能力 " + (entry.capabilities().isEmpty() ? "仅对话" : entry.capabilities())
                + "  隔离 " + entry.isolation()));

        List<String> issues = new ArrayList<>(manifest.verify(runDir));

        // 题集对账：用当前代码/当前文件重算，能发现「跑结果的题集和现在仓库里的题集不是同一份」
        List<MemoryCase> cases = Runs.reconcileCases(options, manifest);
        String now = Digests.sha256Hex(MemoryCases.canonicalJson(cases));
        if (!now.equals(manifest.caseSetSha256())) {
            issues.add("题集与本次运行不一致：契约 " + Digests.shortForm(manifest.caseSetSha256())
                    + " ≠ 当前 " + Digests.shortForm(now) + "（如为外置题集请加 --cases）");
        }

        if (issues.isEmpty()) {
            System.out.println("结论       : 通过——" + manifest.files().size() + " 份产物全部与契约一致");
            System.out.println("==================================");
            return;
        }
        System.out.println("结论       : 不一致（" + issues.size() + " 项）");
        issues.forEach(issue -> System.out.println("  - " + issue));
        System.out.println("==================================");
        System.exit(1);
    }
}
