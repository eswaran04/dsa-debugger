class Solution {
    public int total() {
        return new Pair(1, 2).sum();
    }
}

class Pair {
    private final int a;
    private final int b;

    Pair(int a, int b) {
        this.a = a;
        this.b = b;
    }

    int sum() {
        return a + b;
    }
}
