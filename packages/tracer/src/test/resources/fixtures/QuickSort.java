class Solution {
    public int[] quickSort(int[] nums) {
        int low = 0;
        int high = nums.length - 1;

        quick(nums, low, high);

        return nums;
    }

    public void quick(int[] nums, int low, int high) {
        if (low >= high) return;

        int pIndex = placeInPosition(nums, low, high);

        quick(nums, low, pIndex - 1);
        quick(nums, pIndex + 1, high);
    }

    public int placeInPosition(int[] nums, int low, int high) {
    int pivot = nums[low];
    int i = low;
    int j = high;

    while (i < j) {

        while (i < high && nums[i] <= pivot) {
            i++;
        }

        while (j > low && nums[j] > pivot) {
            j--;
        }

        if (i < j) {
            int temp = nums[i];
            nums[i] = nums[j];
            nums[j] = temp;
        }
    }

    int temp = nums[low];
    nums[low] = nums[j];
    nums[j] = temp;

    return j;
}
}
