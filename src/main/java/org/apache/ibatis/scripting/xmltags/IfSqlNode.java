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
 * 用于存储<if>节点的内容
 * @author Clinton Begin
 */
public class IfSqlNode implements SqlNode {
  private final ExpressionEvaluator evaluator;
  /**
   * <if>节点中的test表达式
   */
  private final String test;
  /**
   * <if>节点中的SqlNode节点，如果<if>节点的test表达式为真，则这个SqlNode中的文本将会被添加到最终执行的SQL中
   */
  private final SqlNode contents;

  public IfSqlNode(SqlNode contents, String test) {
    this.test = test;
    this.contents = contents;
    this.evaluator = new ExpressionEvaluator();
  }

  @Override
  public boolean apply(DynamicContext context) {
    /**
     * 通过ONGL评估test表达式的结果
     */
    if (evaluator.evaluateBoolean(test, context.getBindings())) {
      /**
       * 如果test表达式中的条件成立，则调用其他节点的apply方法进行解析
       */
      contents.apply(context);
      return true;
    }
    return false;
  }

}
