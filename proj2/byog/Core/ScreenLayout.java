package byog.Core;

/**
 * 屏幕布局常量与 world/screen 坐标互逆映射。
 *
 * <p>世界逻辑尺寸保持 80×30；顶部 HUD 3 格、底部上下文条 2 格，
 * 世界 tile 统一绘制在 {@code y + WORLD_Y_OFFSET}，鼠标坐标统一反向映射。</p>
 */
public final class ScreenLayout {

    public static final int WORLD_WIDTH = 80;
    public static final int WORLD_HEIGHT = 30;
    public static final int TOP_UI_HEIGHT = 3;
    public static final int BOTTOM_UI_HEIGHT = 2;
    public static final int WINDOW_HEIGHT =
            WORLD_HEIGHT + TOP_UI_HEIGHT + BOTTOM_UI_HEIGHT;
    /** 世界底边到窗口底边的偏移（底部条高度）。 */
    public static final int WORLD_Y_OFFSET = BOTTOM_UI_HEIGHT;

    private ScreenLayout() {
    }

    /** 世界逻辑 y → 屏幕 y。 */
    public static int worldToScreenY(int worldY) {
        return worldY + WORLD_Y_OFFSET;
    }

    /** 屏幕 y → 世界逻辑 y。 */
    public static int screenToWorldY(int screenY) {
        return screenY - WORLD_Y_OFFSET;
    }
}
