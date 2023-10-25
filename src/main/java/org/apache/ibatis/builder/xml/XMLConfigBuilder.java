/*
 *    Copyright 2009-2023 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
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
  /**
   * 是否已经解析
   */
  private boolean parsed;
  /**
   * 基于 Java XPath解析器
   */
  private final XPathParser parser;
  /**
   * 环境
   */
  private String environment;
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(Configuration.class, reader, environment, props);
  }

  public XMLConfigBuilder(Class<? extends Configuration> configClass, Reader reader, String environment,
                          Properties props) {
    this(configClass, new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(Configuration.class, inputStream, environment, props);
  }

  public XMLConfigBuilder(Class<? extends Configuration> configClass, InputStream inputStream, String environment,
                          Properties props) {
    this(configClass, new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(Class<? extends Configuration> configClass, XPathParser parser, String environment,
                           Properties props) {
    // 创建Configuration 对象
    super(newConfig(configClass));

    ErrorContext.instance().resource("SQL Mapper Configuration");
    // 设置Configuration 对象的 variables 属性
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  public Configuration parse() {
    // parsed 默认为 false， 判断是否已经解析过该配置文件
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    // 设置为 true，标识该配置文件已经解析过一次
    parsed = true;
    // 解析 XML 配置文件中的 <configuration> 节点
    parseConfiguration(parser.evalNode("/configuration"));
    // 返回解析后的 Configuration 对象
    return configuration;
  }

  private void parseConfiguration(XNode root) {
    try {
      // issue #117 read properties first
      // 解析 properties 元素，加载配置文件中定义的属性值到 Configuration 对象中
      // 这是全局变量
      propertiesElement(root.evalNode("properties"));
      // 将 settings 元素解析成 Properties 对象，并加载到 Configuration 对象中
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      // 加载自定义的 VFS 实现类
      loadCustomVfs(settings);
      // 加载自定义的 Log 实现类
      loadCustomLogImpl(settings);
      // 解析 typeAliases 元素，将别名信息加入 Configuration 对象中
      typeAliasesElement(root.evalNode("typeAliases"));
      // 解析 plugins 元素，将插件信息加入 Configuration 对象中
      pluginElement(root.evalNode("plugins"));
      // 解析 objectFactory 元素，将对象工厂信息加入 Configuration 对象中
      objectFactoryElement(root.evalNode("objectFactory"));
      // 解析 objectWrapperFactory 元素，将对象包装工厂信息加入 Configuration 对象中
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      // 解析 reflectorFactory 元素，将对象反射工厂信息加入 Configuration 对象中
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      // 解析 settings 元素，将全局配置信息加入 Configuration 对象中
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      // 解析 environments 元素，将数据源信息加入 Configuration 对象中
      // 将 ${driver} ${url} 解析成真正的值就是发生在 root.evalNode 这里
      environmentsElement(root.evalNode("environments"));
      // 解析 databaseIdProvider 元素，将数据库厂商标识信息加入 Configuration 对象中
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      // 解析 typeHandlers 元素，将类型处理器信息加入 Configuration 对象中
      typeHandlerElement(root.evalNode("typeHandlers"));
      // 解析 mappers 元素，将 Mapper 映射器信息加入 Configuration 对象中
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      // 配置文件解析出现错误，抛出异常
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    // context 为null，则创建一个Properties对象
    if (context == null) {
      return new Properties();
    }
    // 解析子标签为Properties 对象
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    // 获得 Configuration类的元类
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    // 遍历Properties对象中的key
    for (Object key : props.keySet()) {
      // 如果某个key 不存在 setter方法，抛出异常
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException(
          "The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    // 获得 vfsImpl 属性
    String value = props.getProperty("vfsImpl");
    // 如果 value != null
    if (value != null) {
      // 使用 , 作为分隔符，得到 VFS类名的数组
      String[] clazzes = value.split(",");
      // 遍历VFS类数组
      for (String clazz : clazzes) {
        // clazz不为空
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          // 通过全限定类名 加载类
          // 加载这个类并设置到 configuration中
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    // 根据别名解析为对应的类
    // 获取 logImpl 属性值解析为对应的Class
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    // 将实现类设置到 configuration中
    configuration.setLogImpl(logImpl);
  }

  /**
   * 可以通过 XmlConfigBuilderTest 类中的 shouldSuccessfullyLoadXMLConfigFile测试方法来测试
   * @param parent
   */
  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      // 遍历子节点
      for (XNode child : parent.getChildren()) {
        // 指定为包的情况下，注册包下的所有类
        if ("package".equals(child.getName())) {
          // 获得包名
          String typeAliasPackage = child.getStringAttribute("name");
          // 注册包下的所有类
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else { // 指定了类
          // 类别名
          String alias = child.getStringAttribute("alias");
          // 类名
          String type = child.getStringAttribute("type");
          try {
            // 根据全限定类名获取类
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              // 没有别名，使用类的简单名称作为别名
              typeAliasRegistry.registerAlias(clazz);
            } else {
              // 直接注册
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      // 遍历 plugins标签
      for (XNode child : parent.getChildren()) {
        // 获取拦截器的名称
        String interceptor = child.getStringAttribute("interceptor");
        // 子标签的key value
        Properties properties = child.getChildrenAsProperties();
        // 根据拦截器的全限定类名创建一个实例
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor()
          .newInstance();
        // 设置拦截器的属性
        interceptorInstance.setProperties(properties);
        // 添加拦截器到 configuration
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获得objectFactory 的实现类
      String type = context.getStringAttribute("type");
      // 获的 Properties 属性
      Properties properties = context.getChildrenAsProperties();
      // 根据全限定类名获取类并实例化
      ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置 Properties属性
      factory.setProperties(properties);
      // 设置 configuration的 objectFactory属性
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

  /**
   * 解析 properties 节点
   * @param context
   * @throws Exception
   */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      // 读取子标签们，为 Properties 对象
      Properties defaults = context.getChildrenAsProperties();
      // 获取 resource 属性值
      String resource = context.getStringAttribute("resource");
      // 获取 url 属性值
      String url = context.getStringAttribute("url");
      // resource 和 url 都为 null，则抛出异常
      if (resource != null && url != null) {
        throw new BuilderException(
          "The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      // 如果 resource != null，
      if (resource != null) {
        // 读取 本地Properties 配置文件到 defaults 中
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        // 读取 远程Properties 配置文件到 defaults 中
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      // 覆盖 configuration中的 Properties对象到 defaults中
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      // 设置 defaults到 parser和configuration 中
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    configuration
      .setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(
      AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
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
    configuration.setLazyLoadTriggerMethods(
      stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    configuration.setShrinkWhitespacesInSql(booleanValueOf(props.getProperty("shrinkWhitespacesInSql"), false));
    configuration.setArgNameBasedConstructorAutoMapping(
      booleanValueOf(props.getProperty("argNameBasedConstructorAutoMapping"), false));
    configuration.setDefaultSqlProviderType(resolveClass(props.getProperty("defaultSqlProviderType")));
    configuration.setNullableOnForEach(booleanValueOf(props.getProperty("nullableOnForEach"), false));
  }

  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      // 如果 environment属性为null，从 default属性获得
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      // 遍历 environments 标签
      for (XNode child : context.getChildren()) {
        // 获取 id 属性值 （环境：如 test）
        String id = child.getStringAttribute("id");
        // 判断是否是指定的环境
        if (isSpecifiedEnvironment(id)) {
          // 构建并返回事物工厂
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 构建并返回数据源工厂
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          // 获取数据源
          DataSource dataSource = dsFactory.getDataSource();
          // 创建环境对象并设置 id transactionFactory dataSource 属性的值
          Environment.Builder environmentBuilder = new Environment.Builder(id).transactionFactory(txFactory)
            .dataSource(dataSource);
          // 设置 environment 到 configuration 中
          configuration.setEnvironment(environmentBuilder.build());
          break;
        }
      }
    }
  }

  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  /**
   * 通过 transactionManager 构建并返回事物工厂对象
   * @param context
   * @return
   * @throws Exception
   */
  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      // 获取 transactionManager 标签的 type 属性
      String type = context.getStringAttribute("type");
      // 将子节点转为Properties属性对象
      Properties props = context.getChildrenAsProperties();
      // 根据type值找到对应的Class，获取默认的无参构造，创建实例
      TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置事物工厂的属性
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      // 获取 dataSource 标签的type属性 （通常是全限定类名）
      String type = context.getStringAttribute("type");
      // 将子节点转为Properties属性对象
      Properties props = context.getChildrenAsProperties();
      // 根据type值找到对应的Class，获取默认的无参构造，创建实例
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置数据源工厂的 属性
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      // 遍历 typeHandlers 标签
      for (XNode child : parent.getChildren()) {
        // 如果子节点的名称是 package
        if ("package".equals(child.getName())) {
          // 获取其节点的name 属性值
          String typeHandlerPackage = child.getStringAttribute("name");
          // 注册类型处理程序
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          // 获取对应属性名的值
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          // 将属性值解析为对应的类
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          // 注册类型处理程序
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
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
      // 遍历 <mappers> 下的所有子节点
      for (XNode child : parent.getChildren()) {
        // 解析 package 节点
        if ("package".equals(child.getName())) {
          // 获取其节点下的name属性值
          String mapperPackage = child.getStringAttribute("name");
          configuration.addMappers(mapperPackage);
        } else { // 解析 resource、url 或 class 节点
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          if (resource != null && url == null && mapperClass == null) { // 解析 resource 节点
            ErrorContext.instance().resource(resource);
            try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource,
                configuration.getSqlFragments());
              mapperParser.parse();
            }
          } else if (resource == null && url != null && mapperClass == null) { // 解析 url 节点
            ErrorContext.instance().resource(url);
            try (InputStream inputStream = Resources.getUrlAsStream(url)) {
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url,
                configuration.getSqlFragments());
              mapperParser.parse();
            }
          } else if (resource == null && url == null && mapperClass != null) { // 解析 class 节点
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException(
              "A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  /**
   * 当前环境是否是指定的环境
   * @param id
   * @return
   */
  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    }
    if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    }
    return environment.equals(id);
  }

  private static Configuration newConfig(Class<? extends Configuration> configClass) {
    try {
      return configClass.getDeclaredConstructor().newInstance();
    } catch (Exception ex) {
      throw new BuilderException("Failed to create a new Configuration instance.", ex);
    }
  }

}
