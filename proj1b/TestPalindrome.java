import org.junit.Test;
import static org.junit.Assert.*;

public class TestPalindrome {
    // You must use this palindrome, and not instantiate
    // new Palindromes, or the autograder might be upset.
    static Palindrome palindrome = new Palindrome();
    static CharacterComparator offByOne = new OffByOne();

    @Test
    public void testWordToDeque() {
        Deque d = palindrome.wordToDeque("persiflage");
        String actual = "";
        for (int i = 0; i < "persiflage".length(); i++) {
            actual += d.removeFirst();
        }
        assertEquals("persiflage", actual);
    }

    @Test
    public void testPalindrome() {
        //以下表明了Character之间不能直接比较
        Character c1 = 'A'; // 缓存范围内
        Character c2 = 'A';
        System.out.println(c1 == c2);  // true
        Character c3 = 300; // 缓存范围外
        Character c4 = 300;
        System.out.println(c3 == c4);  // false

        //正式测试
        assertTrue(palindrome.isPalindrome(""));
        assertTrue(palindrome.isPalindrome("a"));
        assertTrue(palindrome.isPalindrome("racecar"));
        assertTrue(palindrome.isPalindrome("noon"));
        assertFalse(palindrome.isPalindrome("horse"));
        assertFalse(palindrome.isPalindrome("rancor"));
        assertFalse(palindrome.isPalindrome("aaaaab"));
    }

    @Test
    public void testPalindromeOffByOne() {
        assertTrue(palindrome.isPalindrome("", offByOne));
        assertTrue(palindrome.isPalindrome("a", offByOne));
        assertTrue(palindrome.isPalindrome("ab", offByOne));
        assertTrue(palindrome.isPalindrome("ba", offByOne));
        assertTrue(palindrome.isPalindrome("aaabbb", offByOne));
        assertTrue(palindrome.isPalindrome("bbbaaa", offByOne));
        assertFalse(palindrome.isPalindrome("ok", offByOne));
        assertFalse(palindrome.isPalindrome("racecar", offByOne));
        assertTrue(palindrome.isPalindrome("flake", offByOne));
        assertTrue(palindrome.isPalindrome("10", offByOne));
        assertFalse(palindrome.isPalindrome("24", offByOne));
    }
}


