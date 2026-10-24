package io.github.agentlitmus.cli;

import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.report.ReportRates;
import io.github.agentlitmus.report.RunManifest;
import io.github.agentlitmus.util.Digests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code litmus leaderboard}——把多份已完成的运行汇总成一张静态榜单。
 * <p>
 * 每份运行目录自带运行契约（manifest），其中已有各智能体的有效分 / 覆盖率 / 悲观下界
 * 与口径、题集、环境指纹——榜单<b>只读契约，不重算分数</b>，因此它给出的每个数字
 * 都能用 {@code litmus verify --from <目录>} 当场对账。这正是「榜单可信」的来源：
 * 不要求读者相信榜单作者，只要求每行能回溯到一份可核验的运行。
 * <p>
 * 两条防误读规则写进产物而不是靠读者自觉：
 * <ul>
 *   <li><b>口径或题集不一致的行不排在一起</b>——不同口径下的分数没有可比性，
 *       出现混用立即在榜单顶部警告（这批数字来自不同版本的尺子）；</li>
 *   <li><b>对账失败的行标记出来</b>——产物哈希与契约不符的目录，分数仍列出（不销毁信息），
 *       但标明「对账不一致」，读者自行取舍。</li>
 * </ul>
 * 没有契约的目录（例如旧版运行、或只有 report.txt 的样例目录）按 report.txt 解析六维，
 * 以六维平均分排序并标明「无运行契约」——这类行的数字<b>未经验证</b>，只是尽量不丢弃。
 */
public final class LeaderboardCommand implements Command {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 一行榜单：一款智能体在一份运行里的成绩与来源 */
    record Row(String agent, double score, double coverage, double lowerBound,
               int total, int unknown, String rulesVersion, String tier,
               String runDir, boolean verified, boolean hasContract, String note) {
    }

    @Override
    public String name() {
        return "leaderboard";
    }

