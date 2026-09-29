package io.github.agentlitmus.agent;

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
}
