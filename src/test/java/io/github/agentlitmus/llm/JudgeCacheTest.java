package io.github.agentlitmus.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评委缓存测试。
 * <p>
 * 缓存的全部价值在于"重评结果不变"，所以这里盯的不是命中率，而是<b>键的敏感度</b>：
 * 判定口径、评委指纹、判词、回答——任何一个变了都必须重新询问模型。
 * 少算一项就会串味：换了评委模型却命中旧裁决，报告上的数字看着可复现，其实是上一把尺子量的。
 */
class JudgeCacheTest {

    @Test
    void keyChangesWithEveryInputThatCanChangeTheVerdict() {
        String base = JudgeCache.key("2026.10-c", "qwen-plus@abc", "判词", "回答");
        assertNotEquals(base, JudgeCache.key("2026.10-b", "qwen-plus@abc", "判词", "回答"),
                "换了判定口径必须重新询问");
        assertNotEquals(base, JudgeCache.key("2026.10-c", "gpt-4o@abc", "判词", "回答"),
                "换了评委必须重新询问");
        assertNotEquals(base, JudgeCache.key("2026.10-c", "qwen-plus@abc", "判词v2", "回答"),
                "换了判词必须重新询问");
        assertNotEquals(base, JudgeCache.key("2026.10-c", "qwen-plus@abc", "判词", "回答 "),
                "回答差一个空格也是不同的证据，不能复用裁决");
        assertEquals(base, JudgeCache.key("2026.10-c", "qwen-plus@abc", "判词", "回答"),
                "四项全同必须命中");
    }

    @Test
    void nullsAreToleratedButStillDistinguishable() {
        assertNotEquals(JudgeCache.key(null, null, null, null), JudgeCache.key("", null, null, "x"));
        assertNotNull(JudgeCache.key(null, null, null, null));
    }

    @Test
    void roundTripPreservesVerdictsAndCountsHits(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("judge-cache.json");
        JudgeCache first = JudgeCache.open(file);
        assertNull(first.get("k1"), "空缓存不该命中");
        first.put("k1", "PASS 因为答对了", "规则理由", "qwen-plus@abc");
        first.put("k2", "UNKNOWN 没有作答", "规则理由", "qwen-plus@abc");
        first.save();
        assertTrue(Files.exists(file));

        JudgeCache second = JudgeCache.open(file);
        assertEquals(2, second.size());
        JudgeCache.Entry hit = second.get("k1");
        assertNotNull(hit);
        assertEquals("PASS 因为答对了", hit.verdict());
        assertEquals("qwen-plus@abc", hit.judgeId());
        assertEquals(1, second.hits());
        assertEquals(0, second.misses(), "命中就不该再记一次询问");
        assertNull(second.get("没有这条"));
        assertEquals(1, second.misses());
        assertTrue(second.statsLine().contains("命中 1"));
    }

    @Test
    void corruptCacheIsIgnoredInsteadOfBreakingTheRun(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("judge-cache.json");
        Files.writeString(file, "{ 这不是 JSON", StandardCharsets.UTF_8);
        JudgeCache cache = JudgeCache.open(file);
        assertEquals(0, cache.size(), "读不出来就当空缓存，最多多问一次模型，不该让评测跑不下去");
        cache.put("k", "PASS", "", "judge");
        cache.save();
        assertEquals(1, JudgeCache.open(file).size());
    }

    @Test
    void cacheFromAnotherFormatVersionIsDiscarded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("judge-cache.json");
        Files.writeString(file, "{\"formatVersion\":999,\"entries\":{\"k\":{\"verdict\":\"PASS\"}}}",
                StandardCharsets.UTF_8);
        assertEquals(0, JudgeCache.open(file).size());
    }

    @Test
    void missingFileStartsEmptyAndSaveCreatesParents(@TempDir Path dir) {
        Path nested = dir.resolve("a/b/judge-cache.json");
        JudgeCache cache = JudgeCache.open(nested);
        assertEquals(0, cache.size());
        cache.put("k", "FAIL", "", "judge");
        cache.save();
        assertTrue(Files.exists(nested));
    }

    @Test
    void inMemoryCacheNeverTouchesDisk() {
        JudgeCache cache = JudgeCache.inMemory();
        cache.put("k", "PASS", "", "judge");
        cache.save();
        assertNull(cache.file());
        assertEquals(1, cache.size());
        assertTrue(cache.statsLine().contains("仅内存"));
    }

    @Test
    void parallelRunsDoNotLoseEntries() throws Exception {
        JudgeCache cache = JudgeCache.inMemory();
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        cache.put(tid + "-" + i, "PASS", "", "judge");
                        done.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(threads * perThread, done.get());
        assertEquals(threads * perThread, cache.size(), "并行评测共享缓存，写入不能互相覆盖或把 Map 写坏");
    }
}
