public class Palindrome {
    public Deque<Character> wordToDeque(String word) {
        Deque<Character> deque = new LinkedListDeque<>();
        for(char c: word.toCharArray()) {
            deque.addLast(c);

        }
        return deque;
    }

    public boolean isPalindrome(String word) {
        Deque<Character> deque = wordToDeque(word);
        while(deque.size() > 1) {
            char a = deque.removeFirst();
            char b = deque.removeLast();
            if(a != b ) {  //remove返回的是Charactor，其为引用类型，不能直接比较。转化为char可以。
                return false;
            }
        }
        return true;
    }

    public boolean isPalindrome(String word, CharacterComparator cc) {
        Deque<Character> deque = wordToDeque(word);
        while (deque.size() > 1) {
            char a = deque.removeFirst();
            char b = deque.removeLast();
            if(!cc.equalChars(a,b)) {
                return false;
            }
        }
        return true;
    }
}

