package io.github.agentlitmus.cli;

import io.github.agentlitmus.core.CaseJudge;
import io.github.agentlitmus.core.RulerSelfTest;
import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.dataset.CaseSuites;
import io.github.agentlitmus.dataset.GoldSet;
import io.github.agentlitmus.llm.JudgeCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code litmus selftest}——尺子自证：用标注集量<b>判定器自己</b>，报一致率 / Cohen's κ / 弃权率与逐条分歧。
 * <p>
 * 这条命令不接触任何被测对象，因此可以进 CI：每次改判定规则都自动对账，
 * 防止"改了尺子却以为改的是结论"。
 * <p>
 * 门禁只卡<b>能判的</b>项：规则层的开口即对率必须达标；评委层的 κ 只有在评委真的启用时才算，
 * 没启用就写"不可判"而不是算它通过——用缺席的评委去证明尺子准，是这套工具在别处一律拒绝的动作。
 */
public final class SelftestCommand implements Command {

    @Override
    public String name() {
        return "selftest";
    }

    @Override
    public void run(CliOptions options) throws IOException {
        Path outDir = Path.of(options.out);
        Files.createDirectories(outDir);
        GoldSet gold = options.gold == null || options.gold.isBlank()
                ? GoldSet.bundled() : GoldSet.load(Path.of(options.gold));
        List<MemoryCase> pool = CaseSuites.of(CaseSuites.FULL);

        List<String> notes = new ArrayList<>();
        JudgeCache cache = null;
        CaseJudge judgeRuler = null;
        if (options.judge) {
            cache = JudgeCache.open(outDir.resolve("judge-cache.json"));
            judgeRuler = Runs.buildRuler(options, cache, notes);
            if (!judgeRuler.llmAvailable()) {
                judgeRuler = null;
            }
        } else {
            notes.add("未启用（加 --judge 可同时量评委层，并让 κ 门禁生效）");
        }

        RulerSelfTest.Result result = RulerSelfTest.run(gold, pool, judgeRuler,
                String.join("；", notes), cache);
        if (cache != null) {
            cache.save();
        }

        String text = result.toText();
        System.out.println();
        System.out.println(text);
        Files.writeString(outDir.resolve("selftest.txt"), text, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("selftest.json"), result.toJson(), StandardCharsets.UTF_8);
        System.out.println("自证结果已输出到: " + outDir.toAbsolutePath()
                + "\n  ├─ selftest.txt  人读报告（一致率 / κ / 弃权率 / 分歧明细）"
                + "\n  └─ selftest.json 结构化结果（含混淆矩阵，可接 CI）");

        List<String> failures = result.gateFailures();
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }
}
