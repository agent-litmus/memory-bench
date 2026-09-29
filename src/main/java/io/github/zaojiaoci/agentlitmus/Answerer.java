package io.github.zaojiaoci.agentlitmus;

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
}
