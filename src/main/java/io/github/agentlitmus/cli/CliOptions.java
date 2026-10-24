package io.github.agentlitmus.cli;

import io.github.agentlitmus.agent.Agents;
import io.github.agentlitmus.core.OracleRecheck;
import io.github.agentlitmus.dataset.CaseSuites;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 命令行参数解析。
 * <p>
 * 所有子命令共用一份参数模型与一条解析规则：<b>带值参数恰好消费下一个 token，
 * 布尔开关不消费任何 token</b>。旧版解析对 {@code --cases} 与 {@code --agents}
 * 用了两套取值方式，边界输入（缺值、值以 {@code --} 开头）行为互相矛盾——
 * 统一后缺值立即报错，而不是把 {@code "--cases"} 自己当成文件名留给运行期才炸。
 * <p>
 * 未知开关仍然静默忽略（保持旧行为）：评测一旦开跑动辄几十分钟，
 * 不能因为一个拼写错误让用户白等一轮；位置参数收进 {@link #positional}（compare / leaderboard 用）。
 */
public final class CliOptions {

    public static final String DEFAULT_OUT = "litmus-out";

    /** 不带值参数的开关：解析时不能被当作「上一个参数的值」吞掉 */
    private static final Set<String> FLAG_ONLY = Set.of("--with-control", "--with-oracle", "--judge",
            "--help", "-h");

    public String agents;
    public String agentsConfig;
    public String cases;
    public String suite = CaseSuites.BASIC;
    public String out = DEFAULT_OUT;
    public String endpoint;
    public int repeat = 1;
    public int parallel = 1;
    public boolean withControl;
    public boolean withOracle;
    public String stateDir;
    public boolean judge;
    /** 评委模型名；逗号分隔多个即组成评委团（多数决），见 {@code Runs#buildRuler} */
    public String judgeModel;
    /** 评委端点；单个时全体评委共用，多个时与 {@link #judgeModel} 一一对应 */
    public String judgeEndpoint;
    public String gold;
    public int oracleMax = OracleRecheck.DEFAULT_MAX_CASES;
    public String from;
    public String to;
    /** 榜单标题（leaderboard 用；默认按时间生成） */
    public String title;
    /** {@code --out} 是否显式给出：compare 只在显式给出时才写产物，避免默认目录撞上被比较的运行目录 */
    public boolean outExplicit;
    public final List<String> positional = new ArrayList<>();

    private CliOptions() {
    }

    public static CliOptions parse(List<String> argv) {
        CliOptions options = new CliOptions();
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            switch (arg) {
                case "--agents" -> options.agents = value(argv, ++i, arg);
                case "--cases" -> options.cases = value(argv, ++i, arg);
                case "--suite" -> options.suite = value(argv, ++i, arg);
                case "--out" -> {
                    options.out = value(argv, ++i, arg);
                    options.outExplicit = true;
                }
                case "--endpoint" -> options.endpoint = value(argv, ++i, arg);
                case "--agents-config" -> options.agentsConfig = value(argv, ++i, arg);
                case "--from" -> options.from = value(argv, ++i, arg);
                case "--to" -> options.to = value(argv, ++i, arg);
                case "--title" -> options.title = value(argv, ++i, arg);
                case "--with-control" -> options.withControl = true;
                case "--judge" -> options.judge = true;
                case "--judge-model" -> options.judgeModel = value(argv, ++i, arg);
                case "--judge-endpoint" -> options.judgeEndpoint = value(argv, ++i, arg);
                case "--gold" -> options.gold = value(argv, ++i, arg);
                case "--with-oracle" -> options.withOracle = true;
                case "--state-dir" -> options.stateDir = value(argv, ++i, arg);
                case "--oracle-max" -> options.oracleMax = intOf(argv, ++i, arg,
                        OracleRecheck.DEFAULT_MAX_CASES, "默认 " + OracleRecheck.DEFAULT_MAX_CASES);
                case "--parallel" -> options.parallel = intOf(argv, ++i, arg, 1, "默认 1");
                case "--repeat" -> options.repeat = intOf(argv, ++i, arg, 1, "默认 1");
                default -> {
                    // 位置参数（compare / leaderboard 的运行目录）收下来；未知开关忽略，避免中断评测
                    if (!arg.startsWith("-")) {
                        options.positional.add(arg);
                    }
                }
            }
        }
        if (options.agents == null || options.agents.isBlank()) {
            options.agents = String.join(",", Agents.defaultIds());
        }
        return options;
    }

    /** 带值参数的取值：缺失、或下一个 token 是另一个开关，都视为缺值 */
    private static String value(List<String> argv, int index, String flag) {
        if (index >= argv.size() || argv.get(index).startsWith("--")) {
            throw new IllegalArgumentException("缺少参数值: " + flag);
        }
        return argv.get(index);
    }

    /** 整型参数：无效值告警并保留默认，不让一次手滑毁掉一轮几十分钟的评测 */
    private static int intOf(List<String> argv, int index, String flag, int fallback, String hint) {
        String raw = argv.get(index);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            System.err.println("警告: " + flag + " 参数无效，已忽略（使用" + hint + "）");
            return fallback;
        }
    }
}
