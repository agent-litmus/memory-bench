package io.github.agentlitmus.agent;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 被测回答器：给定会话与问题，产出回答。
 * <p>
 * 抽象出这个接口，是为了让同一套用例既能测「确定性实现」（无 API Key 也能跑），
 * 也能测「真实大模型」。评测逻辑不绑定具体被测对象——
 * 这正是「面向不同智能体进行评测」的基础：接入新智能体 = 新写一个实现。
 */
@FunctionalInterface
public interface Answerer {

    String answer(String sessionId, String question);

    /**
     * 重置该会话的<b>对话上下文</b>（不清长期记忆），用于跨会话长期保持用例。
     * <p>
     * 默认空实现：内置被测对象没有「上下文窗口」概念，本就不依赖对话历史，重置与否无差别。
     * 真实智能体（如命令行适配器）应覆写，真正清掉会话状态后再接受提问。
     */
    default void resetSession(String sessionId) {
        // 默认无需处理
    }

    /**
     * 声明本被测对象可观测到什么。
     * <p>
     * 默认<b>只声明 {@link AgentCapability#RESETS_CONTEXT}</b>：接口的 {@link #resetSession(String)}
     * 有默认空实现，也就是说"声称能重置"不等于"真能重置"，所以宁可从保守值出发，
     * 由适配器自己覆写。保守的代价只是分母变小，激进的代价是产出假缺陷。
     */
    default Set<AgentCapability> capabilities() {
        return EnumSet.of(AgentCapability.RESETS_CONTEXT);
    }

    /**
     * 取走<b>本次线程上</b>由评测方观测到的行动事件（如子进程执行），取走后清空。
     * <p>
     * 只收评测方自己看见的事实：被测对象<b>自报</b>的「我调用了某工具」不算行动证据，
     * 因为自报与事实之间没有任何可核对的环节。默认空实现——观测不到就一条都不报，
     * 报告里因此写的是「行动不可观测」，而不是编一份轨迹。
     */
    default List<String> drainObservedActions() {
        return List.of();
    }

    /** 由能力集合推出的置信层级，报告首屏要把它和被测分数放在一起 */
    default ConfidenceTier confidenceTier() {
        return ConfidenceTier.of(capabilities());
    }
}

