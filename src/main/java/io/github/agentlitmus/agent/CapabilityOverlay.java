package io.github.agentlitmus.agent;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 能力叠加层：在适配器<b>自己声明</b>的能力之上，加上配置里<b>显式声明</b>的能力。
 * <p>
 * 适配器只能声明它确定做得到的事（例如 CLI 适配器只知道「能重置会话」）；
 * 而「记忆落在哪个目录、工具调用是否可见」这类事实只有接入方知道。
 * 因此能力来自两处：代码里能自证的由适配器声明，其余由配置声明，二者取并集。
 * <p>
 * 取并集而不是覆盖：配置写错一项（多写一个 MEMORY_INSPECTABLE）不该让适配器
 * 已经确认的能力凭空消失；反过来，漏写也只是置信度降级，不会产出假缺陷。
 */
public record CapabilityOverlay(Answerer delegate, Set<AgentCapability> declared) implements Answerer {

    public CapabilityOverlay {
        if (delegate == null) {
            throw new IllegalArgumentException("能力叠加层必须包装一个回答器");
        }
        declared = declared == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(declared));
    }

    /** 没有任何声明时直接返回原对象，避免无意义的包装（包装会挡住 instanceof 探测） */
    public static Answerer of(Answerer answerer, Set<AgentCapability> declared) {
        if (answerer == null) {
            return null;
        }
        return declared == null || declared.isEmpty()
                ? answerer
                : new CapabilityOverlay(answerer, declared);
    }

    /** 剥掉叠加层，取回真实适配器——需要 {@code instanceof} 识别适配器类型时先用它 */
    public static Answerer unwrap(Answerer answerer) {
        Answerer current = answerer;
        while (current instanceof CapabilityOverlay overlay) {
            current = overlay.delegate();
        }
        return current;
    }

    @Override
    public String answer(String sessionId, String question) {
        return delegate.answer(sessionId, question);
    }

    @Override
    public void resetSession(String sessionId) {
        delegate.resetSession(sessionId);
    }

    @Override
    public java.util.List<String> drainObservedActions() {
        return delegate.drainObservedActions();
    }

    @Override
    public Set<AgentCapability> capabilities() {
        Set<AgentCapability> merged = EnumSet.noneOf(AgentCapability.class);
        merged.addAll(delegate.capabilities());
        merged.addAll(declared);
        return Set.copyOf(merged);
    }
}
