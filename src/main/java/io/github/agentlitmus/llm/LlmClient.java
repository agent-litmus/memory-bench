package io.github.agentlitmus.llm;

/**
 * 大模型调用的最小抽象。
 * <p>
 * 刻意只保留一个方法：给 prompt，返回文本。这样做的好处是：
 * <ul>
 *   <li><b>核心库零框架依赖</b>——不绑定 Spring AI / LangChain / 任何厂商 SDK；</li>
 *   <li>接入任何模型都只需一行 lambda，例如
 *       {@code prompt -> chatClient.prompt().user(prompt).call().content()}；</li>
 *   <li>不配置即可运行：评测默认走规则判定，{@code null} 表示无可用模型。</li>
 * </ul>
 */
@FunctionalInterface
public interface LlmClient {

    /**
     * 提交 prompt 并返回模型输出。
     *
     * @return 模型文本；实现应尽量避免抛异常，出错时返回 null 由调用方降级处理
     */
    String complete(String prompt);
}
