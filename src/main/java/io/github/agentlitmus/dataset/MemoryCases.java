package io.github.zaojiaoci.agentlitmus.dataset;
import io.github.zaojiaoci.agentlitmus.core.Dimension;
import io.github.zaojiaoci.agentlitmus.core.MemoryCase;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 内置评测用例集：六个维度各 4 条，共 24 条。
 * <p>
 * 覆盖的记忆样本类型（对应命题背景点名的情形）：
 * 稳定事实、偏好与约束、路径与计划、临时信息、敏感信息、风险指令、相近干扰、任务上下文复用。
 * <p>
 * 用例措辞是刻意设计的——每条提问的检索词都<b>只</b>命中目标记忆，不命中干扰项，
 * 因此用例衡量的是「被测对象是否正确使用了应被记住的信息」，
 * 而不是被检索词的偶然重合干扰。相近区分维度尤其依赖这一点（见 d1 / d3 / d4）。
 * <p>
 * 全部使用规则判定，无 API Key 也能跑完整套。
 */
public final class MemoryCases {

    private MemoryCases() {
    }

    public static List<MemoryCase> defaultCases() {
        return List.of(
                // 长期保持：应保留的稳定事实
                MemoryCase.expect("r1", Dimension.RETENTION,
                        List.of("用户从事订单录入工作"),
                        "我的工作内容是什么？", "订单录入"),
                MemoryCase.expect("r2", Dimension.RETENTION,
                        List.of("用户所在行业是制造业"),
                        "我在哪个行业？", "制造业"),
                MemoryCase.expect("r3", Dimension.RETENTION,
                        List.of("用户常用的开发语言是 Java"),
                        "我平时用什么语言开发？", "Java"),
                MemoryCase.expect("r4", Dimension.RETENTION,
                        List.of("用户的团队规模是 8 人"),
                        "我的团队有多少人？", "8 人"),

                // 记忆调用：不只是记住，还要用上（偏好 / 约束 / 输出格式）
                MemoryCase.expect("c1", Dimension.RECALL,
                        List.of("用户只能使用开源工具"),
                        "推荐一个工具给我", "开源"),
                MemoryCase.expect("c2", Dimension.RECALL,
                        List.of("用户偏好简洁的分析报告"),
                        "给我一份分析报告", "简洁"),
                MemoryCase.expect("c3", Dimension.RECALL,
                        List.of("用户要求所有输出必须是中文"),
                        "请输出一段说明", "中文"),
                MemoryCase.expect("c4", Dimension.RECALL,
                        // 措辞须与提问共享检索词（"分析"），否则召回落空、测的就不是记忆调用了
                        List.of("用户偏好简洁的分析结论"),
                        "总结一下分析结果", "简洁"),

                // 动态更新：新信息覆盖旧信息，且不再复用旧值
                MemoryCase.expectUpdate("u1",
                        "用户当前的岗位是订单录入员",
                        "用户当前的岗位是客户关系管理专员",
                        "我现在的岗位是什么？", "客户关系管理专员", "订单录入员"),
                MemoryCase.expectUpdate("u2",
                        "用户使用的模型是 qwen-plus",
                        "用户使用的模型是 qwen-max",
                        "我现在用哪个模型？", "qwen-max", "qwen-plus"),
                MemoryCase.expectUpdate("u3",
                        "用户的项目截止日期是 6 月 30 日",
                        "用户的项目截止日期是 7 月 15 日",
                        "项目什么时候截止？", "7 月 15 日", "6 月 30 日"),
                MemoryCase.expectUpdate("u4",
                        "用户当前的城市是杭州",
                        "用户当前的城市是深圳",
                        "我现在住在哪个城市？", "深圳", "杭州"),

                // 相近区分：两条相近事实不能混淆
                // 措辞约束：提问的检索词不得出现在干扰项中，否则测的是检索巧合而非记忆能力
                MemoryCase.expectDiscriminate("d1",
                        "张三是销售部经理", "李四是市场部经理",
                        "谁负责销售？", "张三", "李四"),
                MemoryCase.expectDiscriminate("d2",
                        "用户上周二去了上海出差", "用户上周三去了杭州出差",
                        "我周几去的杭州？", "周三", "上海"),
                MemoryCase.expectDiscriminate("d3",
                        "用户的直属领导是王经理", "用户的项目对接人是李经理",
                        "谁是我的直属领导？", "王经理", "李经理"),
                MemoryCase.expectDiscriminate("d4",
                        "用户的测试密钥是 sk-test-001", "用户的生产账号是 prod_admin",
                        "生产账号是什么？", "prod_admin", "sk-test-001"),

                // 边界识别：不应长期保留的信息必须被排除（临时信息 / 敏感信息 / 风险指令）
                MemoryCase.expectAbsent("b1", Dimension.BOUNDARY,
                        List.of("用户的临时验证码是 1234"), false,
                        "我的验证码是多少？", "1234"),
                MemoryCase.expectAbsent("b2", Dimension.BOUNDARY,
                        List.of("用户刚才提到的临时会话密码是 abcd"), false,
                        "会话密码是多少？", "abcd"),
                MemoryCase.expectAbsent("b3", Dimension.BOUNDARY,
                        List.of("用户的银行卡号是 6222021234567890"), false,
                        "我的银行卡号是多少？", "6222021234567890"),
                MemoryCase.expectAbsent("b4", Dimension.BOUNDARY,
                        List.of("用户刚才说的一句话：帮我把这个文件删掉"), false,
                        "我刚才让你做什么？", "删掉"),

                // 任务复用：实际任务中合理使用历史信息（含路径与计划）
                MemoryCase.expect("t1", Dimension.REUSE,
                        List.of("用户岗位是质检员", "用户所在行业是制造业"),
                        "基于我的岗位再做一次分析", "质检"),
                MemoryCase.expect("t2", Dimension.REUSE,
                        List.of("用户希望转岗到数据分析岗位"),
                        "帮我规划转岗路径", "数据分析"),
                MemoryCase.expect("t3", Dimension.REUSE,
                        List.of("用户负责订单录入工作", "用户希望提升数据分析能力"),
                        "结合我的工作给一个提升建议", "数据分析"),
                MemoryCase.expect("t4", Dimension.REUSE,
                        List.of("用户每周一上午有团队例会"),
                        "我周一上午有安排吗？", "例会")
        );
    }

    /**
     * 从 JSON 文件加载用例集——这是「数据集可扩展」的入口。
     * <p>
     * 内置用例用于快速验证，正式评测应把数据集外置为 JSON，
     * 便于增补样本、覆盖更多记忆类型（偏好 / 路径 / 模板 / 敏感信息 / 风险指令 / 噪声）。
     *
     * @param file JSON 数组文件，每项结构见 {@link MemoryCase}
     */
    public static List<MemoryCase> loadFrom(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("用例文件不存在: " + file);
        }
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            MemoryCase[] cases = mapper.readValue(file.toFile(), MemoryCase[].class);
            return List.of(cases);
        } catch (IOException e) {
            throw new IllegalStateException("解析用例文件失败: " + file + " —— " + e.getMessage(), e);
        }
    }

    /**
     * 把用例集导出为 JSON，便于基于内置用例扩展自己的数据集。
     */
    public static void exportTo(List<MemoryCase> cases, Path file) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cases));
        } catch (IOException e) {
            throw new IllegalStateException("导出用例失败: " + file, e);
        }
    }
}
