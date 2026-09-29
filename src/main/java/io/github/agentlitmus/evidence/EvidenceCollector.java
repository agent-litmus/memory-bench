package io.github.agentlitmus.evidence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 证据收集器：把评测过程中产生的对话、记忆、行动轨迹、产物统一收集，并可落盘为证据文件。
 * <p>
 * 落盘格式与长期记忆一致（JSON Lines），好处是：一行一条、人可读，
 * 在 openKylin 上跑完后可以直接把该文件作为「评测过程留证」提交检查。
 */
public class EvidenceCollector {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Evidence> buffer = new ArrayList<>();

    public synchronized void collect(Evidence evidence) {
        if (evidence != null) {
            buffer.add(evidence);
        }
    }

    public synchronized List<Evidence> all() {
        return List.copyOf(buffer);
    }

    public synchronized List<Evidence> bySession(String sessionId) {
        return buffer.stream()
                .filter(e -> e.sessionId() != null && e.sessionId().equals(sessionId))
                .toList();
    }

    public synchronized List<Evidence> byType(String type) {
        return buffer.stream()
                .filter(e -> e.type() != null && e.type().equals(type))
                .toList();
    }

    public synchronized int size() {
        return buffer.size();
    }

    public synchronized void clear() {
        buffer.clear();
    }

    /** 导出为 JSON Lines 文件，返回文件路径 */
    public synchronized Path exportTo(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            List<String> lines = new ArrayList<>(buffer.size());
            for (Evidence evidence : buffer) {
                lines.add(mapper.writeValueAsString(evidence));
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("导出证据失败: " + file, e);
        }
    }

    /** 从证据文件读回，便于复现与复盘 */
    public static List<Evidence> loadFrom(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            List<Evidence> evidences = new ArrayList<>();
            for (String line : (Iterable<String>) lines.filter(l -> !l.isBlank())::iterator) {
                try {
                    evidences.add(mapper.readValue(line, Evidence.class));
                } catch (IOException ignored) {
                    // 单行损坏跳过，不因一条坏数据丢掉整份证据
                }
            }
            return evidences;
        } catch (IOException e) {
            return List.of();
        }
    }
}
