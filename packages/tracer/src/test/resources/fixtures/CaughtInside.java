class Solution {
    public int safe(int n) {
        try {
            int x = n / 0;
            return x;
        } catch (ArithmeticException e) {
            return -1;
        }
    }
}
