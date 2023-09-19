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
package org.apache.ibatis.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * @author Clinton Begin
 */
public class MetaClass {

  private final ReflectorFactory reflectorFactory;
  private final Reflector reflector;

  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
    // 获取指定类的 Reflector 对象
    this.reflector = reflectorFactory.findForClass(type);
  }

  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    // 创建指定类的 MetaClass 对象
    return new MetaClass(type, reflectorFactory);
  }

  public MetaClass metaClassForProperty(String name) {
    // 获取属性的类型Class
    Class<?> propType = reflector.getGetterType(name);
    // 创建对应的 MetaClass
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 查找给定名称的属性
   * @param name
   * @return
   */
  public String findProperty(String name) {
    StringBuilder prop = buildProperty(name, new StringBuilder());
    return prop.length() > 0 ? prop.toString() : null;
  }

  /**
   *
   * @param name
   * @param useCamelCaseMapping 是否使用驼峰映射
   * @return
   */
  public String findProperty(String name, boolean useCamelCaseMapping) {
    if (useCamelCaseMapping) {
      name = name.replace("_", "");
    }
    // 不使用驼峰映射，直接查找
    return findProperty(name);
  }

  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }

  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }

  public Class<?> getSetterType(String name) {
    // 分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 如果存在下一个
    if (prop.hasNext()) {
      // 创建对应的 元类
      MetaClass metaProp = metaClassForProperty(prop.getName());
      // 递归调用
      return metaProp.getSetterType(prop.getChildren());
    }
    // 不存在下一个，直接返回
    return reflector.getSetterType(prop.getName());
  }

  public Class<?> getGetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop);
      return metaProp.getGetterType(prop.getChildren());
    }
    // issue #506. Resolve the type inside a Collection Object
    return getGetterType(prop);
  }

  private MetaClass metaClassForProperty(PropertyTokenizer prop) {
    Class<?> propType = getGetterType(prop);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 获取属性对应的 getter 方法的返回值类型。
   * @param prop 属性标记器
   * @return getter 方法的返回值类型
   */
  private Class<?> getGetterType(PropertyTokenizer prop) {
    // 获取 属性对应 getter 方法的返回值类型
    Class<?> type = reflector.getGetterType(prop.getName());
    // 如果属性存在索引并且返回值类型是集合
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
      // 获取属性对应的getter 方法的返回类型
      Type returnType = getGenericGetterType(prop.getName());
      // 如果是参数化类型
      if (returnType instanceof ParameterizedType) {
        // 返回参数类型数组
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
        // 如果参数类型数组不为空 且只有一个参数
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          // 获取第一个参数的类型
          returnType = actualTypeArguments[0];
          // 如果类型是 Class
          if (returnType instanceof Class) {
            // 强转并赋值给 type
            type = (Class<?>) returnType;
          } else if (returnType instanceof ParameterizedType) {
            // 如果类型是参数化类型，则取得其原始类型
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }
    return type;
  }

  private Type getGenericGetterType(String propertyName) {
    try {
      // 获取属性对应的 getter 方法
      Invoker invoker = reflector.getGetInvoker(propertyName);
      // 说明是通过方法获取值
      if (invoker instanceof MethodInvoker) {
        Field declaredMethod = MethodInvoker.class.getDeclaredField("method");
        declaredMethod.setAccessible(true);
        Method method = (Method) declaredMethod.get(invoker);
        // 解析方法的返回类型并返回结果
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      }
      // 通过字段获取值
      if (invoker instanceof GetFieldInvoker) {
        Field declaredField = GetFieldInvoker.class.getDeclaredField("field");
        declaredField.setAccessible(true);
        Field field = (Field) declaredField.get(invoker);
        // 解析字段的类型并返回结果
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException | IllegalAccessException e) {
      // Ignored
    }
    return null;
  }

  public boolean hasSetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (!prop.hasNext()) {
      return reflector.hasSetter(prop.getName());
    }
    if (reflector.hasSetter(prop.getName())) {
      MetaClass metaProp = metaClassForProperty(prop.getName());
      return metaProp.hasSetter(prop.getChildren());
    } else {
      return false;
    }
  }

  public boolean hasGetter(String name) {
    // 分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 没有下一个分词，说明是最后一个属性名
    if (!prop.hasNext()) {
      return reflector.hasGetter(prop.getName());
    }
    // 递归判断剩余属性是否存在 Getter 方法
    if (reflector.hasGetter(prop.getName())) {
      MetaClass metaProp = metaClassForProperty(prop);
      return metaProp.hasGetter(prop.getChildren());
    } else {
      // 不存在，返回false
      return false;
    }
  }

  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }

  private StringBuilder buildProperty(String name, StringBuilder builder) {
    // 使用分词器进行分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 如果有子属性
    if (prop.hasNext()) {
      // 获取 属性名称
      String propertyName = reflector.findPropertyName(prop.getName());
      // 如果 属性名称不为 null
      if (propertyName != null) {
        builder.append(propertyName);
        builder.append(".");
        // 根据属性创建属性的 MetaClass 对象
        MetaClass metaProp = metaClassForProperty(propertyName);
        // 递归调用 当前元类的 buildProperty 继续构建子属性的名称
        metaProp.buildProperty(prop.getChildren(), builder);
      }
    } else {
      // 没有子属性
      // 获取属性名称
      String propertyName = reflector.findPropertyName(name);
      // 如果属性名称不为 null
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    // 返回构建好的属性名称
    return builder;
  }

  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}
