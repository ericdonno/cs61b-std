package byog.AI;

import byog.lab5.Position;

/**
 * 战略意图。AI 大脑做出的高层决策，描述"要达成什么"和"用什么方式达成"。
 */
public class StrategicIntent {

    public enum Goal {
        INTERCEPT_PLAYER, GUARD, PATROL, CHASE, AMBUSH, RETREAT, ATTACK_PLAYER
    }

    public enum Strategy {
        INTERCEPT, AMBUSH, PATROL, GUARD, CHASE, ATTACK
    }

    private final Goal goal;
    private final Strategy strategy;
    private final Position targetPosition;
    private final double confidence;
    private final int targetRoom;

    public StrategicIntent(Goal goal, Strategy strategy, Position targetPosition) {
        this(goal, strategy, targetPosition, 0.5, -1);
    }

    public StrategicIntent(Goal goal, Strategy strategy, Position targetPosition,
                           double confidence, int targetRoom) {
        this.goal = goal;
        this.strategy = strategy;
        this.targetPosition = targetPosition;
        this.confidence = confidence;
        this.targetRoom = targetRoom;
    }

    public Goal getGoal() {
        return goal;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public Position getTargetPosition() {
        return targetPosition;
    }

    public double getConfidence() {
        return confidence;
    }

    public int getTargetRoom() {
        return targetRoom;
    }
}