    @Override
    public void run(CliOptions options) throws IOException {
        if (options.positional.isEmpty()) {
            System.err.println("用法: litmus leaderboard <运行目录>... [--out <dir>] [--title <标题>]");
            System.exit(2);
        }
        Path outDir = Path.of(options.out);
        for (String dir : options.positional) {
            if (outDir.toAbsolutePath().normalize().equals(Path.of(dir).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("--out 不能是榜单来源目录之一（" + dir
                        + "）：榜单只读各运行目录，写进去会破坏其运行契约的产物哈希");
            }
        }
        Files.createDirectories(outDir);

        List<Row> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> rulesVersions = new LinkedHashSet<>();
        Set<String> caseSets = new LinkedHashSet<>();
        Map<String, String> provenance = new LinkedHashMap<>();   // 目录 → 一行来源说明

        for (String dir : options.positional) {
            Path runDir = Path.of(dir);
            Path manifestFile = runDir.resolve(RunManifest.FILE_NAME);
            if (Files.exists(manifestFile)) {
                collectFromManifest(runDir, rows, rulesVersions, caseSets, provenance, problems);
            } else if (Files.exists(runDir.resolve("report.txt"))) {
                collectFromReport(runDir, rows, provenance);
            } else {
                problems.add(dir + ": 没有 " + RunManifest.FILE_NAME + " 也没有 report.txt，已跳过");
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("没有任何目录产出可用的成绩行：" + problems);
        }
        rows.sort(Comparator.comparingDouble(Row::score).reversed()
                .thenComparing(Comparator.comparingDouble(Row::coverage).reversed())
                .thenComparing(Comparator.comparingDouble(Row::lowerBound).reversed())
                .thenComparing(Row::agent));

        List<String> warnings = new ArrayList<>();
        if (rulesVersions.size() > 1) {
            warnings.add("⚠ 混用判定口径: " + String.join(", ", rulesVersions)
                    + " —— 口径不同则分数含义不同，跨行比较无效（可用 litmus regrade 统一到当前口径）");
        }
        if (caseSets.size() > 1) {
            warnings.add("⚠ 题集不一致: " + caseSets.size() + " 套题集（sha256 各不同）"
                    + " —— 题目都不一样，排名只是罗列，不是比较");
        }
        if (rows.stream().anyMatch(r -> !r.hasContract())) {
            warnings.add("⚠ 有行来自没有运行契约的目录（按 report.txt 解析、未经验证），行内已标明");
        }

        String title = options.title == null || options.title.isBlank()
                ? "AgentLitmus 榜单 · " + LocalDateTime.now().format(FMT) : options.title.trim();
        String text = renderText(title, rows, warnings, problems, provenance);
        System.out.println();
        System.out.println(text);
        Files.writeString(outDir.resolve("leaderboard.txt"), text, StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("leaderboard.html"), renderHtml(title, rows, warnings,
                problems, provenance), StandardCharsets.UTF_8);
        System.out.println("榜单已输出到: " + outDir.toAbsolutePath()
                + "\n  ├─ leaderboard.txt   纯文本（可贴材料）"
                + "\n  └─ leaderboard.html  自包含网页（浏览器打开即见）");
    }

    /** 从运行契约取成绩：分数、覆盖率、下界都是契约里的原值，不做任何重算 */
    private void collectFromManifest(Path runDir, List<Row> rows, Set<String> rulesVersions,
                                     Set<String> caseSets, Map<String, String> provenance,
                                     List<String> problems) {
        RunManifest manifest = RunManifest.read(runDir.resolve(RunManifest.FILE_NAME));
        rulesVersions.add(manifest.rulesVersion());
        caseSets.add(Digests.shortForm(manifest.caseSetSha256()));
        List<String> issues = manifest.verify(runDir);
        boolean verified = issues.isEmpty();
        Map<String, String> tierByName = new LinkedHashMap<>();
        manifest.agents().forEach(e -> tierByName.put(e.name(), e.confidenceTier()));
        for (RunManifest.AgentScore score : manifest.scores().values()) {
            rows.add(new Row(score.name(), score.effectiveRate(), score.coverage(), score.lowerBound(),
                    score.total(), score.unknown(), manifest.rulesVersion(),
                    tierByName.getOrDefault(score.name(), "—"), runDir.toString(), verified, true,
                    verified ? "" : "对账不一致：" + issues.get(0)));
        }
        provenance.put(runDir.toString(), runDir + " · " + manifest.caseCount() + " 题（"
                + (manifest.suite() == null || manifest.suite().isBlank() ? "外置题集" : manifest.suite())
                + "，sha256 " + Digests.shortForm(manifest.caseSetSha256()) + "）· 口径 "
                + manifest.rulesVersion() + " · v" + manifest.litmusVersion()
                + (manifest.gitCommit() == null || "unknown".equals(manifest.gitCommit())
                        ? "" : "(" + manifest.gitCommit() + ")")
                + " · " + manifest.environment().osName() + "/" + manifest.environment().osArch()
                + " · JDK " + manifest.environment().javaVersion()
                + " · " + manifest.generatedAt()
                + (verified ? "" : " · ⚠ 对账不一致（" + issues.size() + " 项）"));
    }

    /** 没有契约的目录：按 report.txt 解析六维，六维平均当排序分——数字未经验证，如实标注 */
    private void collectFromReport(Path runDir, List<Row> rows, Map<String, String> provenance)
            throws IOException {
        Map<String, Map<Dimension, Double>> series = ReportRates.parse(runDir.resolve("report.txt"));
        for (Map.Entry<String, Map<Dimension, Double>> entry : series.entrySet()) {
            double mean = entry.getValue().values().stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0);
            rows.add(new Row(entry.getKey(), mean, Double.NaN, Double.NaN, -1, -1,
                    "未知（无契约）", "—", runDir.toString(), false, false, "无运行契约，按 report.txt 六维平均排序"));
        }
        provenance.put(runDir.toString(), runDir + " · 无 manifest.json，按 report.txt 解析（未经验证）");
    }

    private String renderText(String title, List<Row> rows, List<String> warnings,
                              List<String> problems, Map<String, String> provenance) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== ").append(title).append(" ==========\n");
        sb.append("共 ").append(rows.size()).append(" 行，来自 ").append(provenance.size()).append(" 份运行；")
                .append("分数 = 有效分（通过 / 已判定），全部取自各目录运行契约的原值\n");
        warnings.forEach(w -> sb.append(w).append('\n'));
        sb.append('\n');
        sb.append("排名  智能体                        有效分    覆盖率    悲观下界  不可判     口径        置信  运行（对账状态）\n");
        sb.append("----  ----------------------------  ------   ------   ------   --------   ----------  ----  ------------------\n");
        int rank = 1;
        for (Row row : rows) {
            String unknown = row.total() < 0 ? "—"
                    : row.unknown() + "/" + row.total();
            sb.append(String.format(Locale.ROOT, "%-4d  %-28s  %5.1f%%   %s   %s   %9s   %-10s  %-4s  %s%s%n",
                    rank++, row.agent(), row.score() * 100,
                    row.total() < 0 ? "  —  " : String.format(Locale.ROOT, "%5.1f%%", row.coverage() * 100),
                    row.total() < 0 ? "  —  " : String.format(Locale.ROOT, "%5.1f%%", row.lowerBound() * 100),
                    unknown, row.rulesVersion(), row.tier(),
                    row.runDir, row.hasContract() ? (row.verified() ? "（对账通过）" : "（⚠ 对账不一致）")
                            : "（无契约，未验证）"));
            if (!row.note().isBlank()) {
                sb.append("      ").append(row.note()).append('\n');
            }
        }
        sb.append("\n── 各次运行来源 ──\n");
        provenance.values().forEach(p -> sb.append("  · ").append(p).append('\n'));
        if (!problems.isEmpty()) {
            sb.append("\n── 未纳入的目录 ──\n");
            problems.forEach(p -> sb.append("  · ").append(p).append('\n'));
        }
        sb.append("\n每行的数字可用 litmus verify --from <运行目录> 当场对账；混入口径的行请先 regrade 再比较。\n");
        return sb.toString();
    }

