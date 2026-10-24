package io.github.agentlitmus.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆状态快照测试：证据必须能区分「没写进去」和「写进去没答出来」，
 * 否则 T2 只是报告里的一个装饰词。
 */
class MemorySnapshotterTest {

    @TempDir
    Path tempDir;

    @Test
    void missingDirectoryIsRecordedAsAbsentNotEmpty() {
        MemorySnapshotter snapshotter = new MemorySnapshotter(tempDir.resolve("nope"));

        MemorySnapshotter.Snapshot snapshot = snapshotter.capture();

        assertFalse(snapshot.rootExisted(), "「没有目录」和「目录是空的」是两件事，必须分开记");
        assertTrue(snapshot.files().isEmpty());
    }

    @Test
    void diffReportsAddedChangedAndRemovedFiles() throws IOException {
        Path state = tempDir.resolve("state");
        Files.createDirectories(state);
        Files.writeString(state.resolve("stable.jsonl"), "一行不变的内容", StandardCharsets.UTF_8);
        Files.writeString(state.resolve("old.txt"), "要被删掉", StandardCharsets.UTF_8);
        MemorySnapshotter snapshotter = new MemorySnapshotter(state);

        MemorySnapshotter.Snapshot before = snapshotter.capture();
        Files.writeString(state.resolve("mem.jsonl"), "新增的一条记忆：项目代号 沧海-17",
                StandardCharsets.UTF_8);
        Files.writeString(state.resolve("stable.jsonl"), "内容改了一点点", StandardCharsets.UTF_8);
        Files.delete(state.resolve("old.txt"));
        MemorySnapshotter.Snapshot after = snapshotter.capture();

        String diff = MemorySnapshotter.describeDiff(before, after);

        assertTrue(diff.contains("新增 1"), diff);
        assertTrue(diff.contains("变更 1"), diff);
        assertTrue(diff.contains("删除 1"), diff);
        assertTrue(diff.contains("+ mem.jsonl"), diff);
        assertTrue(diff.contains("字节级"), "必须如实声明这只是字节级观测，不是行级语义: " + diff);
    }

    @Test
    void identicalSnapshotsShowNoChange() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "同样内容", StandardCharsets.UTF_8);
        MemorySnapshotter snapshotter = new MemorySnapshotter(tempDir);

        String diff = MemorySnapshotter.describeDiff(snapshotter.capture(), snapshotter.capture());

        assertTrue(diff.contains("新增 0"), diff);
        assertTrue(diff.contains("变更 0"), diff);
        assertFalse(diff.contains("+ a.txt"), "未变化的文件不该出现在明细里: " + diff);
    }

    @Test
    void findTextSeesMarkerInsideBinaryLookingFiles() throws IOException {
        Path nested = tempDir.resolve("sub/deeper");
        Files.createDirectories(nested);
        Files.write(nested.resolve("store.db"), new byte[]{0, 1, 2, 3});
        Files.writeString(nested.resolve("store.db-wal"), "头部噪声 紫鸢-A3F1 尾部噪声",
                StandardCharsets.UTF_8);

        var hits = new MemorySnapshotter(tempDir).findText("紫鸢-A3F1", 5);

        assertEquals(1, hits.size(), "应只命中真正含标记词的文件: " + hits);
        assertEquals("sub/deeper/store.db-wal", hits.get(0).path().replace('\\', '/'));
        assertNotNull(hits.get(0).excerpt());
        assertTrue(hits.get(0).excerpt().contains("紫鸢-A3F1"));
    }

    @Test
    void oversizedFilesAreCountedNotHashed() throws IOException {
        Path big = tempDir.resolve("big.bin");
        Files.write(big, new byte[4096]);
        MemorySnapshotter snapshotter = new MemorySnapshotter(tempDir, 1024);

        MemorySnapshotter.Snapshot snapshot = snapshotter.capture();

        assertEquals(1, snapshot.skipped(), "超限文件要如实计入 skipped，不能当作「没有变化」");
        assertNull(snapshot.files().get("big.bin").sha256(), "超限文件只记大小，不读内容");
        assertEquals(4096, snapshot.files().get("big.bin").size());
    }

    @Test
    void writableConfirmsPermissionNotCleanBaseline() throws IOException {
        Path state = tempDir.resolve("writable");

        assertTrue(new MemorySnapshotter(state).writable(), "不存在时应能创建后再验证");
        assertTrue(Files.exists(state), "验证过程要自行建目录，否则无法区分「没目录」和「不可写」");
        assertFalse(Files.exists(state.resolve(".litmus-probe.tmp")), "探针临时文件必须清掉");
    }
}
