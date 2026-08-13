package byog.Test;

import byog.Common.Direction;
import byog.Core.PlayerMoveController;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PlayerInputCadenceTest {
    @Test
    public void newDirectionMovesImmediately() {
        PlayerMoveController controller = new PlayerMoveController();

        assertEquals(Direction.UP,
                controller.nextMove(true, false, false, false, 0));
    }

    @Test
    public void heldDirectionRepeatsWithoutCatchingUp() {
        PlayerMoveController controller = new PlayerMoveController();
        long interval = PlayerMoveController.REPEAT_INTERVAL_NANOS;

        assertEquals(Direction.RIGHT,
                controller.nextMove(false, false, false, true, 0));
        assertNull(controller.nextMove(
                false, false, false, true, interval - 1));
        assertEquals(Direction.RIGHT,
                controller.nextMove(false, false, false, true, interval));
        assertEquals(Direction.RIGHT,
                controller.nextMove(false, false, false, true, interval * 10));
        assertNull(controller.nextMove(
                false, false, false, true, interval * 10));
    }

    @Test
    public void releaseStopsAndPressAgainMovesImmediately() {
        PlayerMoveController controller = new PlayerMoveController();

        assertEquals(Direction.LEFT,
                controller.nextMove(false, false, true, false, 0));
        assertNull(controller.nextMove(false, false, false, false, 10));
        assertEquals(Direction.LEFT,
                controller.nextMove(false, false, true, false, 20));
    }

    @Test
    public void newestHeldDirectionWinsAndReleaseRestoresPrevious() {
        PlayerMoveController controller = new PlayerMoveController();

        assertEquals(Direction.UP,
                controller.nextMove(true, false, false, false, 0));
        assertEquals(Direction.RIGHT,
                controller.nextMove(true, false, false, true, 10));
        assertNull(controller.nextMove(true, false, false, true, 20));
        assertEquals(Direction.UP,
                controller.nextMove(true, false, false, false, 30));
    }
}
