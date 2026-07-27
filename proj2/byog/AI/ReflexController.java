package byog.AI;

import byog.Helper.Logger;
import byog.lab5.Position;

/**
 * 快脑反射控制器。只读取 ReflexObservation，处理 P1（相邻攻击）和 P2（可见追击）。
 *
 * <p>禁止读取 Player 引用、完整 world、EntityManager 或其他 Enemy 的
 * 隐藏 observation。它只产生高层 intent，动作合法性和寻路由 Enemy
 * 交给 Java 权威 Planner。</p>
 */
public final class ReflexController {

    /** P1 触发原因 */
    public static final String P1_ADJACENT_THREAT = "P1_ADJACENT_THREAT";
    /** P2 触发原因 */
    public static final String P2_VISIBLE_PLAYER = "P2_VISIBLE_PLAYER";

    /**
     * 判断是否应触发 P1：玩家在当前私有 observation 中且曼哈顿距离为 1。
     */
    public boolean shouldTriggerP1(ReflexObservation reflex) {
        return reflex != null && reflex.isPlayerAdjacent();
    }

    /**
     * 判断是否应触发 P2：玩家可见且 policy 允许主动接战。
     * 无 lease 时使用安全默认值（允许接战）。
     */
    public boolean shouldTriggerP2(ReflexObservation reflex,
                                   InterruptPolicy policy) {
        if (reflex == null || !reflex.canSeePlayer()) {
            return false;
        }
        // P2 不重复触发 P1 已处理的相邻情况
        if (reflex.isPlayerAdjacent()) {
            return false;
        }
        if (policy == null) {
            policy = InterruptPolicy.safeDefault();
        }
        return policy.engageVisiblePlayer();
    }

    /**
     * 创建 P1 反射 intent：向相邻玩家发动攻击。
     *
     * @return ATTACK intent，或 null（条件不满足时）
     */
    public StrategicIntent createAdjacentAttackIntent(
            ReflexObservation reflex) {
        if (!shouldTriggerP1(reflex)) {
            return null;
        }
        Position player = reflex.getVisiblePlayer().getPosition();
        Logger.debug("ReflexController P1: attack visible adjacent player at %s",
                player);
        return new StrategicIntent(
                StrategicIntent.Goal.ATTACK_PLAYER,
                StrategicIntent.Strategy.ATTACK,
                player);
    }

    /**
     * 创建 P2 反射 intent：追击当前 observation 中可见的玩家。
     *
     * @return CHASE intent，或 null（玩家不可见时）
     */
    public StrategicIntent createEngageIntent(ReflexObservation reflex) {
        if (reflex == null || !reflex.canSeePlayer()) {
            return null;
        }
        Position player = reflex.getVisiblePlayer().getPosition();
        Logger.debug("ReflexController P2: engage visible player at %s",
                player);
        return new StrategicIntent(
                StrategicIntent.Goal.CHASE,
                StrategicIntent.Strategy.CHASE, player);
    }
}
