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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {

  private final XPathParser parser;
  private final MapperBuilderAssistant builderAssistant;
  private final Map<String, XNode> sqlFragments;
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  /**
   * 解析sql xml文件中的mapper节点并检查xml格式正确性
   */
  public void parse() {
    /**
     * 先判断之前有没有解析过这个mapper文件，如果已经解析过就不再进行解析
     */
    if (!configuration.isResourceLoaded(resource)) {
      /**
       * 解析sql xml文件中的mapper节点，包含mapper节点中指定的namespace，
       * 以及mapper节点下的select、insert、update、delete操作SQL的节点，
       * 如果xml格式不正确，会直接抛出异常，终止执行
       */
      configurationElement(parser.evalNode("/mapper"));
      /**
       * 将这个resource表示的mapper文件标记为已加载
       */
      configuration.addLoadedResource(resource);
      /**
       * 绑定namespace和mapper文件的关系，后续才可直接通过调用mapper接口方法
       * 执行与之对应的SQL语句
       */
      bindMapperForNamespace();
    }

    /**
     * 重新解析之前解析不了的节点
     */
    parsePendingResultMaps();
    parsePendingCacheRefs();
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  /**
   * 解析Mapper文件中的节点
   * @param context
   */
  private void configurationElement(XNode context) {
    try {
      /**
       * 解析mapper节点上的namespace的值，确定这个mapper文件绑定的MyBatis接口对象
       */
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.isEmpty()) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      /**
       * 设置namespace
       */
      builderAssistant.setCurrentNamespace(namespace);
      /**
       * 解析 <cache-ref> 节点
       * 在 MyBatis 中，二级缓存是可以共用的。
       * 这需要通过<cache-ref>节点为命名空间配置参照缓存,比如像下面这样:
       *
       * 第一个mapper文件
       * <mapper namespace="tech.punklu.dao.Mapper1">
       *    <cache-ref namespace="tech.punklu.dao.Mapper2"/>
       * </mapper>
       *
       * 第二个配置文件：
       * <mapper namespace="tech.punklu.dao.Mapper2">
       *     <cache/>
       * </mapper>
       * 就实现了两个mapper文件共享二级缓存
       */
      cacheRefElement(context.evalNode("cache-ref"));
      /**
       * 解析 <cache> 节点
       * MyBatis 提供了一、二级缓存，其中一级缓存是 SqlSession 级别的，默认为开启状态。
       * 二级缓存配置在映射文件中，使用者需要显示配置才能开启。如果无特殊要求，二级缓存的
       * 配置很简单。如下：
       * <cache/>
       *
       * 如果想修改缓存的一些属性，可以像下面这样配置:
       * <cache
       *  eviction="FIFO"
       *  flushInterval="60000"
       *  size="512"
       *  readOnly="true"/>
       *
       * 根据上面的配置创建出的缓存有以下特点：
       * 1. 按先进先出的策略淘汰缓存项
       * 2. 缓存的容量为 512 个对象引用
       * 3. 缓存每隔 60 秒刷新一次
       * 4. 缓存返回的对象是写安全的，即在外部修改对象不会影响到缓存内部存储对象
       *
       * 除了上面两种配置方式，还可以给 MyBatis 配置第三方缓存或者自己实现的缓存等。
       * 比如，将 Ehcache 缓存整合到 MyBatis 中，可以这样配置:
       * <cache type="org.mybatis.caches.ehcache.EhcacheCache"/>
       *    <property name="timeToIdleSeconds" value="3600"/>
       *    <property name="timeToLiveSeconds" value="3600"/>
       *    <property name="maxEntriesLocalHeap" value="1000"/>
       *    <property name="maxEntriesLocalDisk" value="10000000"/>
       *    <property name="memoryStoreEvictionPolicy" value="LRU"/>
       * </cache>
       */
      cacheElement(context.evalNode("cache"));
      /**
       * 解析parameterMap节点
       */
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      /**
       * 解析用于定义返回结果的resultMap节点
       */
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      /**
       * 解析<sql></sql>节点
       */
      sqlElement(context.evalNodes("/mapper/sql"));
      /**
       * 解析select、insert、update、delete节点
       */
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }

  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      /**
       * 创建用于生成Statement的构建类
       */
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        /**
         * 解析XML中的Statement节点(SELECT|UPDATE|DELETE|INSERT)，
         * 并将解析结果存储到configuration的mappedStatements集合中
         */
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        /**
         *  XML语句有问题时，存储到集合中，等第一次解析完之后再重新解析
         */
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

  /**
   * 解析二级缓存相关的配置
   * @param context
   */
  private void cacheRefElement(XNode context) {
    if (context != null) {
      /**
       * 向Configuration中添加二级缓存配置
       * 第一个参数和第二个参数分别为在配置文件中声明的
       * 需要共享二级缓存的两个mapper文件的namespace的值
       */
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      /**
       * 创建CacheRefResolver实例
       */
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        /**
         * 解析参照缓存
         */
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        /**
         * 捕捉IncompleteElementException异常，
         * 并将cacheRefResolver存入到Configuration的incompleteCacheRefs集合中
         */
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  private void cacheElement(XNode context) {
    if (context != null) {
      /**
       * 获取各种属性，如果属性值未在配置文件中显式设置的话，使用第二个参数作为默认值
       */
      String type = context.getStringAttribute("type", "PERPETUAL");
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      String eviction = context.getStringAttribute("eviction", "LRU");
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      Long flushInterval = context.getLongAttribute("flushInterval");
      Integer size = context.getIntAttribute("size");
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      boolean blocking = context.getBooleanAttribute("blocking", false);
      /**
       * 获取子节点配置
       */
      Properties props = context.getChildrenAsProperties();
      /**
       * 构建缓存对象
       */
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  private void parameterMapElement(List<XNode> list) {
    for (XNode parameterMapNode : list) {
      /**
       * 获得parameterMap节点的id标识符
       */
      String id = parameterMapNode.getStringAttribute("id");
      /**
       * 取得所指向的Class类对象的全路径名
       */
      String type = parameterMapNode.getStringAttribute("type");
      /**
       * 获得所指向的Class对象
       */
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  private void resultMapElements(List<XNode> list) {
    /**
     * 遍历<resultMap>节点列表
     */
    for (XNode resultMapNode : list) {
      try {
        /**
         * 解析resultMap节点
         */
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  private ResultMap resultMapElement(XNode resultMapNode) {
    return resultMapElement(resultMapNode, Collections.emptyList(), null);
  }

  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    /**
     * 获取resultMap的type属性的值
     */
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    /**
     * 解析type属性对应的类的类型
     */
    Class<?> typeClass = resolveClass(type);
    if (typeClass == null) {
      typeClass = inheritEnclosingType(resultMapNode, enclosingType);
    }
    Discriminator discriminator = null;
    List<ResultMapping> resultMappings = new ArrayList<>(additionalResultMappings);
    /**
     * 获取并便利<resultMap>的子节点列表
     */
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) {
      if ("constructor".equals(resultChild.getName())) {
        /**
         * 解析constructor节点，并生成对应的ResultMapping
         * 一般情况下，定义的实体类都是简单的Java对象，即POJO。
         * 这种对象包含一些私有属性和相应的 getter/setter 方法，
         * 通常这种 POJO 可以满足大部分需求。但如果你想使用
         * 不可变类存储查询结果，则就需要做一些改动。比如把 POJO 的 setter 方法移除，
         * 增加构造方法用于初始化成员变量。对于这种不可变的 Java 类，
         * 需要通过带有参数的构造方法进行初始化（反射也可以达到同样目的）
         *
         * 例子:
         * <resultMap>
         *   <constructor>
         *      <idArg column="id" name="id"/>
         *      <arg column="title" name="title"/>
         *      <arg column="content" name="content"/>
         *   </constructor>
         * </resultMap>
         */
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
        /**
         * 解析discriminator节点
         */
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        List<ResultFlag> flags = new ArrayList<>();
        if ("id".equals(resultChild.getName())) {
          /**
           * 添加ID到flags集合中
           */
          flags.add(ResultFlag.ID);
        }
        /**
         * 解析id和property节点，并生成对应的ResultMapping
         */
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    /**
     * 获取resultMap中的id属性的值
     */
    String id = resultMapNode.getStringAttribute("id",
            resultMapNode.getValueBasedIdentifier());
    /**
     * 获取extends和autoMapping属性
     */
    String extend = resultMapNode.getStringAttribute("extends");
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      /**
       * 根据前面获取到的信息构建resultMap对象
       */
      return resultMapResolver.resolve();
    } catch (IncompleteElementException e) {
      /**
       * 如果发生IncompleteElementException异常，
       * 将resultMapResolver添加到incompleteResultMaps集合中
       */
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
    if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      String property = resultMapNode.getStringAttribute("property");
      if (property != null && enclosingType != null) {
        MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
        return metaResultType.getSetterType(property);
      }
    } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      return enclosingType;
    }
    return null;
  }

  /**
   * <resultMap>
   *   <constructor>
   *      <idArg column="id" name="id"/>
   *      <arg column="title" name="title"/>
   *      <arg column="content" name="content"/>
   *   </constructor>
   * </resultMap>
   * @param resultChild
   * @param resultType
   * @param resultMappings
   */
  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) {
    /**
     * 获取子节点列表
     */
    List<XNode> argChildren = resultChild.getChildren();
    for (XNode argChild : argChildren) {
      List<ResultFlag> flags = new ArrayList<>();
      /**
       * 向 flags 中添加 CONSTRUCTOR 标志
       */
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        /**
         * 向 flags 中添加 ID 标志
         */
        flags.add(ResultFlag.ID);
      }
      /**
       * 构建 ResultMapping
       */
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<>();
    for (XNode caseChild : context.getChildren()) {
      String value = caseChild.getStringAttribute("value");
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  private void sqlElement(List<XNode> list) {
    /**
     * 如果 Configuration 的 databaseId 不为空，sqlElement 方法
     * 会被调用了两次。第一次传入具体的 databaseId，用于解析带有 databaseId 属性，且属性值与
     * 此相等的<sql>节点。第二次传入的 databaseId 为空，用于解析未配置 databaseId 属性的<sql>节点。
     */
    if (configuration.getDatabaseId() != null) {
      /**
       * 调用 sqlElement 解析 <sql> 节点
       */
      sqlElement(list, configuration.getDatabaseId());
    }
    /**
     * 再次调用 sqlElement，不同的是，这次调用，该方法的第二个参数为 null
     */
    sqlElement(list, null);
  }

  private void sqlElement(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      /**
       * 获取 id 和 databaseId 属性
       */
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      id = builderAssistant.applyCurrentNamespace(id, false);
      /**
       * 检测当前 databaseId 和 requiredDatabaseId 是否一致
       */
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        /**
         * 将 <id, XNode> 键值对缓存到 sqlFragments 中
         */
        sqlFragments.put(id, context);
      }
    }
  }

  /**
   * databaseId 的匹配规则。
   * 1. databaseId 与 requiredDatabaseId 不一致，即失配，返回 false
   * 2. 当前节点与之前的节点出现 id 重复的情况，若之前的<sql>节点 databaseId 属性
   * 3. 不为空，返回 false
   * 4. 若以上两条规则均匹配失败，此时返回 true
   * @param id
   * @param databaseId
   * @param requiredDatabaseId
   * @return
   */
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      /**
       * 当前 databaseId 和目标 databaseId 不一致时，返回 false
       */
      return requiredDatabaseId.equals(databaseId);
    }
    /**
     * 如果目标 databaseId 为空，但当前 databaseId 不为空。两者不一致，返回 false
     */
    if (databaseId != null) {
      return false;
    }
    /**
     * 如果当前 <sql> 节点的 id 与之前的 <sql> 节点重复，且先前节点
     * databaseId 不为空。则忽略当前节点，并返回 false
     */
    if (!this.sqlFragments.containsKey(id)) {
      return true;
    }
    // skip this fragment if there is a previous one with a not null databaseId
    XNode context = this.sqlFragments.get(id);
    return context.getStringAttribute("databaseId") == null;
  }

  /**
   * 构建ResultMapping对象
   * resultMap对象的使用示例：
   * <resultMap id="articleResult" type="Article">
   *    <id property="id" column="id"/>
   *    <result property="title" column="article_title"/>
   * </resultMap>
   *
   * @param context
   * @param resultType
   * @param flags
   * @return
   */
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) {
    String property;
    /**
     * 根据节点类型获取name或property属性
     */
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      property = context.getStringAttribute("property");
    }
    /**
     * 获取其他各种属性
     */
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String nestedSelect = context.getStringAttribute("select");
    /**
     * 解析resultMap属性，该属性出现在<association>和<collection>节点中
     * 若这两个节点包含resultMap属性，则调用processNestedResultMappings方法解析嵌套resultMap
     */
    String nestedResultMap = context.getStringAttribute("resultMap", () ->
        processNestedResultMappings(context, Collections.emptyList(), resultType));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    String typeHandler = context.getStringAttribute("typeHandler");
    String resultSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    /**
     * 解析javaType、typeHandler的类型以及枚举类型JdbcType
     */
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    /**
     * 构建ResultMapping对象
     */
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

  /**
   * 解析resultMap属性，该属性出现在<association>和<collection>节点中
   * 若这两个节点包含resultMap属性，则调用processNestedResultMappings方法解析嵌套resultMap
   * @param context
   * @param resultMappings
   * @param enclosingType
   * @return
   */
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) {
    if (Arrays.asList("association", "collection", "case").contains(context.getName())
        && context.getStringAttribute("select") == null) {
      validateCollection(context, enclosingType);
      /**
       * resultMapElement是解析ResultMap入口方法
       */
      ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
      return resultMap.getId();
    }
    return null;
  }

  protected void validateCollection(XNode context, Class<?> enclosingType) {
    if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
        && context.getStringAttribute("javaType") == null) {
      MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
      String property = context.getStringAttribute("property");
      if (!metaResultType.hasSetter(property)) {
        throw new BuilderException(
            "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
      }
    }
  }

  private void bindMapperForNamespace() {
    /**
     * 获取xml中<mapper>节点中的namespace指定的的命名空间路径名
     */
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        /**
         * 根据命名空间解析对应的mapper接口的Class
         */
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        // ignore, bound type is not required
      }
      /**
       * 检测当前 mapper 类是否被绑定过
       */
      if (boundType != null && !configuration.hasMapper(boundType)) {
        // Spring may not know the real resource name so we set a flag
        // to prevent loading again this resource from the mapper interface
        // look at MapperAnnotationBuilder#loadXmlResource
        configuration.addLoadedResource("namespace:" + namespace);
        /**
         * 绑定mapper类
         */
        configuration.addMapper(boundType);
      }
    }
  }

}
