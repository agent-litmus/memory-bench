package io.github.agentlitmus.dataset;

import io.github.agentlitmus.core.Dimension;
import io.github.agentlitmus.core.MemoryCase;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新题集档位的<b>合法性门禁</b>。
 * <p>
 * 题量一上来，靠人眼审题一定会漏。这四条是机器能查、且漏一条就说明题集有硬伤：
 * <ol>
 *   <li><b>可判定</b>：期望串必须出现在注入原文里——否则这题永远答不对，是在被测上记一笔假失败；</li>
 *   <li><b>可寻址</b>：ID 全局唯一——重复 ID 会让逐题一致率与区分度统计串题；</li>
 *   <li><b>不涉真</b>：不得出现真实厂商、真实证件样式、真实邮箱域——安全档把真凭据写进题集本身就是事故；</li>
 *   <li><b>可拼装</b>：档位组成固定——分数变化必须能区分"改了题"还是"改了能力"。</li>
 * </ol>
 */
class NewCaseSuitesTest {

    /** 真实存在的厂商/机构名：一律禁止出现在题集里 */
    private static final List<String> REAL_ENTITY_BLACKLIST = List.of(
            "阿里", "腾讯", "华为", "字节", "百度", "美团", "京东", "抖音", "微信", "支付宝",
            "OpenAI", "Google", "Microsoft", "Anthropic", "百度大脑", "中科院");

    /** 真实邮箱/域名后缀：题集只用 RFC 2606 保留域与 example-* */
    private static final List<String> REAL_DOMAIN_BLACKLIST = List.of(
            "@qq.com", "@163.com", "@gmail.com", "@outlook.com", "@company.com");

    /** 看起来像真手机号的号段（题集统一用 139/135 + 连续 0000 的虚构样式） */
    private static final Pattern REAL_LOOKING_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private static final List<Dimension> NEW_DIMENSIONS = List.of(
            Dimension.BOUNDARY, Dimension.RETENTION, Dimension.DISCRIMINATION, Dimension.UPDATE);

    @Test
    void everyExpectationIsGroundedInTheInjectedText() {
        // 同样只约束新档位，理由见 noRealEntitiesOrRealLookingContacts
        List<MemoryCase> newCases = new java.util.ArrayList<>(SecurityCases.cases());
        newCases.addAll(PressureCases.cases());
        newCases.addAll(CrossCutCases.cases());
        for (MemoryCase kase : newCases) {
            String contains = kase.expectedContains();
            if (contains != null && !contains.isBlank()) {
                assertTrue(appearsIn(contains, kase),
                        kase.id() + " 的期望串「" + contains + "」不在注入原文里，这题永远答不对");
            }
            String notContains = kase.expectedNotContains();
            if (notContains != null && !notContains.isBlank()) {
                assertTrue(appearsIn(notContains, kase),
                        kase.id() + " 的排除串「" + notContains + "」不在注入原文里，等于没在测泄漏");
            }
            assertFalse(kase.seeds().isEmpty(), kase.id() + " 没有注入内容");
            assertFalse(kase.question().isBlank(), kase.id() + " 没有提问");
        }
    }

    private static boolean appearsIn(String needle, MemoryCase kase) {
        StringBuilder haystack = new StringBuilder();
        kase.seeds().forEach(haystack::append);
        if (kase.updateTo() != null) {
            haystack.append(kase.updateTo());
        }
        return haystack.toString().contains(needle);
    }

    @Test
    void idsAreGloballyUniqueAcrossSuites() {
        Set<String> ids = new HashSet<>();
        // 新档位各自唯一；基础档与新档位的隔离由 suiteCompositionIsFixed 与 ID 前缀约定保证
        for (MemoryCase kase : CaseSuites.of(CaseSuites.FULL)) {
            assertTrue(ids.add(kase.id()), "用例 ID 重复: " + kase.id());
        }
    }

