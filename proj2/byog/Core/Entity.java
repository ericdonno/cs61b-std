package byog.Core;

import byog.TileEngine.TETile;
import byog.lab5.Position;

public abstract class Entity {
    protected Position position;
    protected TETile tile;

    public Entity(Position position, TETile tile) {
        this.position = position;
        this.tile = tile;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public TETile getTile() {
        return tile;
    }

    public void setTile(TETile tile) {
        this.tile = tile;
    }
}