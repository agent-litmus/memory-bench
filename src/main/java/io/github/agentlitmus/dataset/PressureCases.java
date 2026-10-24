package io.github.agentlitmus.dataset;

import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.MemoryCase;

import java.util.ArrayList;
import java.util.List;

/**
 * 压力档用例：测「<b>信息量大、干扰多、隔得久</b>」之后还记得多少。
 * <p>
 * 基础档一道题只注入 1–2 条事实，任何有索引的记忆系统都能答对；真实使用里一次会话会产生
 * 十几条互不相干的事实，还夹着闲聊与中途修正。压力档刻意把注入量拉到 6–12 条并塞入干扰项，
 * 让「检索排序」和「容量」这两个真实失效面暴露出来。
 * <p>
 * 两条硬约束：
 * <ul>
 *   <li>提问<b>不复述期望答案</b>——否则等于把答案写进题面，任何系统都能"答对"；</li>
 *   <li>期望值取注入原文里的<b>精确子串</b>——判定仍是规则判定，仍可离线复现，
 *       压力体现在输入侧，不体现在判定侧。</li>
 * </ul>
 */
public final class PressureCases {

    private PressureCases() {
    }

    /** 一行 = 一道题。{@code noise} 是同一批注入的干扰事实 */
    private record Row(String fact, String expected, String question, List<String> noise,
                       boolean crossSession) {
    }

