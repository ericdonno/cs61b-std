package byog.AI;

import byog.Bridge.AgentProtocol;

import java.util.Objects;

/**
 * 远程 intent 的中断策略。三个受限布尔开关，控制快脑何时可以暂挂远程 lease。
 *
 * <p>注意：P0/P1 安全规则始终高于远程 policy，
 * {@code respondToAdjacentThreat=false} 不会真正关闭 P1 相邻攻击。</p>
 */
public final class InterruptPolicy {

    private final boolean engageVisiblePlayer;
    private final boolean respondToAdjacentThreat;
    private final boolean allowLocalReroute;

    public InterruptPolicy(boolean engageVisiblePlayer,
                           boolean respondToAdjacentThreat,
                           boolean allowLocalReroute) {
        this.engageVisiblePlayer = engageVisiblePlayer;
        this.respondToAdjacentThreat = respondToAdjacentThreat;
        this.allowLocalReroute = allowLocalReroute;
    }

    /** 安全默认值：三项均允许。 */
    public static InterruptPolicy safeDefault() {
        return new InterruptPolicy(true, true, true);
    }

    /** GUARD 默认值：不主动追击远处可见玩家，但仍响应相邻威胁。 */
    public static InterruptPolicy guardDefault() {
        return new InterruptPolicy(false, true, true);
    }

    /** 从协议层 InterruptPolicyData 构造；null 时返回安全默认值。 */
    public static InterruptPolicy fromData(AgentProtocol.InterruptPolicyData data) {
        return fromData(data, null);
    }

    /** 从协议层构造；缺失时按 skill 使用 Java 侧安全默认值。 */
    public static InterruptPolicy fromData(
            AgentProtocol.InterruptPolicyData data,
            AgentProtocol.Skill skill) {
        if (data == null) {
            return skill == AgentProtocol.Skill.GUARD
                    ? guardDefault() : safeDefault();
        }
        return new InterruptPolicy(
                data.engageVisiblePlayer(),
                data.respondToAdjacentThreat(),
                data.allowLocalReroute());
    }

    /** 是否允许在看到远处玩家时主动接战（P2）。 */
    public boolean engageVisiblePlayer() {
        return engageVisiblePlayer;
    }

    /** 是否允许响应相邻威胁（P1）。即使为 false，P1 仍然由 Java 强制执行。 */
    public boolean respondToAdjacentThreat() {
        return respondToAdjacentThreat;
    }

    /** 是否允许在动作受阻时局部重新规划路线。 */
    public boolean allowLocalReroute() {
        return allowLocalReroute;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InterruptPolicy other)) {
            return false;
        }
        return engageVisiblePlayer == other.engageVisiblePlayer
                && respondToAdjacentThreat == other.respondToAdjacentThreat
                && allowLocalReroute == other.allowLocalReroute;
    }

    @Override
    public int hashCode() {
        return Objects.hash(engageVisiblePlayer, respondToAdjacentThreat,
                allowLocalReroute);
    }
}
