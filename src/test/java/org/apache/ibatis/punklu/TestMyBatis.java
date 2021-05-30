package org.apache.ibatis.punklu;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class TestMyBatis {

  public static void main(String[] args) throws IOException {
    String resource = "resources/mybatis.xml";
    InputStream inputStream = Resources.getResourceAsStream(resource);
    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
    /**
     * 从调用者的角度来讲，与数据库打交道的对象 SqlSession
     */
    SqlSession sqlSession = sqlSessionFactory.openSession();
    /**
     * 通过JDK动态代理，获得最终用来执行SQL的MyBatis代理层对象
     */
    MyBatisDemoMapper myBatisDemoMapper = sqlSession.getMapper(MyBatisDemoMapper.class);
    Map<String,Object> map = new HashMap<>();
    map.put("id","1");
    System.out.println(myBatisDemoMapper.selectAll(map));
    /**
     * 关闭SqlSession连接
     */
    sqlSession.close();
  }
}