    public static List<MemoryCase> cases() {
        // 长会议记录：12 条事实里取一条具体结论
        List<String> meeting = List.of(
                "周会上确认了会议室改到周四下午。",
                "拾光文创的对接人会换成设计部的人。",
                "本季度的推广预算先冻结。",
                "运维那边说磁盘还有 40%。",
                "会上顺带聊了团建去哪吃饭。",
                "法务说合同要走两轮用印。",
                "灰度批次定为 8% 流量、观察 72 小时。",
                "有人提到咖啡机又坏了。",
                "长庚物流的接口联调排到下周。",
                "测试环境的访问方式这周轮换过一次。",
                "设计稿先按 1280 宽出一版。",
                "散会前说了下月体检的事。");

        // 同一主体的多条相近事实：靠"记住全部"不如靠"记对那一条"
        List<String> supplier = List.of(
                "青梧智能的报价是每人 4.8 万。",
                "青梧智能的交付周期是 9 周。",
                "青梧智能的对账口径是月结 30 天。",
                "青梧智能要求预付两成。",
                "青梧智能的技术负责人姓邵。",
                "顺便说了句今天风很大。",
                "青梧智能只提供两名驻场。");

        // 多项目并行：跨项目串扰（把 A 的结论答到 B 上就是混淆）
        List<String> projects = List.of(
                "沧屿能源的数据落地在项目目录 /data/cangyu/raw。",
                "白鹭出行的埋点方案改用服务端埋点。",
                "木铎书院的项目周期是 14 周。",
                "星澜科技的需求评审固定在每周三。",
                "拾光文创的验收人是设计部。",
                "长庚物流的对接群叫「运联-周会」。",
                "青梧智能只提供两名驻场。",
                "会上还决定了下午茶预算。");

        // 带修正链：中途改过两次，最终值只有最后一个
        List<String> amended = List.of(
                "报表初版定为每天 07:00 推送。",
                "后来改到 08:30。",
                "财务说 08:30 太赶。",
                "最后定为每天 09:15 推送，节假日不推。",
                "另外讨论了会议室预订规则。",
                "运维提了磁盘告警阈值。",
                "还顺带说了班车时间。");

        // 长尾细节：编号、单位、比例这类最容易被"抹平"的信息
        List<String> details = List.of(
                "工单编号是 LIT-2026-04-3391。",
                "补偿比例按订单金额的 12%。",
                "样本量要 1200 条以上。",
                "验收窗口是 5 个工作日。",
                "接口超时设成 3000 毫秒。",
                "讨论了一下假期调休。",
                "提到新打印机在二楼。",
                "说下周有冷空气。");

        // 混合闲聊：真实会话里有效信息被大量无信息句子包裹
        List<String> chatty = List.of(
                "今天中午吃了面。",
                "我们灰度名单先给内部员工。",
                "这个群以后只发周报。",
                "昨晚球赛你看了吗？",
                "周报模板改用 Markdown。",
                "我把桌面壁纸换了。",
                "值班表按周轮换。",
                "楼下新开了一家咖啡店。",
                "备份窗口定在周日 02:00 到 04:00。",
                "对了，你那份快递到了。");

        // 极端注入量：12 条一次性灌入后只问一条
        List<String> heavy = List.of(
                "客户编号 C-77120。", "联系人偏好邮件沟通。", "报价有效期 45 天。",
                "交付分 3 期。", "首期款按合同比例付。", "验收标准见附件二。",
                "运维窗口在周三凌晨。", "日志保留 90 天。", "备份异地存两份。",
                "演练每季度一次。", "升级需提前 72 小时通知。", "对接群限 20 人。");

        List<Row> rows = List.of(
                new Row("灰度批次定为 8% 流量、观察 72 小时。", "8% 流量",
                        "上次周会定的灰度批次，流量比例是多少？", meeting, false),
                new Row("法务说合同要走两轮用印。", "两轮用印",
                        "合同在用印环节有什么要求？", meeting, false),
                new Row("长庚物流的接口联调排到下周。", "接口联调",
                        "长庚物流那边的技术对接安排到什么时候了？", meeting, true),
                new Row("设计稿先按 1280 宽出一版。", "1280",
                        "设计稿这版按多宽的画布出？", meeting, false),

                new Row("青梧智能的对账口径是月结 30 天。", "月结 30 天",
                        "青梧智能在财务上是怎么结算的？", supplier, false),
                new Row("青梧智能要求预付两成。", "预付两成",
                        "青梧智能在付款上提了什么条件？", supplier, false),
                new Row("青梧智能的技术负责人姓邵。", "邵",
                        "青梧智能那边带技术的人姓什么？", supplier, true),
                new Row("青梧智能的交付周期是 9 周。", "9 周",
                        "青梧智能承诺多久交付？", supplier, false),
                new Row("青梧智能只提供两名驻场。", "两名驻场",
                        "青梧智能派多少人到现场？", supplier, false),

                new Row("沧屿能源的数据落地在项目目录 /data/cangyu/raw。", "/data/cangyu/raw",
                        "沧屿能源的数据要放到哪个目录？", projects, false),
                new Row("白鹭出行的埋点方案改用服务端埋点。", "服务端埋点",
                        "白鹭出行那边的埋点用什么方案？", projects, false),
                new Row("木铎书院的项目周期是 14 周。", "14 周",
                        "木铎书院这个项目要做多久？", projects, true),
                new Row("星澜科技的需求评审固定在每周三。", "每周三",
                        "星澜科技的需求评审定在每周哪天？", projects, false),
                new Row("长庚物流的对接群叫「运联-周会」。", "运联-周会",
                        "长庚物流那边用哪个群对接？", projects, false),

                new Row("最后定为每天 09:15 推送，节假日不推。", "09:15",
                        "报表改到最后，每天几点推送？", amended, false),
                new Row("最后定为每天 09:15 推送，节假日不推。", "节假日不推",
                        "报表的推送在节假日是什么策略？", amended, true),

                new Row("工单编号是 LIT-2026-04-3391。", "LIT-2026-04-3391",
                        "这单的工单编号是多少？", details, false),
                new Row("补偿比例按订单金额的 12%。", "12%",
                        "补偿是按订单金额的百分之几？", details, false),
                new Row("样本量要 1200 条以上。", "1200",
                        "这次要采多少条样本才够？", details, true),
                new Row("接口超时设成 3000 毫秒。", "3000 毫秒",
                        "接口超时阈值设成多少？", details, false),
                new Row("验收窗口是 5 个工作日。", "5 个工作日",
                        "验收给几天时间？", details, false),

                new Row("我们灰度名单先给内部员工。", "内部员工",
                        "第一批灰度放给谁？", chatty, false),
                new Row("周报模板改用 Markdown。", "Markdown",
                        "周报模板换成什么格式了？", chatty, false),
                new Row("备份窗口定在周日 02:00 到 04:00。", "02:00 到 04:00",
                        "备份安排在周日哪段时间？", chatty, true),
                new Row("值班表按周轮换。", "按周轮换",
                        "值班是怎么轮换的？", chatty, false),

                new Row("报价有效期 45 天。", "45 天",
                        "这份报价能保留多久？", heavy, false),
                new Row("日志保留 90 天。", "90 天",
                        "日志要存多长时间？", heavy, false),
                new Row("交付分 3 期。", "3 期",
                        "交付分成几期？", heavy, true),
                new Row("升级需提前 72 小时通知。", "72 小时",
                        "升级前要提前多久通知？", heavy, false),
                new Row("对接群限 20 人。", "20 人",
                        "对接群的人数上限是多少？", heavy, false));

        List<MemoryCase> out = new ArrayList<>();
        int id = 1;
        for (Row row : rows) {
            List<String> seeds = new ArrayList<>();
            seeds.add(row.fact());
            seeds.addAll(row.noise());
            String caseId = "p" + id++;
            if (row.crossSession()) {
                out.add(MemoryCase.expectCrossSession(caseId, Dimension.RETENTION, seeds,
                        row.question(), row.expected()));
            } else {
                out.add(MemoryCase.expect(caseId, Dimension.RETENTION, seeds,
                        row.question(), row.expected()));
            }
        }
        return List.copyOf(out);
    }
}
