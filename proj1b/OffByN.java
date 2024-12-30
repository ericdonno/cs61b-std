public class OffByN implements CharacterComparator{
    private int offN;

    public OffByN(int N) {
        this.offN = N;
    }

    @Override
    public boolean equalChars(char x, char y) {
        return Math.abs(x-y) == offN;
    }
}
