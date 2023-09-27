/*
 *    Copyright 2009-2022 the original author or authors.
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

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.binding.MapperProxy.MapperMethodInvoker;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Lasse Voss
 */
public class MapperProxyFactory<T> {

  /**
   * Mapper 接口
   */
  private final Class<T> mapperInterface;
  /**
   * 方法与 MapperMethod的映射
   */
  private final Map<Method, MapperMethodInvoker> methodCache = new ConcurrentHashMap<>();

  public MapperProxyFactory(Class<T> mapperInterface) {
    this.mapperInterface = mapperInterface;
  }

  /**
   * 获取Mapper 接口
   * @return
   */
  public Class<T> getMapperInterface() {
    return mapperInterface;
  }

  /**
   * 获取映射缓存集合
   * @return
   */
  public Map<Method, MapperMethodInvoker> getMethodCache() {
    return methodCache;
  }

  @SuppressWarnings("unchecked")
  protected T newInstance(MapperProxy<T> mapperProxy) {
    // 创建指定接口的代理实例
    return (T) Proxy.newProxyInstance(
      mapperInterface.getClassLoader(), // 用于定义代理类的类加载器
      new Class[] { mapperInterface }, // 要实现的代理类的接口列表
      mapperProxy); // 代理类
  }

  public T newInstance(SqlSession sqlSession) {
    // 创建一个代理类
    final MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);
    return newInstance(mapperProxy);
  }

}
