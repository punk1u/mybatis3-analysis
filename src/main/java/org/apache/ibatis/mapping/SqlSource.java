/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.mapping;

/**
 * 表示从XML文件或注解读取的映射语句的内容。它创建将从用户接收的输入参数传递到数据库的SQL。
 * Represents the content of a mapped statement read from an XML file or an annotation.
 * It creates the SQL that will be passed to the database out of the input parameter received from the user.
 *
 * 在五个实现类中，ProviderSqlSource和VelocitySqlSource不常使用。
 *
 * 剩下的三个实现类中，仅前两个实现类会在映射文件解析的过程中被使用。
 * 仅DynamicSqlSource和RawSqlSource会在映射文件解析的过程中被使用。
 * 当 SQL 配置中包含${}（不是#{}）占位符，或者包含<if>、<where>等标签时，会被认为是
 * 动态 SQL，此时使用 DynamicSqlSource 存储 SQL 片段。否则，使用 RawSqlSource 存储 SQL
 * 配置信息。相比之下 DynamicSqlSource 存储的 SQL 片段类型较多，解析起来也更为复杂一些。
 *
 * @author Clinton Begin
 */
public interface SqlSource {

  /**
   * 获得当前SqlSource的BoundSql对象
   * @param parameterObject
   * @return
   */
  BoundSql getBoundSql(Object parameterObject);

}
