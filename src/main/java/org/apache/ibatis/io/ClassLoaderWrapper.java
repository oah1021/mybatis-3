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
package org.apache.ibatis.io;

import java.io.InputStream;
import java.net.URL;

/**
 * A class to wrap access to multiple class loaders making them work as one
 * 类加载器包装类
 * @author Clinton Begin
 */
public class ClassLoaderWrapper {
  /**
   * 默认的类加载器
   */
  ClassLoader defaultClassLoader;
  /**
   * 系统类加载器
   */
  ClassLoader systemClassLoader;

  ClassLoaderWrapper() {
    try {
      // 设置系统类加载器
      systemClassLoader = ClassLoader.getSystemClassLoader();
    } catch (SecurityException ignored) {
      // AccessControlException on Google App Engine
    }
  }

  /**
   * Get a resource as a URL using the current class path
   * 查找资源
   * @param resource
   *          - the resource to locate
   *
   * @return the resource or null
   */
  public URL getResourceAsURL(String resource) {
    return getResourceAsURL(resource, getClassLoaders(null));
  }

  /**
   * Get a resource from the classpath, starting with a specific class loader
   * 使用指定的类加载器查找资源
   * @param resource
   *          - the resource to find
   * @param classLoader
   *          - the first classloader to try
   *
   * @return the stream or null
   */
  public URL getResourceAsURL(String resource, ClassLoader classLoader) {
    return getResourceAsURL(resource, getClassLoaders(classLoader));
  }

  /**
   * Get a resource from the classpath
   * 使用类加载器查找资源，返回 输入流 或者 null
   * @param resource
   *          - the resource to find
   *
   * @return the stream or null
   */
  public InputStream getResourceAsStream(String resource) {
    return getResourceAsStream(resource, getClassLoaders(null));
  }

  /**
   * Get a resource from the classpath, starting with a specific class loader
   *
   * @param resource
   *          - the resource to find
   * @param classLoader
   *          - the first class loader to try
   *
   * @return the stream or null
   */
  public InputStream getResourceAsStream(String resource, ClassLoader classLoader) {
    return getResourceAsStream(resource, getClassLoaders(classLoader));
  }

  /**
   * Find a class on the classpath (or die trying)
   *
   * @param name
   *          - the class to look for
   *
   * @return - the class
   *
   * @throws ClassNotFoundException
   *           Duh.
   */
  public Class<?> classForName(String name) throws ClassNotFoundException {
    return classForName(name, getClassLoaders(null));
  }

  /**
   * Find a class on the classpath, starting with a specific classloader (or die trying)
   *
   * @param name
   *          - the class to look for
   * @param classLoader
   *          - the first classloader to try
   *
   * @return - the class
   *
   * @throws ClassNotFoundException
   *           Duh.
   */
  public Class<?> classForName(String name, ClassLoader classLoader) throws ClassNotFoundException {
    return classForName(name, getClassLoaders(classLoader));
  }

  /**
   * Try to get a resource from a group of classloaders
   *
   * @param resource
   *          - the resource to get
   * @param classLoader
   *          - the classloaders to examine
   *
   * @return the resource or null
   */
  InputStream getResourceAsStream(String resource, ClassLoader[] classLoader) {
    // 遍历类加载器
    for (ClassLoader cl : classLoader) {
      // 如果类加载器不等于 null
      if (null != cl) {

        // try to find the resource as passed
        // 获取指定资源的输入流
        InputStream returnValue = cl.getResourceAsStream(resource);

        // now, some class loaders want this leading "/", so we'll add it and try again if we didn't find the resource
        if (null == returnValue) {
          returnValue = cl.getResourceAsStream("/" + resource);
        }

        if (null != returnValue) {
          return returnValue;
        }
      }
    }
    return null;
  }

  /**
   * Get a resource as a URL using the current class path
   *
   * @param resource
   *          - the resource to locate
   * @param classLoader
   *          - the class loaders to examine
   *
   * @return the resource or null
   */
  URL getResourceAsURL(String resource, ClassLoader[] classLoader) {

    URL url;
    // 遍历类加载器
    for (ClassLoader cl : classLoader) {

      if (null != cl) {

        // look for the resource as passed in...
        // 查找指定名称的资源
        url = cl.getResource(resource);

        // ...but some class loaders want this leading "/", so we'll add it
        // and try again if we didn't find the resource
        // 因为有一些类加载器需要 / ，所以当获取不到资源时再尝试一次
        if (null == url) {
          url = cl.getResource("/" + resource);
        }

        // "It's always in the last place I look for it!"
        // ... because only an idiot would keep looking for it after finding it, so stop looking already.
        if (null != url) {
          return url;
        }

      }

    }

    // didn't find it anywhere.
    return null;

  }

  /**
   * Attempt to load a class from a group of classloaders
   *
   * @param name
   *          - the class to load
   * @param classLoader
   *          - the group of classloaders to examine
   *
   * @return the class
   *
   * @throws ClassNotFoundException
   *           - Remember the wisdom of Judge Smails: Well, the world needs ditch diggers, too.
   */
  Class<?> classForName(String name, ClassLoader[] classLoader) throws ClassNotFoundException {
    // 遍历类加载器
    for (ClassLoader cl : classLoader) {
      // 如果类加载器 != null
      if (null != cl) {

        try {
          // 根据全限定类型 从类加载器中查找并初始化类
          return Class.forName(name, true, cl);

        } catch (ClassNotFoundException e) {
          // we'll ignore this until all classloaders fail to locate the class
        }

      }

    }

    throw new ClassNotFoundException("Cannot find class: " + name);

  }

  /**
   * 获取类加载器
   * @param classLoader
   * @return 返回类加载器数组
   */
  ClassLoader[] getClassLoaders(ClassLoader classLoader) {
    return new ClassLoader[] {
      classLoader,  // 传入的类加载器
      defaultClassLoader, // 默认的类加载器
      Thread.currentThread().getContextClassLoader(), // 当前线程持有的类加载器
      getClass().getClassLoader(), // 当前类的类加载器
      systemClassLoader // 系统类加载器
    };
  }

}
