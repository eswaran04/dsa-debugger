import java.util.*;

public class ValuesProbe {
    public static void main(String[] args) {
        int i = 3;
        long big = 9007199254740993L;
        char c = 'x';
        String s = "hi";
        int[] a = {1, 2};
        int[][] g = {{1}, {2, 3}};
        List<Integer> l = new ArrayList<>(List.of(4, 5));
        List<List<Integer>> ll = new ArrayList<>();
        ll.add(new ArrayList<>(List.of(1, 2)));
        ll.add(new LinkedList<>(List.of(3)));
        Map<String, Integer> m = new HashMap<>(Map.of("k", 1));
        Set<Integer> st = new HashSet<>(List.of(7));
        Deque<Integer> dq = new ArrayDeque<>();
        dq.addLast(1);
        dq.addLast(2);
        dq.addFirst(0);
        int[] huge = new int[1500];
        List<Integer> imN = List.of(1, 2, 3);
        List<Integer> im1 = List.of(9);
        Map<String, Integer> imMap1 = Map.of("a", 1);
        Set<Integer> imSet = Set.of(5);
        double inf = Double.POSITIVE_INFINITY;
        float nan = Float.NaN;
        int stop = 0;
    }
}
