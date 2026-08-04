package byog.Common;

/**
 * 敌人视野模式。DIRECTIONAL 是正式默认；OMNIDIRECTIONAL 保留原全向菱形，
 * 用于回归、固定场景对比和调试。模式随世界存档保存。
 */
public enum VisionMode {
    DIRECTIONAL,
    OMNIDIRECTIONAL
}
