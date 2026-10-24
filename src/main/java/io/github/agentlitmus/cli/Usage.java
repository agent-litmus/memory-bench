package io.github.agentlitmus.cli;

/**
 * 帮助文本。单独成类是为了可测：注册表里每个命令都必须在这里出现，
 * 漏一个（新命令忘了写用法）由 {@code CommandDispatchTest} 拦下。
 */
public final class Usage {

    private Usage() {
    }

    public static String text() {
        return """
                AgentLitmus · 智能体长期记忆评测基准

                用法:
                  litmus run [选项]              运行评测（默认内置两款智能体对比）
                  litmus run-http [选项]         通过 HTTP 对真实智能体跑评测（对话式注入）
                  litmus validate-cases [选项]   校验数据集合法性与覆盖度
                  litmus export-cases [选项]     导出内置用例集，便于扩展数据集
                  litmus rerender --from <dir> [--out <新目录>]
                                                 按已有报告重新渲染雷达图（不重跑评测）；
                                                 源目录已登记产物哈希时必须给 --out，拒绝就地改写
                  litmus verify --from <dir>     对账一次运行的产物与题集哈希（可复现校验）
                  litmus leaderboard <目录...> [--out <dir>]
                                                 汇总多份运行成一张静态榜单：读各目录 manifest.json
                                                 按有效分排序；口径或题集不一致会显著警告（跨行比较无效）
                  litmus probe --agents-config <file>
                                                 接入探测：先探清这个平台能被测到什么程度，
                                                 产出置信层级与可直接用的配置草案
                  litmus selftest [选项]         尺子自证：用标注集量判定器自己，报一致率 / κ / 弃权率，
                                                 不达标则退出码非零（可接 CI）
                  litmus regrade --from <dir> --out <dir>
                                                 离线重评：不重跑被测对象，用当前口径重判已有证据，
                                                 逐条列出裁决变化；源目录只读
                  litmus compare <A> <B> [--out <dir>]
                                                 版本回归门禁：逐题比较两次运行；显著回归或新增不可判
                                                 ⇒ 退出码非零（可接 CI）

                选项:
                  --agents <id,id,...>   指定被测智能体（内置: reference,degraded）
                  --agents-config <file> 从 JSON 文件导入智能体配置（http/command/builtin），可标注 role
                  --suite <name>         题集档位：basic/security/pressure/crosscut/standard/full（默认 basic）
                  --cases <file>         使用外部用例集 JSON（覆盖 --suite）
                  --out <dir>            输出目录（默认 litmus-out）
                  --endpoint <url>       真实智能体地址（run-http 使用）
                  --repeat <N>           同输入重复评测 N 次，产出稳定性证据（默认 1）
                  --parallel <N>         用例并行度，加速真实智能体评测（默认 1 串行；总 token 消耗不变）
                  --with-control         追加「同题双臂」对照臂，报告记忆增量 Δ（用例数与 token 翻倍）
                  --with-oracle          失败用例附上正确事实重问，区分「记不住」与「答不对」
                  --oracle-max <N>       神谕重问的条数上限（默认 40；真实智能体上重问要花 token）
                  --state-dir <dir>      被测对象的记忆落盘目录（run-http 用；给出后可升到 T2 证据）
                  --judge                启用评委模型（语义层）：接管关键词分不开的用例，
                                         密钥只从环境变量读（LITMUS_LLM_API_KEY / AI_DASHSCOPE_API_KEY）
                  --judge-model <name>   评委模型名（默认 qwen-plus）；逗号分隔多个即组成评委团，
                                         多数决裁决——平票或「不可判」占多数时记不可判，不硬判
                  --judge-endpoint <url> OpenAI 兼容的 chat/completions 端点（默认 DashScope 兼容模式）；
                                         多评委时可逗号分隔与模型一一对应，只给一个则全体共用
                  --title <text>         榜单标题（leaderboard 用）
                  --gold <file>          外置标注集（selftest 用；默认用随 jar 发布的内置标注集）
                  --from <dir>           源运行目录（verify / rerender / regrade / compare 的 A 侧）
                  --to <dir>             compare 的 B 侧；也可用两个位置参数代替 --from/--to
                  --help                 显示帮助

                示例:
                  litmus run
                  litmus run --agents reference,degraded --out result/
                  litmus run --repeat 5 --out stable/
                  litmus run --agents-config examples/agents-openkylin.json --out result/
                  litmus run --cases my-cases.json --out result/
                  litmus run --judge --judge-model qwen-plus,deepseek-chat   # 双评委团多数决
                  litmus run-http --endpoint http://localhost:8089 --out real/
                  litmus probe --agents-config examples/agents-openkylin.json --out probe-out/
                  litmus run --agents-config agents.json --with-control --with-oracle
                  litmus rerender --from samples/full-final-150
                  litmus validate-cases
                  litmus selftest --out selftest-out/
                  litmus selftest --judge        # 同时量规则层与评委层，κ 门禁生效
                  litmus regrade --from out/hermes-01 --out out/hermes-01-regraded
                  litmus compare out/hermes-01 out/hermes-02   # 回归门禁；有红线则退出码 1
                  litmus leaderboard out/hermes-01 out/kylin-01 --out board/   # 多运行汇总榜单
                """;
    }

    public static void print() {
        System.out.println(text());
    }
}
