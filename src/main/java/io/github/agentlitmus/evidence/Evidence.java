package io.github.agentlitmus.evidence;

import io.github.agentlitmus.util.Digests;

import java.util.Map;

/**
 * 评测证据。
 * <p>
 * 命题要求「将对话、记忆记录、行动轨迹、文件或其他运行产物统一组织为证据」。
 * 这里把它们收敛成同一种结构，评测时无论来源，都能按同一份证据流检查与回放。
 * <p>
 * 每条证据自带 {@code seq} 与 {@code sha256}：一行证据能不能被信任，取决于它有没有被改过、
 * 有没有缺行。序号断裂说明这份流被裁剪过，哈希不匹配说明内容被改过——
 * 两者都能被 {@link EvidenceIntegrity} 离线查出来，不需要重跑评测。
 * <b>这不是签名</b>：能改文件的人也能重算哈希，它挡的是「悄悄改一行」和「传输损坏」，
 * 不是恶意伪造；整份产物的对账由 {@code manifest.json} 负责。
 *
 * @param type      证据类型：{@link #DIALOGUE} / {@link #MEMORY} / {@link #ACTION} / {@link #ARTIFACT}
 * @param sessionId 所属会话
 * @param timestamp 产生时间（毫秒）
 * @param content   证据内容
 * @param metadata  附加信息（如工具名、记忆 ID、产物类型）
 * @param seq       在本份证据流里的序号（从 1 开始连续）；由收集器盖章
 * @param sha256    {@code content} 的 sha256；由收集器盖章
 */
public record Evidence(String type,
                       String sessionId,
                       long timestamp,
                       String content,
                       Map<String, String> metadata,
                       long seq,
                       String sha256) {

    public static final String DIALOGUE = "dialogue";
    public static final String MEMORY = "memory";
    public static final String ACTION = "action";
    public static final String ARTIFACT = "artifact";

    public Evidence {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 对话角色：注入的事实（用户先告诉智能体） */
    public static final String ROLE_SEED = "seed";
    /** 对话角色：更新事实 */
    public static final String ROLE_UPDATE = "update";
    /** 对话角色：评测方的提问 */
    public static final String ROLE_QUESTION = "question";
    /** 对话角色：被测对象的回答——<b>离线重评只认这一种</b> */
    public static final String ROLE_ANSWER = "answer";
    /** 对话角色：评测框架自己写的说明（会话重置、能力门控等），不是任何一方的发言 */
    public static final String ROLE_NOTE = "note";

    /** 产物种类：本条用例的裁决记录 */
    public static final String KIND_JUDGMENT = "judgment";
    /** 裁决产物 metadata 里的三态字段名（离线重评/跨运行比较按此读回，不解析人读文本） */
    public static final String META_VERDICT = "verdict";

    /**
     * 一条对话证据。
     * <p>
     * 角色必须显式标注：一个会话里提问和回答都是 {@code dialogue} 类型，
     * 只靠先后顺序推断"哪条是回答"是脆的——离线重评（{@code litmus regrade}）要在没有代码上下文的情况下
     * 把回答捞出来重判，顺序推断一旦错位，重评就会拿提问去判分，而且不会报错，只会给出假数字。
     */
    public static Evidence dialogue(String sessionId, String content, String role) {
        return new Evidence(DIALOGUE, sessionId, System.currentTimeMillis(), content,
                role == null ? Map.of() : Map.of("role", role), 0, null);
    }

    public static Evidence memory(String sessionId, String content, String entryId) {
        return new Evidence(MEMORY, sessionId, System.currentTimeMillis(), content,
                entryId == null ? Map.of() : Map.of("entryId", entryId), 0, null);
    }

    public static Evidence action(String sessionId, String content, String tool) {
        return new Evidence(ACTION, sessionId, System.currentTimeMillis(), content,
                tool == null ? Map.of() : Map.of("tool", tool), 0, null);
    }

    public static Evidence artifact(String sessionId, String content, String kind) {
        return new Evidence(ARTIFACT, sessionId, System.currentTimeMillis(), content,
                kind == null ? Map.of() : Map.of("kind", kind), 0, null);
    }

    /**
     * 裁决产物：把三态裁决单独写进 metadata，而不是只留在 {@code content} 的人读文本里。
     * <p>
     * 离线重评与跨运行比较都要从证据流里读回「当时判成了什么」。若只能靠正则在
     * {@code Judgment.toString()} 里捞 {@code verdict=PASS}，那么给 Judgment 加一个字段就可能
     * 悄悄改变读回结果——这类故障不会报错，只会让门禁基于错误的基线放行或拦截。
     * 结构化字段是协议的一部分，文本只是给人看的。
     */
    public static Evidence judgment(String sessionId, String content, String verdict) {
        return new Evidence(ARTIFACT, sessionId, System.currentTimeMillis(), content,
                verdict == null ? Map.of("kind", KIND_JUDGMENT)
                        : Map.of("kind", KIND_JUDGMENT, META_VERDICT, verdict), 0, null);
    }

    /** 盖章：写入序号与内容哈希。只由 {@link EvidenceCollector} 调用，保证序号连续且不重复 */
    public Evidence sealed(long sequence) {
        return new Evidence(type, sessionId, timestamp, content, metadata, sequence,
                Digests.sha256Hex(content == null ? "" : content));
    }
}
