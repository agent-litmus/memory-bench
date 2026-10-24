package io.github.agentlitmus.cli;

import io.github.agentlitmus.core.MemoryCase;
import io.github.agentlitmus.dataset.CoverageReport;

import java.util.List;

/** {@code litmus validate-cases}——校验数据集合法性与覆盖度（数据治理入口）。 */
public final class ValidateCasesCommand implements Command {

    @Override
    public String name() {
        return "validate-cases";
    }

    @Override
    public void run(CliOptions options) {
        List<MemoryCase> cases = Runs.loadCases(options);
        CoverageReport coverage = CoverageReport.of(cases);
        System.out.println(coverage.toText());
        System.exit(coverage.valid() ? 0 : 1);
    }
}
