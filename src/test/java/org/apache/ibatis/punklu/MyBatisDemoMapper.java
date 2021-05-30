package org.apache.ibatis.punklu;

import java.util.List;
import java.util.Map;

public interface MyBatisDemoMapper {
  List<Map<String,Object>> selectAll(Map<String, Object> map);
}
