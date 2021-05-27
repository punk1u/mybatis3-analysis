package org.apache.ibatis.punklu;

import java.sql.*;

/**
 * JDBC原生操作数据库的例子
 * @author punk1u
 */
public class TestJdbc {

  static {
    try {
      Class.forName(Driver.class.getName());
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] args) throws SQLException {
    Connection root = DriverManager.getConnection("jdbc:mysql://localhost:3306/test/characterEncoding=utf-8", "root", "123456");
    PreparedStatement preparedStatement = root.prepareStatement("select  * from test where id = ?");
    preparedStatement.setString(1,"1");
    ResultSet resultSet = preparedStatement.executeQuery();
    while (resultSet.next()) {
      String columnName = resultSet.getMetaData().getColumnName(1);
      String columnName1 = resultSet.getMetaData().getColumnName(2);
      System.out.println(columnName + ":" + resultSet.getString(1));
      System.out.println(columnName1 + ":" + resultSet.getString(2));
    }
    resultSet.close();
    preparedStatement.close();
    root.close();
  }
}