    @Test
    void noRealEntitiesOrRealLookingContacts() {
        // 只约束新档位：基础档 150 条已随真实运行证据归档（samples/*），
        // 追溯改题会让那些报告的题集哈希对不上，等于篡改已发布证据。
        List<MemoryCase> newCases = new java.util.ArrayList<>(SecurityCases.cases());
        newCases.addAll(PressureCases.cases());
        newCases.addAll(CrossCutCases.cases());
        for (MemoryCase kase : newCases) {
            String blob = kase.id() + "|" + String.join("|", kase.seeds())
                    + "|" + nullToEmpty(kase.updateTo())
                    + "|" + kase.question()
                    + "|" + nullToEmpty(kase.expectedContains())
                    + "|" + nullToEmpty(kase.expectedNotContains());
            for (String banned : REAL_ENTITY_BLACKLIST) {
                assertFalse(blob.contains(banned), kase.id() + " 出现真实厂商名「" + banned + "」");
            }
            for (String banned : REAL_DOMAIN_BLACKLIST) {
                assertFalse(blob.contains(banned), kase.id() + " 出现真实邮箱域「" + banned + "」");
            }
            var matcher = REAL_LOOKING_PHONE.matcher(blob);
            while (matcher.find()) {
                String phone = matcher.group();
                assertTrue(phone.startsWith("1390000") || phone.startsWith("1350000")
                                || phone.startsWith("1860000"),
                        kase.id() + " 的号码「" + phone + "」不像虚构号段");
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Test
    void suiteCompositionIsFixed() {
        assertEquals(SecurityCases.cases().size(),
                CaseSuites.of(CaseSuites.SECURITY).size());
        assertEquals(PressureCases.cases().size(),
                CaseSuites.of(CaseSuites.PRESSURE).size());
        assertEquals(CrossCutCases.cases().size(),
                CaseSuites.of(CaseSuites.CROSSCUT).size());

        int basic = MemoryCases.defaultCases().size();
        assertEquals(basic + SecurityCases.cases().size(),
                CaseSuites.of(CaseSuites.STANDARD).size());
        assertEquals(basic + SecurityCases.cases().size() + PressureCases.cases().size()
                        + CrossCutCases.cases().size(),
                CaseSuites.of(CaseSuites.FULL).size());

        assertTrue(SecurityCases.cases().size() >= 80, "安全档需覆盖六类红线的同/跨会话");
        assertTrue(PressureCases.cases().size() >= 30, "压力档至少 30 道长上下文题");
        assertTrue(CrossCutCases.cases().size() >= 15, "交叉档至少 15 道双维度题");
    }

    @Test
    void newSuitesPassDatasetGovernance() {
        for (String suite : List.of(CaseSuites.SECURITY, CaseSuites.PRESSURE,
                CaseSuites.CROSSCUT, CaseSuites.STANDARD, CaseSuites.FULL)) {
            CoverageReport coverage = CoverageReport.of(CaseSuites.of(suite));
            assertTrue(coverage.valid(), suite + " 未通过数据治理:\n" + coverage.toText());
        }
    }

    @Test
    void securitySuiteCoversBothSessionScopes() {
        List<MemoryCase> security = SecurityCases.cases();
        assertTrue(security.stream().anyMatch(MemoryCase::crossSession), "安全档必须有跨会话变体");
        long same = security.stream().filter(c -> !c.crossSession()).count();
        long cross = security.stream().filter(MemoryCase::crossSession).count();
        assertEquals(same, cross, "每条红线都应同时有同会话与跨会话两道题");
        assertTrue(security.stream().allMatch(c -> c.dimension() == Dimension.BOUNDARY),
                "安全档统一落在边界识别维度，避免破坏六维口径");
    }

    @Test
    void crossCutCoversBothCrossTypes() {
        List<MemoryCase> cross = CrossCutCases.cases();
        assertTrue(cross.stream().anyMatch(c -> c.dimension() == Dimension.UPDATE),
                "缺少「更新 × 边界」");
        assertTrue(cross.stream().anyMatch(c -> c.dimension() == Dimension.DISCRIMINATION),
                "缺少「区分 × 复用」");
        assertTrue(cross.stream().allMatch(c -> NEW_DIMENSIONS.contains(c.dimension())));
    }

    @Test
    void pressureCasesInjectAtLeastSixFacts() {
        for (MemoryCase kase : PressureCases.cases()) {
            assertTrue(kase.seeds().size() >= 6,
                    kase.id() + " 只注入 " + kase.seeds().size() + " 条，不构成压力档");
        }
    }

    @Test
    void questionsDoNotLeakTheExpectedAnswer() {
        // 提问里复述期望串等于把答案写进题面；新档位必须避免
        for (MemoryCase kase : CaseSuites.of(CaseSuites.PRESSURE)) {
            String contains = kase.expectedContains();
            if (contains != null && !contains.isBlank()) {
                assertFalse(kase.question().contains(contains),
                        kase.id() + " 的提问把期望串「" + contains + "」写明了");
            }
        }
    }

    @Test
    void unknownSuiteFailsWithAvailableNames() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CaseSuites.of("nonsense"));
        assertTrue(e.getMessage().contains(CaseSuites.FULL), e.getMessage());
        assertTrue(CaseSuites.names().contains(CaseSuites.STANDARD));
    }
}
