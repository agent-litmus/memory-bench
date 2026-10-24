package io.github.agentlitmus.cli;

import io.github.agentlitmus.core.CaseJudge;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.core.Regrade;
import io.github.agentlitmus.llm.JudgeCache;
import io.github.agentlitmus.report.RunManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code litmus regrade}——离线重评：不重跑被测对象，只用<b>当前</b>口径把一次已完成运行里的回答重判一遍。
 * <p>
 * 产物一律写到 {@code --out}，源目录只读——写回源目录会让它自己的运行契约哈希全部失配，
 * 等于为了重评毁掉原始证据。因此 {@code --out} 与 {@code --from} 相同直接报错。
 */
public final class RegradeCommand implements Command {

    @Override
    public String name() {
        return "regrade";
    }

    @Override
    public void run(CliOptions options) throws IOException {
        Path runDir = Path.of(options.from == null ? CliOptions.DEFAULT_OUT : options.from);
        Path manifestFile = runDir.resolve(RunManifest.FILE_NAME);
        if (!Files.exists(manifestFile)) {
            System.err.println("未找到运行契约: " + manifestFile
                    + "（请用 --from 指定评测输出目录；没有契约就无法确认这批数字出自哪个口径）");
            System.exit(1);
        }
        Path outDir = Path.of(options.out);
        if (outDir.toAbsolutePath().equals(runDir.toAbsolutePath())) {
            throw new IllegalArgumentException("--out 不能与 --from 相同："
                    + "重评对源目录只读，写进去会让源运行契约的产物哈希全部失配");
        }
        Files.createDirectories(outDir);

        RunManifest manifest = RunManifest.read(manifestFile);
        List<MemoryCase> pool = Runs.reconcileCases(options, manifest);
        List<String> notes = new ArrayList<>();
        JudgeCache cache = options.judge ? JudgeCache.open(outDir.resolve("judge-cache.json")) : null;
        CaseJudge ruler = Runs.buildRuler(options, cache, notes);
        System.out.println("重评对象   : " + runDir.toAbsolutePath() + "（只读）");
        System.out.println("题集       : " + pool.size() + " 条（档位 "
                + (options.cases == null ? String.valueOf(manifest.suite()) : "外置文件") + "）");
        notes.forEach(n -> System.out.println("评委层: " + n));

        Regrade.Result result = Regrade.run(runDir, pool, ruler);
        if (cache != null) {
            cache.save();
        }

        String text = result.toText();
        System.out.println();
        System.out.println(text);
        Files.writeString(outDir.resolve("regrade.txt"), text, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("regrade.json"), result.toJson(), StandardCharsets.UTF_8);
        System.out.println("重评结果已输出到: " + outDir.toAbsolutePath()
                + "\n  ├─ regrade.txt   逐条裁决变化（人读）"
                + "\n  └─ regrade.json  结构化结果（脚本消费）");
        if (!result.usable()) {
            System.exit(1);
        }
    }
}
