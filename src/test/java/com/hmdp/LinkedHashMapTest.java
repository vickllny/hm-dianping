package com.hmdp;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class LinkedHashMapTest {

    @Test
    public void test1(){
        final LinkedHashMap<String, String> map = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return this.size() > 1000;
            }
        };
    }
}
