package byog.AI;

import byog.Common.Direction;
import byog.Common.Facing;
import byog.Perception.ObservationEnvelope;
import byog.Perception.VisibleEntity;
import byog.lab5.Position;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 确定性巡视决策服务。
 *
 * <p>只接收私有 Observation 与 {@link PatrolState}，不读取完整 world、
 * Player 或 EntityManager。返回下一个 action 的 primitive
 * （MOVE / WAIT / TURN），由执行层翻译为具体 Action。候选目标来自
 * observation 的可见可行走格，选择索引由稳定输入派生，保证同输入同结果。</p>
 */
public final class PatrolController {

    /** 目标候选的最小/最大曼哈顿距离。 */
    public static final int MIN_TARGET_DIST = 3;
    public static final int MAX_TARGET_DIST = 8;

    /** 到达后的停留次数与扫描转向次数（转向频率已按产品要求调低为 2 次）。 */
    public static final int DWELL_ACTIONS = 1;
    public static final int SCAN_TURNS = 2;

    /** 连续受阻达到该次数后放弃目标进入扫描。 */
    public static final int MAX_BLOCKED_ATTEMPTS = 2;

    private static final long PATROL_NAMESPACE = 0x9E3779B97F4A7C15L;

    /** 一次巡视决策结果：动作 primitive 与状态转换原因。 */
    public record PatrolDecision(Kind kind, Direction moveDirection,
                                 Facing turnTo, String transitionReason) {

        public enum Kind { MOVE, WAIT, TURN }

        public static PatrolDecision move(Direction direction,
                                          String reason) {
            return new PatrolDecision(Kind.MOVE, direction, null, reason);
        }

        public static PatrolDecision wait(String reason) {
            return new PatrolDecision(Kind.WAIT, null, null, reason);
        }

        public static PatrolDecision turn(Facing facing, String reason) {
            return new PatrolDecision(Kind.TURN, null, facing, reason);
        }
    }

    /**
     * 生成下一个巡视动作并推进状态机。
     *
     * @param obs      本敌人最新私有 Observation
     * @param state    可保存巡视状态（会被修改）
     * @param seedKey  世界 seed 的稳定派生（Game 提供）
     * @param floorId  当前楼层
     * @param agentId  本敌人身份
     */
    public PatrolDecision decide(ObservationEnvelope obs,
                                 PatrolState state,
                                 long seedKey,
                                 int floorId,
                                 String agentId) {
        Position self = obs.getSelfPosition();
        return switch (state.getMode()) {
            case TRAVELING -> traveling(obs, state, self, seedKey, floorId, agentId);
            case DWELLING -> dwelling(state, self);
            case SCANNING -> scanning(obs, state, self, seedKey, floorId, agentId);
        };
    }

    /** 一次成功移动清零连续受阻计数。 */
    public void onMoveSucceeded(PatrolState state) {
        state.setBlockedAttempts(0);
    }

    /** 一次受阻移动增加连续受阻计数；由执行层在 ActionOutcome 后调用。 */
    public void onMoveBlocked(PatrolState state) {
        state.setBlockedAttempts(state.getBlockedAttempts() + 1);
    }

    private PatrolDecision traveling(ObservationEnvelope obs,
                                     PatrolState state,
                                     Position self,
                                     long seedKey,
                                     int floorId,
                                     String agentId) {
        if (!state.hasTarget()) {
            return selectTarget(obs, state, self, seedKey, floorId, agentId);
        }
        Position target = state.getTarget();
        if (target.equals(self)) {
            state.setMode(PatrolState.Mode.DWELLING);
            state.setDwellActionsRemaining(DWELL_ACTIONS);
            return PatrolDecision.wait("REACHED_TARGET");
        }
        // 连续两次受阻：放弃目标进入扫描（由执行层反馈驱动）。
        if (state.getBlockedAttempts() >= MAX_BLOCKED_ATTEMPTS) {
            abandonTarget(state);
            return PatrolDecision.turn(
                    obs.getSelfFacing().clockwise(),
                    "ABANDON_TARGET_BLOCKED");
        }
        List<Position> path = bfs(obs, self, target);
        if (path.isEmpty()) {
            // BFS 失败（目标可见但绕行路径经过不可见格）：有限次后放弃，
            // 避免敌人永久等待卡死。
            state.setBlockedAttempts(state.getBlockedAttempts() + 1);
            if (state.getBlockedAttempts() >= MAX_BLOCKED_ATTEMPTS) {
                abandonTarget(state);
                return PatrolDecision.turn(
                        obs.getSelfFacing().clockwise(),
                        "ABANDON_TARGET_UNREACHABLE");
            }
            return PatrolDecision.wait("PATH_RECALCULATING");
        }
        Position next = path.get(0);
        Direction direction = Direction.fromDelta(
                next.x - self.x, next.y - self.y);
        if (direction == null) {
            return PatrolDecision.wait("PATH_STEP_INVALID");
        }
        return PatrolDecision.move(direction, "TRAVELING");
    }

    private PatrolDecision dwelling(PatrolState state, Position self) {
        if (state.getDwellActionsRemaining() > 0) {
            state.setDwellActionsRemaining(
                    state.getDwellActionsRemaining() - 1);
            return PatrolDecision.wait("DWELLING");
        }
        state.setMode(PatrolState.Mode.SCANNING);
        state.setScanTurnsRemaining(SCAN_TURNS);
        return turnDuringScan(state);
    }

