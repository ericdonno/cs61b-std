import org.junit.Test;
import static org.junit.Assert.*;

public class TestOffByN {

    // You must use this CharacterComparator and not instantiate
    // new ones, or the autograder might be upset.
    static CharacterComparator offByFive = new OffByN(5);


    // Your tests go here.
    @Test
    public void testEqualChars() {
        assertFalse(offByFive.equalChars('0', '1'));
        assertTrue(offByFive.equalChars('6', '1'));
        assertTrue(offByFive.equalChars('9', '4'));
        assertTrue(offByFive.equalChars('a', 'f'));
        assertFalse(offByFive.equalChars('b', 'a'));
        assertTrue(offByFive.equalChars((char) 71, (char) 66));
    }
}