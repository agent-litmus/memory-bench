package io.github.agentlitmus.cli;

import io.github.agentlitmus.core.RunCompare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code litmus compare}——版本回归门禁：逐题比较两次运行，有显著回归或新增不可判时
 * 以非零退出码结束，可直接挂 CI。
 * <p>
 * 两侧的裁决都是从各自证据流里<b>照抄</b>的当时读数，不重判——重判是 {@code regrade} 的事。
 * 口径或题集不一致时直接判红线：那种情况下差值同时包含「尺子变了」与「东西变了」，
 * 报出来的回归是假的，而假回归会让人去查一个不存在的问题。
 */
public final class CompareCommand implements Command {

    @Override
    public String name() {
        return "compare";
    }

    @Override
    public void run(CliOptions options) throws IOException {
        String a = options.from != null ? options.from
                : (options.positional.isEmpty() ? null : options.positional.get(0));
        String b = options.to != null ? options.to
                : (options.positional.size() > 1 ? options.positional.get(1) : null);
        if (a == null || b == null) {
            System.err.println("用法: litmus compare <基线目录> <本次目录>   （或 --from <A> --to <B>）");
            System.exit(2);
        }
        Path dirA = Path.of(a);
        Path dirB = Path.of(b);
        RunCompare.Result result = RunCompare.of(dirA, dirB);

        String text = result.toText();
        System.out.println();
        System.out.println(text);

        if (options.outExplicit) {
            Path outDir = Path.of(options.out);
            if (outDir.toAbsolutePath().equals(dirA.toAbsolutePath())
                    || outDir.toAbsolutePath().equals(dirB.toAbsolutePath())) {
                throw new IllegalArgumentException("--out 不能是被比较的运行目录之一："
                        + "写进去会让该目录的运行契约产物哈希失配");
            }
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve("compare.txt"), text, StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("compare.json"), result.toJson(), StandardCharsets.UTF_8);
            System.out.println("比较结果已输出到: " + outDir.toAbsolutePath()
                    + "（compare.txt / compare.json）");
        }
        System.out.println("退出码     : " + (result.blocked() ? "1（有红线，CI 应失败）" : "0（通过）"));
        System.exit(result.blocked() ? 1 : 0);
    }
}
