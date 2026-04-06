package byog.lab5;

import static org.junit.Assert.*;

import byog.TileEngine.Tileset;
import org.junit.Test;

public class HexCoorSysTest {
    @Test
    public void test() {
        HexCoorSys hcs1 = new HexCoorSys(new Position(35,35), 3, Tileset.PLAYER);
        hcs1.hexSet.put(new HexCoordinate(-1,0.5), new Hexagon(3,Tileset.random()));
        hcs1.hexSet.forEach((key, value) -> System.out.println("Key: " + key + ", Value: " + value.getStyle()));
        System.out.println(hcs1.hexSet.get(new HexCoordinate(0,0)));
//        hcs1.hexSet.put(new HexCoordinate(0,0), new Hexagon(3,Tileset.random()));
//        hcs1.hexSet.forEach((key, value) -> System.out.println("Key: " + key + ", Value: " + value));
        // 直接操作hexSet极为不安全。以上会导致origin变为空指针

        try {
            hcs1.addHex(1,1, Tileset.random());
        } catch (IllegalArgumentException e) {
            System.out.println(e);
        }
        try {
            hcs1.addHex(10,-25.5, Tileset.random());
        } catch (IllegalArgumentException e) {
            System.out.println(e);
        }
        try {
            hcs1.addHex(1,0.6, Tileset.random());
        } catch (IllegalArgumentException e) {
            System.out.println(e);
        }
        assertTrue(hcs1.addHex(1,1.5, Tileset.random()));
        assertTrue(hcs1.addHex(-101,38.5, Tileset.random()));
        assertTrue(hcs1.addHex(200,-1249, Tileset.random()));
        assertFalse(hcs1.addHex(0,0, Tileset.random()));
        hcs1.hexSet.forEach((key, value) -> System.out.println("Key: " + key + ", Value: " + value.getStyle()));
        System.out.println(hcs1.getHex(200,-1249).getStyle());
        System.out.println();

        try {
            hcs1.removeHex(0,0);
        } catch (IllegalArgumentException e) {
            System.out.println(e);
        }
        assertTrue(hcs1.removeHex(200,-1249));
        assertTrue(hcs1.removeHex(-101,38.5));
        assertTrue(hcs1.removeHex(1,1.5));
        assertFalse(hcs1.removeHex(1,0.5));
        assertFalse(hcs1.removeHex(10000,10000));
        hcs1.hexSet.forEach((key, value) -> System.out.println("Key: " + key + ", Value: " + value.getStyle()));

        assertTrue(hcs1.addHex(1,0.5, Tileset.random()));
        System.out.println(hcs1.getHex(1,0.5).getPosition());

    }
}
