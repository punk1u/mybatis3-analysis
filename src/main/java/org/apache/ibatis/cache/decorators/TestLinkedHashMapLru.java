package org.apache.ibatis.cache.decorators;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试LinkedHashMap实现Lru
 */
public class TestLinkedHashMapLru {

  public static void main(String[] args) {
    Map<String,String> testMap = new LinkedHashMap<>(1024,.75f,true);
    testMap.put("1","1");
    testMap.put("2","2");
    testMap.put("3","3");
    testMap.put("4","4");
    testMap.put("5","5");

    for(Map.Entry<String, String> entry : testMap.entrySet()) {
      System.out.println(entry.getKey());
    }

    System.out.println("------------------------");

    String second = testMap.get("2");
    String third = testMap.get("3");
    for(Map.Entry<String, String> entry : testMap.entrySet()) {
      System.out.println(entry.getKey());
    }
  }
}
