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
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  private boolean parsed;
  private final XPathParser parser;
  private String environment;
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  /**
   * 将XMLConfigBuilder解析成存储MyBatis配置信息的Configuration对象
   * @return
   */
  public Configuration parse() {
    /**
     * 每个配置文件只解析一次
     */
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    /**
     * 从XML配置文件的configuration节点开始解析，因为MyBatis的配置信息都是在xml的这个节点下的
     */
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  /**
   * 解析XML文件中的节点
   * @param root
   */
  private void parseConfiguration(XNode root) {
    try {
      // issue #117 read properties first
      /**
       * 解析 properties 配置
       */
      propertiesElement(root.evalNode("properties"));
      /**
       * 解析settings配置，并将其转换为Properties对象
       * <settings>相关配置是MyBatis中非常重要的配置，这些配置用于调整MyBatis运行时的行为。
       *
       * mybatis官网对<settings>节点可选项的文档：
       * https://mybatis.org/mybatis-3/zh/configuration.html#settings
       */
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      /**
       * 加载vfs
       */
      loadCustomVfs(settings);
      loadCustomLogImpl(settings);
      /**
       * 解析别名相关的配置,用于处理ResultMap、ResultType、ParameterType、ParameterMap
       */
      typeAliasesElement(root.evalNode("typeAliases"));
      /**
       * 插件的解析
       */
      pluginElement(root.evalNode("plugins"));
      /**
       * 自定义实例化对象的行为
       * 比如，MyBatis执行完SQL后，封装返回值对象（例如User对象）时，
       * 可能会需要赋一些默认值，可以继承ObjectFactory接口的实现类DefaultObjectFactory
       * 并将这个配置类添加到XML中的<objectFactory>标签中以实现这个效果
       */
      objectFactoryElement(root.evalNode("objectFactory"));
      /**
       * objectWrapperFactory和reflectorFactory配合MateObject，
       * 方便反射操作实体类的对象
       */
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      /**
       * 解析 reflectorFactory 配置
       */
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      /**
       * <settings> 中的信息设置到 Configuration 对象中
       */
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      /**
       * 解析JDBC相关的环境变量配置信息
       * 事务管理器和数据源配置在<environments>节点中
       */
      environmentsElement(root.evalNode("environments"));
      /**
       * 解析databaseIdProvider节点的配置信息
       * mybatis可以根据不同的数据库厂商执行不同的语句,
       * 这种多厂商的支持是基于映射语句中的databaseId属性.
       * mybatis会加载不带databaseId属性和带有匹配当前数据库databaseId属性的所有语句.
       * 如果同时找到带有databaseId和不带databaseId的相同语句,则后者会被舍弃.
       */
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      /**
       * 解析MyBatis的类型处理器相关的配置
       * 无论是 MyBatis 在预处理语句（PreparedStatement）中设置一个参数时，
       * 还是从结果集中取出一个值时，都会用类型处理器将获取的值以合适的方式转换成 Java 类型。
       * Mybatis默认为我们实现了许多TypeHandler, 当我们没有配置指定TypeHandler时，
       * Mybatis会根据参数或者返回结果的不同，默认为我们选择合适的TypeHandler处理。
       *
       * 这里解析的就是用户自定义的相关TypeHandler的配置
       */
      typeHandlerElement(root.evalNode("typeHandlers"));
      /**
       * 解析mappers节点
       */
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    /**
     * 获取<settings>节点下的子节点的内容
     */
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    /**
     * 创建Configuration类的"元信息"对象
     */
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    /**
     * 通过MetaClass检测Configuration中是否存在某个属性的setter方法，不存在则抛出异常
     */
    for (Object key : props.keySet()) {
      /**
       * 检测Configuration中是否存在相关属性，不存在则抛出异常
       */
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
  }

  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        /**
         * 别名的设置方式有两种，第一种通过package批量设置指定目录下有关别名的相关配置
         * 第二种通过alias和type节点进行设置,alias属性的值指别名的名称，
         * type的值指这个别名所指向的对象类型
         */
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            /**
             * 实例化这个别名表示的类型的Class对象
             */
            Class<?> clazz = Resources.classForName(type);
            /**
             * 注册别名名称-别名Class对象的关系
             */
            if (alias == null) {
              /**
               * 如果别名的名称为空，则判断这个Class对象上是否有@Alias注解，如果有的话，
               * 使用这个注解的值作为这个别名的名字
               */
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  /**
   * 解析有关插件的配置
   *
   * 插件是 MyBatis 提供的一个拓展机制，通过插件机制我们可在 SQL 执行过程中的某些
   * 点上做一些自定义操作。实现一个插件需要比简单，首先需要让插件类实现 Interceptor
   * 接口。然后在插件类上添加@Intercepts 和@Signature 注解，用于指定想要拦截的目标
   * 方法。MyBatis 允许拦截下面接口中的一些方法：
   * Executor:
   *      update，query，flushStatements，commit，rollback，getTransaction，close，isClosed
   * ParameterHandler:
   *      getParameterObject，setParameters
   * ResultSetHandler:
   *      handleResultSets，handleOutputParameters
   * StatementHandler:
   *      prepare，parameterize，batch，update，query
   *
   * @param parent
   * @throws Exception
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        /**
         * 获取配置信息
         */
        Properties properties = child.getChildrenAsProperties();
        /**
         * 解析拦截器的类型，并创建拦截器
         */
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();
        /**
         * 设置属性
         */
        interceptorInstance.setProperties(properties);
        /**
         * 添加拦截器到Configuration中
         */
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  /**
   * 解析ObjectFactory相关的配置信息
   * @param context
   * @throws Exception
   */
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties properties = context.getChildrenAsProperties();
      ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(properties);
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      configuration.setReflectorFactory(factory);
    }
  }

  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      /**
       * 解析 properties 的子节点，并将这些节点内容转换为属性对象 Properties
       */
      Properties defaults = context.getChildrenAsProperties();
      /**
       * 获取 properties 节点中的 resource 和 url 属性值
       */
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      /**
       * resource和url的值只能存在一个，都存在直接报错
       */
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      /**
       * 因为上面的Properties defaults = context.getChildrenAsProperties();这行代码会先解析
       * <properties>节点下的子节点的内容，所以下面的从文件系统或网络读取的属性配置，会覆盖掉
       * <properties>节点下的同名的属性和值
       */
      if (resource != null) {
        /**
         * 从文件系统中加载并解析属性文件
         */
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        /**
         * 通过 url 加载并解析属性文件
         */
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      parser.setVariables(defaults);
      /**
       * 将属性值设置到 configuration 中
       */
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    /**
     * 设置 autoMappingBehavior 属性，默认值为 PARTIAL
     */
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    /**
     * 设置 cacheEnabled 属性，默认值为 true
     */
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    /**
     * 解析并设置默认的枚举处理器
     */
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    configuration.setShrinkWhitespacesInSql(booleanValueOf(props.getProperty("shrinkWhitespacesInSql"), false));
    configuration.setDefaultSqlProviderType(resolveClass(props.getProperty("defaultSqlProviderType")));
  }

  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      if (environment == null) {
        /**
         * 获取default属性
         */
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        /**
         * 获取 id 属性
         */
        String id = child.getStringAttribute("id");
        /**
         * 检测当前 environment 节点的 id 与其父节点 environments 的
         * 属性 default 内容是否一致，一致则返回 true，否则返回 false
         */
        if (isSpecifiedEnvironment(id)) {
          /**
           * 解析transactionManager节点
           */
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          /**
           * 解析 dataSource节点，逻辑和插件的解析逻辑很相似，找到DataSourceFactory类型的数据源工厂实现类
           */
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          /**
           * 从数据源工厂中获取数据源
           */
          DataSource dataSource = dsFactory.getDataSource();
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          /**
           * 构建Environment对象，并设置到Configuration中
           */
          configuration.setEnvironment(environmentBuilder.build());
          break;
        }
      }
    }
  }

  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    /**
     * 如果设置了databaseIdProvider节点
     */
    if (context != null) {
      /**
       * 提取type类型
       */
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      /**
       * 打一个补丁
       */
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      /**
       * 获取属性
       */
      Properties properties = context.getChildrenAsProperties();
      /**
       * 构造实例
       */
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
      /**
       * 设置属性
       */
      databaseIdProvider.setProperties(properties);
    }
    /**
     * 提取数据源的对象
     */
    Environment environment = configuration.getEnvironment();
    /**
     * 如果都不为空
     */
    if (environment != null && databaseIdProvider != null) {
      /**
       * 获取到对应的数据库id标识
       */
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      /**
       * 将数据库id设置到存储MyBatis相关配置的Configuration中
       */
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        /**
         * 从指定的包中注册TypeHandler
         */
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          /**
           * 注册方法
           */
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          /**
           * 获取这个TypeHandler要使用的java类型
           */
          String javaTypeName = child.getStringAttribute("javaType");
          /**
           * 获取这个TypeHandler所针对的jdbc数据类型
           */
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          /**
           * 获取这个TypeHandler的实现类对象路径名
           */
          String handlerTypeName = child.getStringAttribute("handler");
          /**
           * 解析上面获取到的属性值
           */
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          /**
           * 根据 javaTypeClass 和 jdbcType 值的情况进行不同的注册策略
           */
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              /**
               * 注册TypeHandler
               */
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              /**
               * 注册TypeHandler
               */
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        /**
         * package节点和mapper节点只能在mappers节点下边同时出现一个
         * 使用package节点时，会自动扫描这个package下的mapper文件并添加进要使用的Mapper集合中
         */
        if ("package".equals(child.getName())) {
          String mapperPackage = child.getStringAttribute("name");
          configuration.addMappers(mapperPackage);
        } else {
          /**
           * resource节点用于指定要使用的MyBatis SQL XML的全路径名(例如:resources/mapper/MyBatisDemoMapper.xml)
           */
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          /**
           * mapper节点下的resource、url、class三个节点只能设置一个节点，其他两个必须为空
           */
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            try(InputStream inputStream = Resources.getResourceAsStream(resource)) {
              /**
               * 解析这个mapper resource中指定的xml mapper文件，将其转换为XMLMapperBuilder对象
               */
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
              /**
               * 解析xml文件并检查xml格式正确性
               */
              mapperParser.parse();
            }
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            try(InputStream inputStream = Resources.getUrlAsStream(url)){
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
              mapperParser.parse();
            }
          } else if (resource == null && url == null && mapperClass != null) {
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    }
    if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    }
    return environment.equals(id);
  }

}
