package byog.AI;

/**
 * AI 大脑接口。定义 AI 决策的统一入口，所有具体 AI 实现（规则 AI、LLM AI 等）都实现此接口。
 */
public interface EnemyBrain {

    /**
     * 根据当前游戏状态快照生成战略意图。
     * @param state 游戏状态快照
     * @return AI 的战略意图
     */
    StrategicIntent think(GameStateSnapshot state);
}
