package io.github.agentlitmus.cli;

import io.github.agentlitmus.dataset.MemoryCases;

import java.nio.file.Path;

/** {@code litmus export-cases}——导出内置用例集，便于在副本上扩展数据集。 */
public final class ExportCasesCommand implements Command {

    @Override
    public String name() {
        return "export-cases";
    }

    @Override
    public void run(CliOptions options) {
        Path file = Path.of(options.out).resolve("cases.json");
        MemoryCases.exportTo(Runs.loadCases(options), file);
        System.out.println("内置用例已导出到: " + file.toAbsolutePath());
        System.out.println("可在其基础上扩展数据集，再用 --cases 指定运行。");
    }
}
