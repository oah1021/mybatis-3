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
package org.apache.ibatis.binding;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperRegistry {

  private final Configuration config;
  /**
   * MapperProxyFactory 的映射
   *
   * KEY：Mapper 接口
   */
  private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new ConcurrentHashMap<>();

  public MapperRegistry(Configuration config) {
    this.config = config;
  }

  @SuppressWarnings("unchecked")
  public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
    final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
    if (mapperProxyFactory == null) {
      throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
    }
    try {
      return mapperProxyFactory.newInstance(sqlSession);
    } catch (Exception e) {
      throw new BindingException("Error getting mapper instance. Cause: " + e, e);
    }
  }

  public <T> boolean hasMapper(Class<T> type) {
    return knownMappers.containsKey(type);
  }

  public <T> void addMapper(Class<T> type) {
    // 如果是接口
    if (type.isInterface()) {
      // 是否已包含此Mapper，添加过则抛出异常
      if (hasMapper(type)) {
        throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
      }
      // 表示是否加载完成
      boolean loadCompleted = false;
      try {
        knownMappers.put(type, new MapperProxyFactory<>(type));
        // It's important that the type is added before the parser is run
        // otherwise the binding may automatically be attempted by the
        // mapper parser. If the type is already known, it won't try.
        MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
        parser.parse();
        // 标记加载完成
        loadCompleted = true;
      } finally {
        if (!loadCompleted) {
          // 未加载完成，则从 knownMappers 中删除
          knownMappers.remove(type);
        }
      }
    }
  }

  /**
   * Gets the mappers.
   *
   * @return the mappers
   *
   * @since 3.2.2
   */
  public Collection<Class<?>> getMappers() {
    return Collections.unmodifiableCollection(knownMappers.keySet());
  }

  /**
   * Adds the mappers.
   *
   * @param packageName
   *          the package name
   * @param superType
   *          the super type
   *
   * @since 3.2.2
   */
  public void addMappers(String packageName, Class<?> superType) {
    // 扫描指定的包
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
    resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
    // 获取符合条件的类
    Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();
    for (Class<?> mapperClass : mapperSet) {
      // 添加
      addMapper(mapperClass);
    }
  }

  /**
   * Adds the mappers.
   *
   * @param packageName
   *          the package name
   *
   * @since 3.2.2
   */
  public void addMappers(String packageName) {
    addMappers(packageName, Object.class);
  }

}
