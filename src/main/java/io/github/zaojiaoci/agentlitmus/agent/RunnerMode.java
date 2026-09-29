package io.github.zaojiaoci.agentlitmus.agent;

/**
 * 被测智能体的评测模式——区分「记忆由谁建立」。
 * <p>
 * 这是适配不同智能体的关键抽象：
 * <ul>
 *   <li>{@link #MEMORY}：记忆由评测框架直接写入被测对象背后的存储，适合评测记忆系统本身
 *       （内置 reference / degraded 属于此类）；</li>
 *   <li>{@link #DIALOGUE}：记忆由<b>对话</b>建立——真实智能体的记忆是在交互中形成的，
 *       外部无法（也不应）代劳，只能通过把事实"说给它听"来注入（HTTP / 命令行智能体属于此类）。</li>
 * </ul>
 * 有了这个区分，同一套用例既能测自建记忆系统，也能测真实智能体。
 */
public enum RunnerMode {

    /** 记忆由评测框架注入（本地记忆实现） */
    MEMORY("记忆注入"),

    /** 记忆由对话建立（真实智能体） */
    DIALOGUE("对话注入");

    private final String label;

    RunnerMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
