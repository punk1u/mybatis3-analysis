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
    /**
     * ????????????BaseBuilder????????????
     */
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  /**
   * ???XMLConfigBuilder???????????????MyBatis???????????????Configuration??????
   * @return
   */
  public Configuration parse() {
    /**
     * ?????????????????????????????????
     */
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    /**
     * ???XML???????????????configuration???????????????????????????MyBatis????????????????????????xml?????????????????????
     */
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  /**
   * ??????XML??????????????????
   * @param root
   */
  private void parseConfiguration(XNode root) {
    try {
      // issue #117 read properties first
      /**
       * ?????? properties ??????
       */
      propertiesElement(root.evalNode("properties"));
      /**
       * ??????settings???????????????????????????Properties??????
       * <settings>???????????????MyBatis???????????????????????????????????????????????????MyBatis?????????????????????
       *
       * mybatis?????????<settings>???????????????????????????
       * https://mybatis.org/mybatis-3/zh/configuration.html#settings
       */
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      /**
       * ??????vfs
       */
      loadCustomVfs(settings);
      loadCustomLogImpl(settings);
      /**
       * ???????????????????????????,????????????ResultMap???ResultType???ParameterType???ParameterMap
       */
      typeAliasesElement(root.evalNode("typeAliases"));
      /**
       * ???????????????
       */
      pluginElement(root.evalNode("plugins"));
      /**
       * ?????????????????????????????????
       * ?????????MyBatis?????????SQL????????????????????????????????????User???????????????
       * ????????????????????????????????????????????????ObjectFactory??????????????????DefaultObjectFactory
       * ??????????????????????????????XML??????<objectFactory>??????????????????????????????
       */
      objectFactoryElement(root.evalNode("objectFactory"));
      /**
       * objectWrapperFactory???reflectorFactory??????MateObject???
       * ????????????????????????????????????
       */
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      /**
       * ?????? reflectorFactory ??????
       */
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      /**
       * <settings> ????????????????????? Configuration ?????????
       */
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      /**
       * ??????JDBC?????????????????????????????????
       * ????????????????????????????????????<environments>?????????
       */
      environmentsElement(root.evalNode("environments"));
      /**
       * ??????databaseIdProvider?????????????????????
       * mybatis?????????????????????????????????????????????????????????,
       * ???????????????????????????????????????????????????databaseId??????.
       * mybatis???????????????databaseId????????????????????????????????????databaseId?????????????????????.
       * ????????????????????????databaseId?????????databaseId???????????????,?????????????????????.
       */
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      /**
       * ??????MyBatis?????????????????????????????????
       * ????????? MyBatis ?????????????????????PreparedStatement??????????????????????????????
       * ???????????????????????????????????????????????????????????????????????????????????????????????????????????? Java ?????????
       * Mybatis??????????????????????????????TypeHandler, ???????????????????????????TypeHandler??????
       * Mybatis???????????????????????????????????????????????????????????????????????????TypeHandler?????????
       *
       * ?????????????????????????????????????????????TypeHandler?????????
       */
      typeHandlerElement(root.evalNode("typeHandlers"));
      /**
       * ??????mappers??????
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
     * ??????<settings>??????????????????????????????
     */
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    /**
     * ??????Configuration??????"?????????"??????
     */
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    /**
     * ??????MetaClass??????Configuration??????????????????????????????setter?????????????????????????????????
     */
    for (Object key : props.keySet()) {
      /**
       * ??????Configuration??????????????????????????????????????????????????????
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

  /**
   * ??????typeAliases???????????????
   * ??????<typeAliases>??????????????????<package>???????????????MyBatis????????????????????????????????????????????????????????????????????????????????????
   * ??????<typeAliases>??????????????????<typeAlias>???????????????MyBatis??????????????????????????????????????????
   * ???????????????????????????configuration???typeAliasRegistry????????????
   * @param parent
   */
  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        /**
         * ????????????????????????????????????????????????package??????????????????????????????????????????????????????
         * ???????????????alias???type??????????????????,alias?????????????????????????????????
         * type?????????????????????????????????????????????
         */
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            /**
             * ???????????????????????????????????????Class??????
             */
            Class<?> clazz = Resources.classForName(type);
            /**
             * ??????????????????-??????Class???????????????
             */
            if (alias == null) {
              /**
               * ?????????????????????????????????????????????Class??????????????????@Alias???????????????????????????
               * ???????????????????????????????????????????????????
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
   * ???????????????????????????
   *
   * ????????? MyBatis ???????????????????????????????????????????????????????????? SQL ????????????????????????
   * ??????????????????????????????????????????????????????????????????????????????????????????????????? Interceptor
   * ????????????????????????????????????@Intercepts ???@Signature ??????????????????????????????????????????
   * ?????????MyBatis ?????????????????????????????????????????????
   * Executor:
   *      update???query???flushStatements???commit???rollback???getTransaction???close???isClosed
   * ParameterHandler:
   *      getParameterObject???setParameters
   * ResultSetHandler:
   *      handleResultSets???handleOutputParameters
   * StatementHandler:
   *      prepare???parameterize???batch???update???query
   *
   * @param parent
   * @throws Exception
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        /**
         * ??????????????????
         */
        Properties properties = child.getChildrenAsProperties();
        /**
         * ?????????????????????????????????????????????
         */
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();
        /**
         * ????????????
         */
        interceptorInstance.setProperties(properties);
        /**
         * ??????????????????Configuration???
         */
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  /**
   * ??????ObjectFactory?????????????????????
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
       * ?????? properties ???????????????????????????????????????????????????????????? Properties
       */
      Properties defaults = context.getChildrenAsProperties();
      /**
       * ?????? properties ???????????? resource ??? url ?????????
       */
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      /**
       * resource???url????????????????????????????????????????????????
       */
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      /**
       * ???????????????Properties defaults = context.getChildrenAsProperties();????????????????????????
       * <properties>????????????????????????????????????????????????????????????????????????????????????????????????????????????
       * <properties>?????????????????????????????????
       */
      if (resource != null) {
        /**
         * ?????????????????????????????????????????????
         */
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        /**
         * ?????? url ???????????????????????????
         */
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      parser.setVariables(defaults);
      /**
       * ????????????????????? configuration ???
       */
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    /**
     * ?????? autoMappingBehavior ????????????????????? PARTIAL
     */
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    /**
     * ?????? cacheEnabled ????????????????????? true
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
     * ???????????????????????????????????????
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
         * ??????default??????
         */
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        /**
         * ?????? id ??????
         */
        String id = child.getStringAttribute("id");
        /**
         * ???????????? environment ????????? id ??????????????? environments ???
         * ?????? default ???????????????????????????????????? true??????????????? false
         */
        if (isSpecifiedEnvironment(id)) {
          /**
           * ??????transactionManager??????,?????????<transactionManager>??????????????????type????????????
           * ?????????????????????????????????
           */
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          /**
           * ?????? dataSource?????????????????????????????????????????????????????????DataSourceFactory?????????????????????????????????
           */
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          /**
           * ????????????????????????????????????
           */
          DataSource dataSource = dsFactory.getDataSource();
          /**
           * ???????????????????????????????????????id????????????????????????Environment??????
           */
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          /**
           * ??????Environment?????????????????????Configuration???
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
     * ???????????????databaseIdProvider??????
     */
    if (context != null) {
      /**
       * ??????type??????
       */
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      /**
       * ???????????????
       */
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      /**
       * ????????????
       */
      Properties properties = context.getChildrenAsProperties();
      /**
       * ????????????
       */
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
      /**
       * ????????????
       */
      databaseIdProvider.setProperties(properties);
    }
    /**
     * ????????????????????????
     */
    Environment environment = configuration.getEnvironment();
    /**
     * ??????????????????
     */
    if (environment != null && databaseIdProvider != null) {
      /**
       * ???????????????????????????id??????
       */
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      /**
       * ????????????id???????????????MyBatis???????????????Configuration???
       */
      configuration.setDatabaseId(databaseId);
    }
  }

  /**
   * ??????mybatis??????????????????<environment>????????????<transactionManager>??????
   * @param context
   * @return
   * @throws Exception
   */
  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      /**
       * ??????<transactionManager>????????????type???????????????????????????????????????
       */
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
         * ????????????????????????TypeHandler
         */
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          /**
           * ????????????
           */
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          /**
           * ????????????TypeHandler????????????java??????
           */
          String javaTypeName = child.getStringAttribute("javaType");
          /**
           * ????????????TypeHandler????????????jdbc????????????
           */
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          /**
           * ????????????TypeHandler???????????????????????????
           */
          String handlerTypeName = child.getStringAttribute("handler");
          /**
           * ?????????????????????????????????
           */
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          /**
           * ?????? javaTypeClass ??? jdbcType ???????????????????????????????????????
           */
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              /**
               * ??????TypeHandler
               */
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              /**
               * ??????TypeHandler
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

  /**
   * <mapper>?????????????????????
   *
   * <mapper>???????????????????????????????????????
   * 1???
   *    <mappers>
   *        <package name="org.mybatis.builder"/>
   *    </mappers>
   * 2???
   *    <mappers>
   *        <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
   *    </mappers>
   * 3???
   *    <mappers>
   *        <mapper url="file:///var/mappers/AuthorMapper.xml"/>
   *    </mappers>
   * 4???
   *    <mappers>
   *        <mapper class="org.mybatis.builder.AuthorMapper"/>
   *    </mappers>
   * @param parent
   * @throws Exception
   */
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        /**
         * package?????????mapper???????????????mappers??????????????????????????????
         * ??????package?????????????????????????????????package??????mapper??????????????????????????????Mapper?????????
         */
        if ("package".equals(child.getName())) {
          String mapperPackage = child.getStringAttribute("name");
          configuration.addMappers(mapperPackage);
        } else {
          /**
           * resource??????????????????????????????MyBatis SQL XML???????????????(??????:resources/mapper/MyBatisDemoMapper.xml)
           */
          String resource = child.getStringAttribute("resource");
          /**
           * url??????????????????????????????MyBatis SQL XML???????????????????????????file:///var/mappers/AuthorMapper.xml???
           */
          String url = child.getStringAttribute("url");
          /**
           * class??????????????????????????????MyBatis SQL XML???????????????Mapper??????????????????????????????org.mybatis.builder.AuthorMapper???
           */
          String mapperClass = child.getStringAttribute("class");
          /**
           * mapper????????????resource???url???class???????????????????????????????????????????????????????????????
           */
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            try(InputStream inputStream = Resources.getResourceAsStream(resource)) {
              /**
               * ????????????mapper resource????????????xml mapper????????????????????????XMLMapperBuilder??????
               */
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
              /**
               * ??????xml???????????????xml???????????????
               */
              mapperParser.parse();
            }
            /**
             * url ????????????????????????????????????????????? url ????????????
             */
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            try(InputStream inputStream = Resources.getUrlAsStream(url)){
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
              /**
               * ??????????????????
               */
              mapperParser.parse();
            }
            /**
             * mapperClass ????????????????????????????????????????????? mapperClass ??????????????????
             */
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
