package io.github.zaojiaoci.agentlitmus.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆存储的行为测试。
 * <p>
 * 全部使用临时目录 + 文件存储，不发起任何网络请求、不需要 API Key，
 * 符合「单测全离线、可直接进 CI」的约定——在 openKylin 上也能直接跑。
 */
class FileLongTermMemoryTest {

    @TempDir
    Path tempDir;

    private FileLongTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new FileLongTermMemory(tempDir);
    }

    @Test
    void writesAndRecallsByKeyword() {
        memory.write("s1", MemoryEntry.fact("s1", "用户在某公司负责订单录入"));

        List<MemoryEntry> recalled = memory.recall("s1", "订单录入", 5);

        assertEquals(1, recalled.size());
        assertTrue(recalled.get(0).content().contains("订单录入"));
        assertNotNull(recalled.get(0).id(), "写入后应分配 ID");
    }

    @Test
    void recallIgnoresUnrelatedQuery() {
        memory.write("s1", MemoryEntry.fact("s1", "用户负责订单录入"));

        assertTrue(memory.recall("s1", "航天发动机", 5).isEmpty());
    }

    @Test
    void updateSupersedesOldEntryAndRecallReturnsOnlyNewOne() {
        String oldId = memory.write("s1", MemoryEntry.fact("s1", "用户当前岗位是订单录入员"));
        String newId = memory.update("s1", oldId, MemoryEntry.fact("s1", "用户已转岗为客户关系管理专员"));

        List<MemoryEntry> recalled = memory.recall("s1", "用户", 5);
        assertEquals(1, recalled.size(), "被取代的旧条目不应出现在召回结果中");
        assertEquals(newId, recalled.get(0).id());

        // 旧条目保留内容但标记过时，供「动态更新」维度检查模型是否还在误用旧信息
        MemoryEntry old = memory.all("s1").stream()
                .filter(e -> oldId.equals(e.id()))
                .findFirst()
                .orElseThrow();
        assertFalse(old.isActive(), "旧条目应被标记为已过时");
        assertEquals(newId, old.supersededBy());
        assertTrue(old.content().contains("订单录入员"), "旧内容应保留用于审计");
    }

    @Test
    void nonRetainableEntryIsExcludedFromRecall() {
        // 不应长期保留的信息（如临时验证码），对应「边界识别」维度
        memory.write("s1", MemoryEntry.temporary("s1", "用户的临时验证码是 1234"));

        assertTrue(memory.recall("s1", "验证码", 5).isEmpty(),
                "不应保留的信息不应被召回");
        // 但它仍能在 active() 中看到，以便评测检查系统是否错误地留下了它
        assertEquals(1, memory.active("s1").size());
        assertFalse(memory.active("s1").get(0).retainable());
    }

    @Test
    void forgetRemovesEntry() {
        String id = memory.write("s1", MemoryEntry.fact("s1", "用户偏好开源工具"));

        assertTrue(memory.forget("s1", id));
        assertTrue(memory.recall("s1", "开源工具", 5).isEmpty());
        assertFalse(memory.forget("s1", "not-exist"), "不存在的 ID 应返回 false");
    }

    @Test
    void persistsAcrossInstances() {
        memory.write("s1", MemoryEntry.fact("s1", "用户在制造业从事质检工作"));

        // 换一个实例指向同一目录，模拟进程重启后长期记忆仍在
        FileLongTermMemory reopened = new FileLongTermMemory(tempDir);
        List<MemoryEntry> recalled = reopened.recall("s1", "质检", 5);

        assertEquals(1, recalled.size(), "长期记忆应跨实例持久化");
        assertTrue(recalled.get(0).content().contains("质检"));
    }

    @Test
    void sessionsAreIsolated() {
        memory.write("s1", MemoryEntry.fact("s1", "会话一的事实"));
        memory.write("s2", MemoryEntry.fact("s2", "会话二的事实"));

        assertEquals(1, memory.recall("s1", "事实", 5).size());
        assertTrue(memory.recall("s1", "事实", 5).get(0).content().contains("会话一"));
        assertTrue(memory.recall("s2", "事实", 5).get(0).content().contains("会话二"));
    }

    @Test
    void clearRemovesSessionMemory() {
        memory.write("s1", MemoryEntry.fact("s1", "临时会话数据"));
        memory.clear("s1");

        assertTrue(memory.all("s1").isEmpty());
    }

    @Test
    void unlabeledCredibilityFallsBackToLowestLevel() {
        String id = memory.write("s1", MemoryEntry.of("s1", "未标注可信度的信息", "fact", null, true));

        MemoryEntry stored = memory.all("s1").stream()
                .filter(e -> id.equals(e.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(MemoryEntry.DEFAULT_CREDIBILITY, stored.credibility(),
                "未标注可信度应按最低等级处理，宁可低估");
    }

    @Test
    void keywordsSplitHandlesChinesePhrases() {
        List<String> terms = FileLongTermMemory.terms("我上周去了杭州");
        assertFalse(terms.isEmpty());
        assertTrue(terms.stream().anyMatch("杭州"::equals), "中文短语应作为整体词保留");

        assertEquals(1, FileLongTermMemory.score("我上周去了杭州", List.of("杭州")));
        assertEquals(0, FileLongTermMemory.score("我上周去了苏州", List.of("杭州")));
    }
}
