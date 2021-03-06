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

import java.util.regex.Pattern;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

/**
 * 用于存储带有${}占位符的文本
 * @author Clinton Begin
 */
public class TextSqlNode implements SqlNode {
  /**
   * 含有${}占位符的SQL文本
   */
  private final String text;
  private final Pattern injectionFilter;

  public TextSqlNode(String text) {
    this(text, null);
  }

  public TextSqlNode(String text, Pattern injectionFilter) {
    this.text = text;
    this.injectionFilter = injectionFilter;
  }

  public boolean isDynamic() {
    DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
    GenericTokenParser parser = createParser(checker);
    parser.parse(text);
    return checker.isDynamic();
  }

  @Override
  public boolean apply(DynamicContext context) {
    /**
     * 创建${}占位符解析器
     */
    GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));
    /**
     * 解析${}占位符，并将解析结果添加到DynamicContext中
     */
    context.appendSql(parser.parse(text));
    return true;
  }

  /**
   * 创建占位符解析器，GenericTokenParser是一个通用解析器，并非只能解析${}占位符
   *
   * GenericTokenParser 负责将标记中的内容抽取出来，并将标记内容交给相应的
   * TokenHandler 去处理。BindingTokenParser 负责解析标记内容，并将解析结果返回给
   * GenericTokenParser，用于替换${xxx}标记
   * @param handler
   * @return
   */
  private GenericTokenParser createParser(TokenHandler handler) {
    return new GenericTokenParser("${", "}", handler);
  }

  private static class BindingTokenParser implements TokenHandler {

    private DynamicContext context;
    private Pattern injectionFilter;

    public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
      this.context = context;
      this.injectionFilter = injectionFilter;
    }

    @Override
    public String handleToken(String content) {
      Object parameter = context.getBindings().get("_parameter");
      if (parameter == null) {
        context.getBindings().put("value", null);
      } else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
        context.getBindings().put("value", parameter);
      }
      /**
       * 通过ONGL从用户传入的参数中获取结果
       */
      Object value = OgnlCache.getValue(content, context.getBindings());
      String srtValue = value == null ? "" : String.valueOf(value); // issue #274 return "" instead of "null"
      /**
       * 通过正则表达式检测strValue有效性
       */
      checkInjection(srtValue);
      return srtValue;
    }

    private void checkInjection(String value) {
      if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
        throw new ScriptingException("Invalid input. Please conform to regex" + injectionFilter.pattern());
      }
    }
  }

  private static class DynamicCheckerTokenParser implements TokenHandler {

    private boolean isDynamic;

    public DynamicCheckerTokenParser() {
      // Prevent Synthetic Access
    }

    public boolean isDynamic() {
      return isDynamic;
    }

    /**
     * 当扫描到SQL中有${}的时候调用此方法，其实就是不解析，在运行时候的时候才会替换成具体的值
     * @param content
     * @return
     */
    @Override
    public String handleToken(String content) {
      this.isDynamic = true;
      return null;
    }
  }

}