    private String renderHtml(String title, List<Row> rows, List<String> warnings,
                              List<String> problems, Map<String, String> provenance) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <title>AgentLitmus 榜单</title>
                <style>
                  body { font-family: -apple-system, "Segoe UI", "Noto Sans CJK SC", sans-serif;
                         margin: 32px; color: #222; background: #fafafa; }
                  h1 { font-size: 24px; margin-bottom: 4px; }
                  .sub { color: #666; font-size: 14px; margin-bottom: 24px; }
                  .card { background: #fff; border: 1px solid #e0e0e0; border-radius: 8px;
                          padding: 20px; margin-bottom: 20px; }
                  h2 { font-size: 18px; margin-top: 0; }
                  table { border-collapse: collapse; width: 100%; font-size: 14px; }
                  th, td { border: 1px solid #e0e0e0; padding: 8px 10px; text-align: left; }
                  th { background: #f0f0f0; }
                  td.num { text-align: right; }
                  .tag { display: inline-block; font-size: 12px; padding: 1px 8px; border-radius: 10px;
                         background: #eee; color: #555; margin-left: 8px; }
                  .tag.warn { background: #fff3e0; color: #ef6c00; }
                  .tag.bad { background: #ffebee; color: #c62828; }
                  .warn-line { color: #c62828; font-weight: bold; margin: 6px 0; }
                  pre { background: #f5f5f5; padding: 12px; border-radius: 6px;
                        overflow-x: auto; font-size: 13px; }
                </style>
                </head>
                <body>
                """);
        sb.append("<h1>").append(escape(title)).append("</h1>");
        sb.append("<div class=\"sub\">共 ").append(rows.size()).append(" 行，来自 ").append(provenance.size())
                .append(" 份运行；分数 = 有效分（通过 / 已判定），全部取自各目录运行契约的原值。</div>");

        if (!warnings.isEmpty()) {
            sb.append("<div class=\"card\">");
            warnings.forEach(w -> sb.append("<p class=\"warn-line\">").append(escape(w)).append("</p>"));
            sb.append("</div>");
        }

        sb.append("<div class=\"card\"><h2>榜单</h2><table>");
        sb.append("<tr><th>排名</th><th>智能体</th><th>有效分</th><th>覆盖率</th><th>悲观下界</th>")
                .append("<th>不可判</th><th>口径</th><th>置信层级</th><th>运行目录</th><th>对账</th></tr>");
        int rank = 1;
        for (Row row : rows) {
            sb.append("<tr><td class=\"num\">").append(rank++).append("</td>");
            sb.append("<td>").append(escape(row.agent())).append("</td>");
            sb.append("<td class=\"num\"><b>").append(pct(row.score())).append("</b></td>");
            sb.append("<td class=\"num\">").append(row.total() < 0 ? "—" : pct(row.coverage())).append("</td>");
            sb.append("<td class=\"num\">").append(row.total() < 0 ? "—" : pct(row.lowerBound())).append("</td>");
            sb.append("<td class=\"num\">").append(row.total() < 0 ? "—" : row.unknown() + "/" + row.total()).append("</td>");
            sb.append("<td>").append(escape(row.rulesVersion())).append("</td>");
            sb.append("<td>").append(escape(row.tier())).append("</td>");
            sb.append("<td>").append(escape(row.runDir())).append("</td>");
            if (!row.hasContract()) {
                sb.append("<td><span class=\"tag warn\">无契约，未验证</span></td>");
            } else if (row.verified()) {
                sb.append("<td><span class=\"tag\">对账通过</span></td>");
            } else {
                sb.append("<td><span class=\"tag bad\">对账不一致</span></td>");
            }
            sb.append("</tr>");
            if (!row.note().isBlank()) {
                sb.append("<tr><td></td><td colspan=\"9\" class=\"sub\">").append(escape(row.note()))
                        .append("</td></tr>");
            }
        }
        sb.append("</table></div>");

        sb.append("<div class=\"card\"><h2>各次运行来源</h2><pre>");
        provenance.values().forEach(p -> sb.append(escape(p)).append('\n'));
        sb.append("</pre>");
        if (!problems.isEmpty()) {
            sb.append("<h2>未纳入的目录</h2><pre>");
            problems.forEach(p -> sb.append(escape(p)).append('\n'));
            sb.append("</pre>");
        }
        sb.append("</div>");
        sb.append("<div class=\"sub\">每行的数字可用 <code>litmus verify --from &lt;运行目录&gt;</code> 当场对账；")
                .append("混入口径的行请先 <code>litmus regrade</code> 再比较。</div>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String pct(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
