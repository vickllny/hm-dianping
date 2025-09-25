package com.hmdp;

import org.junit.Test;

/**
 * https://leetcode.cn/studyplan/top-interview-150/
 */
public class LeetcodeTests {

    @Test
    /**
     * https://leetcode.cn/problems/merge-sorted-array/?envType=study-plan-v2&envId=top-interview-150
     * 合并2个数组
     */
    public void test(){
        int[] nums1 = {-1,0,0,3,3,3,0,0,0};
        int[] nums2 = {1,2,2};

        int m = 6;
        int n = 3;
        mergeSortedArray(nums1, m, nums2, n);
    }

    static void mergeSortedArray(int[] nums1, int m, int[] nums2, int n){
        if(m == 0){
            if(n != 0){
                System.arraycopy(nums2, 0, nums1, 0, n);
            }
            return;
        }else if(n == 0){
            return;
        }


//        int i = 0,j = 0, index = 0;
//        int[] arr = new int[m + n];
//        while (i < m && j < n){
//            arr[index++] = nums1[i] <= nums2[j]? nums1[i++] : nums2[j++];
//        }
//        while (i < m){
//            arr[index++] = nums1[i++];
//        }
//        while (j < n){
//            arr[index++] = nums2[j++];
//        }
//        System.arraycopy(arr, 0, nums1, 0, m + n);

        //从后往前
        int i = m - 1,j = n - 1, index = m + n - 1;
        while (i >= 0 && j >= 0){
            nums1[index--] = nums1[i] >= nums2[j] ? nums1[i--] : nums2[j--];
        }
        while (j >= 0){
            nums1[index--] = nums2[j--];
        }
    }
}