    private PatrolDecision scanning(ObservationEnvelope obs,
                                    PatrolState state,
                                    Position self,
                                    long seedKey,
                                    int floorId,
                                    String agentId) {
        if (state.getScanTurnsRemaining() > 0) {
            return turnDuringScan(state);
        }
        return selectTarget(obs, state, self, seedKey, floorId, agentId);
    }

    private PatrolDecision turnDuringScan(PatrolState state) {
        state.setScanTurnsRemaining(state.getScanTurnsRemaining() - 1);
        // 朝向从状态派生；TURN primitive 的朝向由执行层读敌人当前 facing。
        return PatrolDecision.turn(null, "SCANNING");
    }

    private PatrolDecision selectTarget(ObservationEnvelope obs,
                                        PatrolState state,
                                        Position self,
                                        long seedKey,
                                        int floorId,
                                        String agentId) {
        List<Position> candidates = collectCandidates(obs, self);
        if (candidates.isEmpty()) {
            // Re-check after one turn so a wall-facing scan cannot wait forever.
            return PatrolDecision.turn(null, "NO_PATROL_CANDIDATES");
        }
        state.setMode(PatrolState.Mode.TRAVELING);
        long ordinal = state.getSelectionOrdinal();
        long hash = seedKey ^ (floorId * 31L)
                ^ (agentId == null ? 0L : agentId.hashCode())
                ^ PATROL_NAMESPACE ^ ordinal;
        int index = Math.floorMod(hash, candidates.size());
        Position target = candidates.get(index);
        state.setTarget(target);
        state.advanceSelectionOrdinal();
        state.setBlockedAttempts(0);
        List<Position> path = bfs(obs, self, target);
        if (path.isEmpty()) {
            return PatrolDecision.wait("TARGET_UNREACHABLE_NOW");
        }
        Position next = path.get(0);
        Direction direction = Direction.fromDelta(
                next.x - self.x, next.y - self.y);
        return direction == null
                ? PatrolDecision.wait("TARGET_UNREACHABLE_NOW")
                : PatrolDecision.move(direction, "TARGET_SELECTED");
    }

    /** 放弃当前目标并进入扫描。 */
    private void abandonTarget(PatrolState state) {
        state.setTarget(null);
        state.setMode(PatrolState.Mode.SCANNING);
        state.setScanTurnsRemaining(SCAN_TURNS);
    }

    /**
     * 候选：obs 可见可行走格，距离 3–8，排除 self 与可见实体占位。
     * 稳定排序：先 x、再 y。
     */
    private static List<Position> collectCandidates(
            ObservationEnvelope obs, Position self) {
        Set<Position> occupied = new HashSet<>();
        for (VisibleEntity entity : obs.getVisibleEntities()) {
            occupied.add(entity.getPosition());
        }
        List<Position> candidates = new ArrayList<>();
        for (int x = 0; x < obs.getVisibleMask().length; x++) {
            for (int y = 0; y < obs.getVisibleMask()[0].length; y++) {
                if (!obs.isWalkable(x, y)) {
                    continue;
                }
                Position p = new Position(x, y);
                if (p.equals(self) || occupied.contains(p)) {
                    continue;
                }
                int dist = Math.abs(x - self.x) + Math.abs(y - self.y);
                if (dist >= MIN_TARGET_DIST && dist <= MAX_TARGET_DIST) {
                    candidates.add(p);
                }
            }
        }
        candidates.sort(Comparator.comparingInt((Position p) -> p.x)
                .thenComparingInt(p -> p.y));
        return candidates;
    }

    /**
     * 在 observation 的可见可行走格上做 BFS（不读取隐藏地图）。
     * 返回从 self 到 target 的下一步路径（不含 self）。
     */
    private static List<Position> bfs(ObservationEnvelope obs,
                                      Position self, Position target) {
        if (self.equals(target)) {
            return List.of();
        }
        boolean[][] visited = new boolean[
                obs.getVisibleMask().length][obs.getVisibleMask()[0].length];
        Map<Position, Position> cameFrom = new HashMap<>();
        Queue<Position> queue = new ArrayDeque<>();
        visited[self.x][self.y] = true;
        queue.add(self);
        int[][] deltas = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            Position current = queue.poll();
            for (int[] delta : deltas) {
                int nx = current.x + delta[0];
                int ny = current.y + delta[1];
                if (!inBounds(obs, nx, ny) || visited[nx][ny]) {
                    continue;
                }
                if (!obs.isWalkable(nx, ny)) {
                    continue;
                }
                Position next = new Position(nx, ny);
                visited[nx][ny] = true;
                cameFrom.put(next, current);
                if (next.equals(target)) {
                    return reconstruct(cameFrom, self, target);
                }
                queue.add(next);
            }
        }
        return List.of();
    }

    private static List<Position> reconstruct(Map<Position, Position> cameFrom,
                                              Position start, Position target) {
        List<Position> path = new ArrayList<>();
        Position cursor = target;
        while (cursor != null && !cursor.equals(start)) {
            path.add(0, cursor);
            cursor = cameFrom.get(cursor);
        }
        return path;
    }

    private static boolean inBounds(ObservationEnvelope obs, int x, int y) {
        return x >= 0 && x < obs.getVisibleMask().length
                && y >= 0 && y < obs.getVisibleMask()[0].length;
    }
}
