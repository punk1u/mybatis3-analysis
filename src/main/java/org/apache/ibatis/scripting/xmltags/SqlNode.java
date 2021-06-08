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
package org.apache.ibatis.scripting.xmltags;

/**
 * 在执行 SQL 之前，需要将 SQL 语句完整的解析出来。我们都知道 SQL 是配置在映射文
 * 件中的，但由于映射文件中的 SQL 可能会包含占位符#{}，以及动态 SQL 标签，比如<if>、
 * <where>等。因此，我们并不能直接使用映射文件中配置的 SQL。MyBatis 会将映射文件中
 * 的 SQL 解析成一组 SQL 片段。如果某个片段中也包含动态 SQL 相关的标签，那么，MyBatis
 * 会对该片段再次进行分片。一个 SQL 配置将会被解析成一个 SQL 节点(SqlNode)片段树.
 *
 * 我们需要对片段树进行解析，以便从每个片段对象中获取相应的内容。然后将这些内容
 * 组合起来即可得到一个完成的 SQL 语句，这个完整的 SQL 以及其他的一些信息最终会存储
 * 在 BoundSql 对象中
 *
 * 用于表示mapper xml中的sql节点（<where>、<foreach>、<set>、<if>）的接口
 * @author Clinton Begin
 */
public interface SqlNode {
  boolean apply(DynamicContext context);
}
