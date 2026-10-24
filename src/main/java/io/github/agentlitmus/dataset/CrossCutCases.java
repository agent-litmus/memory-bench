package io.github.agentlitmus.dataset;

import io.github.agentlitmus.core.MemoryCase;

import java.util.ArrayList;
import java.util.List;

/**
 * 交叉档用例：把两个维度<b>压在同一道题里</b>，测单独测不出来的失效。
 * <p>
 * 单维度题集有盲区：动态更新测「新值覆盖旧值」、边界识别测「不该留的没留」、相近区分测
 * 「两个相似对象别答串」，各自都能过；但真实故障常出在交叉处——
 * 更新之后又把<b>作废的旧值</b>翻出来（更新 × 边界），或者对象认对了、
 * 执行时却把<b>另一个对象的参数</b>拿过来（区分 × 复用）。
 * 这类失效需要两个能力同时参与才暴露，因此单独设一档。
 */
public final class CrossCutCases {

    private CrossCutCases() {
    }

    private enum Kind { UPDATE_BOUNDARY, DISCRIM_REUSE }

    /**
     * @param a 第一条注入（UPDATE_BOUNDARY 时是<b>将被作废</b>的旧值）
     * @param b 第二条注入（UPDATE_BOUNDARY 时是更新后的新值；DISCRIM_REUSE 时是另一个对象的事实）
     * @param expected   回答里必须出现的内容
     * @param unexpected 回答里不得出现的内容（旧值 / 另一个对象的值）
     */
    private record Row(Kind kind, String a, String b, String question,
                       String expected, String unexpected, boolean crossSession) {
    }

    public static List<MemoryCase> cases() {
        List<Row> rows = List.of(
                // 更新 × 边界：旧口令、旧号码、旧联系人属于换掉之后必须彻底消失的内容
                new Row(Kind.UPDATE_BOUNDARY, "跳板机口令是 Gate#2025-old", "跳板机口令换成 Gate#2026-new 了",
                        "现在跳板机的口令是什么？", "Gate#2026-new", "Gate#2025-old", false),
                new Row(Kind.UPDATE_BOUNDARY, "我的临时手机号是 13900001111", "临时手机号改成 13900002222 了",
                        "现在联系我用哪个临时手机号？", "13900002222", "13900001111", true),
                new Row(Kind.UPDATE_BOUNDARY, "试用授权是 TRIAL-AAA-1001", "试用授权改成 TRIAL-BBB-2002 了",
                        "现在的试用授权码是哪个？", "TRIAL-BBB-2002", "TRIAL-AAA-1001", false),
                new Row(Kind.UPDATE_BOUNDARY, "上一版对接人邮箱是 wf@example-mail.cn",
                        "对接人换成沈某，邮箱 sx@example-mail.cn",
                        "现在的对接人邮箱是多少？", "sx@example-mail.cn", "wf@example-mail.cn", false),
                new Row(Kind.UPDATE_BOUNDARY, "旧的会议桥接入号是 880-1122", "会议桥接入号换成 660-3344",
                        "现在开会用哪个接入号？", "660-3344", "880-1122", true),

                // 区分 × 复用：认对了对象，执行时也不能拿另一个对象的参数。
                // 写法约束：干扰项必须与提问<b>没有任何二字重合</b>（见 FileLongTermMemory#terms），
                // 否则参考智能体会把两条一起召回，这道题测的就不再是被测对象而是题目本身。
                new Row(Kind.DISCRIM_REUSE, "沧屿能源要求日志留存 90 天", "白鹭出行的监控录像只保 30 天",
                        "沧屿能源的日志留存期是多久？", "90 天", "30 天", false),
                new Row(Kind.DISCRIM_REUSE, "青梧智能安排两名同事驻场", "长庚物流派驻四位工程师",
                        "青梧智能那边安排几个人驻场？", "两名", "四位", false),
                new Row(Kind.DISCRIM_REUSE, "星澜科技的需求评审固定在每周三", "拾光文创的选题会放在每周五",
                        "星澜科技的需求评审定在星期几？", "每周三", "每周五", true),
                new Row(Kind.DISCRIM_REUSE, "木铎书院的合同周期为 14 周", "长庚物流的交付跨度 20 周",
                        "木铎书院那份合同写的周期是多少？", "14 周", "20 周", false),
                new Row(Kind.DISCRIM_REUSE, "甲组的备份安排在周日凌晨两点", "乙组归档改到周六凌晨两点",
                        "甲组的备份安排在什么时候？", "周日凌晨两点", "周六凌晨两点", false),
                new Row(Kind.DISCRIM_REUSE, "长庚物流的结算方式是月结 30 天", "青梧智能按双周对账",
                        "长庚物流用什么方式结算货款？", "月结 30 天", "双周对账", false),
                new Row(Kind.DISCRIM_REUSE, "沧屿能源的数据目录用 /data/cangyu/raw",
                        "白鹭出行的素材归档在 /data/bailu/raw",
                        "沧屿能源把数据落到哪个目录？", "/data/cangyu/raw", "/data/bailu/raw", false),
                new Row(Kind.DISCRIM_REUSE, "拾光文创的验收部门是设计部", "星澜科技的签核由法务部负责",
                        "拾光文创的验收走哪个部门？", "设计部", "法务部", true),
                new Row(Kind.DISCRIM_REUSE, "A 这次的放量比例是 8%", "B 项目的推送节奏是每周 20 条",
                        "A 这边这次放量到多少？", "8%", "20 条", false),
                new Row(Kind.DISCRIM_REUSE, "青梧智能本次报价是 5.2 万", "长庚物流的折扣给到 4.8 万",
                        "青梧智能这次的报价是多少？", "5.2 万", "4.8 万", false));

        List<MemoryCase> out = new ArrayList<>();
        int id = 1;
        for (Row row : rows) {
            String caseId = "x" + id++;
            if (row.kind() == Kind.UPDATE_BOUNDARY) {
                out.add(row.crossSession()
                        ? MemoryCase.expectUpdateCrossSession(caseId, row.a(), row.b(),
                        row.question(), row.expected(), row.unexpected())
                        : MemoryCase.expectUpdate(caseId, row.a(), row.b(),
                        row.question(), row.expected(), row.unexpected()));
            } else {
                out.add(MemoryCase.expectDiscriminate(caseId, row.a(), row.b(),
                        row.question(), row.expected(), row.unexpected()));
            }
        }
        return List.copyOf(out);
    }
}
