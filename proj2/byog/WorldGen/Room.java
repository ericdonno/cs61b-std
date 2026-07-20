package byog.WorldGen;

import byog.TileEngine.TETile;
import byog.lab5.Position;

import static java.lang.Math.sqrt;

public interface Room {
    /**
     * Returns the size of the room.
     */
    int getSize();

    /**
     * Returns the position of the room.
     */
    Position getPosition();

    /**
     * Checks if this room intersects with another square room.
     * @param other the other square room
     * @return true if the rooms intersect, false otherwise
     */
    boolean isIntersect(SquareRoom other);

    /**
     * Checks if the room is within the bounds of the given world.
     * @param world the 2D tile array representing the world
     * @return true if the room is within bounds, false otherwise
     */
    boolean isWithinBounds(TETile[][] world);

    /**
     * Adds the room to the given world.
     * @param world the 2D tile array representing the world
     * @return true if the room was successfully added, false otherwise
     */
    boolean addSelf(TETile[][] world);
    /**
     *
     * */
     String getShape();

    /**
     * Calculates the Euclidean distance to another room.
     * @param other the other room
     * @return the distance between the two rooms
     */
    default double distanceTo(Room other) {
        double dx = this.getPosition().x - other.getPosition().x;
        double dy = this.getPosition().y - other.getPosition().y;
        return sqrt(dx*dx + dy*dy);
    }

}
