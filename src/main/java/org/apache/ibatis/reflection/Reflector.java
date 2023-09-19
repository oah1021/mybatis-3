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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker;
import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.util.MapUtil;

/**
 * This class represents a cached set of class definition information that allows for easy mapping between property
 * names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  private static final MethodHandle isRecordMethodHandle = getIsRecordMethodHandle();
  /**
   * 对应的类
   */
  private final Class<?> type;
  /**
   * 可读属性数组
   */
  private final String[] readablePropertyNames;
  /**
   * 可写属性数组
   */
  private final String[] writablePropertyNames;
  /**
   * 属性对应的 setting 方法的映射
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();
  /**
   * 属性对应的 getting 方法的映射
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();
  /**
   * 属性对应的 setting 方法的方法参数类型的映射。
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  /**
   * 属性对应的 getting 方法的返回值类型的映射。
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  /**
   * 默认构造方法
   */
  private Constructor<?> defaultConstructor;
  /**
   * 不区分大小写的属性集合
   */
  private final Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  public Reflector(Class<?> clazz) {
    // 设置对应的类
    type = clazz;
    // 初始化 默认构造方法
    addDefaultConstructor(clazz);
    // 获取类中的所有方法，包括 private 方法
    Method[] classMethods = getClassMethods(clazz);
    // 判断当前类是否为 Java 15 才有的 record 类
    if (isRecord(type)) {
      // 如果是 record 类，则添加相关的 getting 方法
      addRecordGetMethods(classMethods);
    } else {
      // 如果不是 record 类，则初始化 getMethods 和 setMethods ，通过遍历 getting 方法
      addGetMethods(classMethods);
      addSetMethods(classMethods);
      // 初始化 fields 数组，包括 public、protected、default修饰的字段，并将这些字段存储到 fields 数组中
      addFields(clazz);
    }
    // getMethods 中的键即是可读属性
    readablePropertyNames = getMethods.keySet().toArray(new String[0]);
    // setMethods 中的键即是可写属性
    writablePropertyNames = setMethods.keySet().toArray(new String[0]);
    for (String propName : readablePropertyNames) {
      // 不区分大小写的属性集合
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      // 不区分大小写的属性集合
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addRecordGetMethods(Method[] methods) {
    // 保留 参数长度为0 的方法， 添加到getMethods
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0)
        .forEach(m -> addGetMethod(m.getName(), m, false));
  }

  private void addDefaultConstructor(Class<?> clazz) {
    // 获取所有的构造函数
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();
    // 保留 无参构造
    Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0).findAny()
        .ifPresent(constructor -> this.defaultConstructor = constructor);
  }

  private void addGetMethods(Method[] methods) {
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    // 保留 参数长度为0的方法 并且方法名是以get 和 is开头的方法
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
        .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
    resolveGetterConflicts(conflictingGetters);
  }

  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    // 遍历冲突的 getter 方法， 因为有桥接方法
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;
      String propName = entry.getKey();
      // 是否 是二义性
      boolean isAmbiguous = false;
      for (Method candidate : entry.getValue()) {
        if (winner == null) {
          winner = candidate;
          continue;
        }
        // 获取冲突方法的返回值类型
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        // 如果 getter 方法返回类型相同
        if (candidateType.equals(winnerType)) {
          // 特殊情况处理，如果是 boolean 类型的 getter 方法，优先选择以 "is" 开头的方法
          if (!boolean.class.equals(candidateType)) {
            // 返回类型相同且不是 boolean 类型，说明存在二义性
            isAmbiguous = true;
            break;
          }
          // 是否以 "is" 开头
          if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
          // 如果 candidateType 是 winnerType 的子类或实现类
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // getter 方法的类型为 winnerType 的子类或实现类，无需处理
          // OK getter type is descendant
        }
        // 如果 winnerType 是 candidateType 的子类或实现类
        else if (winnerType.isAssignableFrom(candidateType)) {
          winner = candidate;
        }
        // 返回类型不一致，并且两者之间不存在继承或实现关系
        else {
          isAmbiguous = true;
          break;
        }
      }
      // 将解决后的 getter 方法添加到 getMethods 中
      addGetMethod(propName, winner, isAmbiguous);
    }
  }

  private void addGetMethod(String name, Method method, boolean isAmbiguous) {
    // 如果存在二义性，则创建一个可以跑出异常的MethodInvoker 对象，否则创建一个普通的 MethodInvoker 对象
    MethodInvoker invoker = isAmbiguous ? new AmbiguousMethodInvoker(method, MessageFormat.format(
        "Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
        name, method.getDeclaringClass().getName())) : new MethodInvoker(method);
    getMethods.put(name, invoker);
    Type returnType = TypeParameterResolver.resolveReturnType(method, type);
    getTypes.put(name, typeToClass(returnType));
  }

  private void addSetMethods(Method[] methods) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
        .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    // 是否是有效的属性名
    if (isValidPropertyName(name)) {
      List<Method> list = MapUtil.computeIfAbsent(conflictingMethods, name, k -> new ArrayList<>());
      list.add(method);
    }
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (Entry<String, List<Method>> entry : conflictingSetters.entrySet()) {
      String propName = entry.getKey();
      List<Method> setters = entry.getValue();
      Class<?> getterType = getTypes.get(propName);
      boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
      boolean isSetterAmbiguous = false;
      Method match = null;
      for (Method setter : setters) {
        if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        if (!isSetterAmbiguous) {
          match = pickBetterSetter(match, setter, propName);
          isSetterAmbiguous = match == null;
        }
      }
      if (match != null) {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    }
    if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
        MessageFormat.format(
            "Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.", property,
            setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
    setMethods.put(property, invoker);
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
    setTypes.put(property, typeToClass(paramTypes[0]));
    return null;
  }

  private void addSetMethod(String name, Method method) {
    MethodInvoker invoker = new MethodInvoker(method);
    setMethods.put(name, invoker);
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
    setTypes.put(name, typeToClass(paramTypes[0]));
  }

  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) {
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  private void addFields(Class<?> clazz) {
    // 获取 clazz 类 中所有的字段，但不包括继承的
    Field[] fields = clazz.getDeclaredFields();
    // 遍历每个字段
    for (Field field : fields) {
      // 如果 setMethods 中不包含key为 field
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        // 获取该字段的修饰符
        int modifiers = field.getModifiers();
        // 检查字段的修饰符是否为 final 或 static，如果不是，则调用 addSetField 方法
        if ((!Modifier.isFinal(modifiers) || !Modifier.isStatic(modifiers))) {
          addSetField(field);
        }
      }
      // 如果 getMethods 中不包含该字段的名称，则调用 addGetField 方法
      if (!getMethods.containsKey(field.getName())) {
        addGetField(field);
      }
    }
    // 递归处理父类的字段
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    return (!name.startsWith("$") && !"serialVersionUID".equals(name) && !"class".equals(name));
  }

  /**
   * This method returns an array containing all methods declared in this class and any superclass. We use this method,
   * instead of the simpler <code>Class.getMethods()</code>, because we want to look for private methods as well.
   * 此方法返回一个数组，其中包含此类和任何超类中声明的所有方法。
   * @param clazz
   *          The class
   *
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> clazz) {
    // 方法签名与方法的映射，用于去重
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = clazz;
    // 循环当前类及其超类（不包括 Object.class）
    while (currentClass != null && currentClass != Object.class) {
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 由于类可能是抽象类，还需要查找接口中的方法
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        // 将接口中的方法添加到 uniqueMethods 中
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }
      // 获取父类，继续循环
      currentClass = currentClass.getSuperclass();
    }
    // 获取去重后的方法集合
    Collection<Method> methods = uniqueMethods.values();
    // 将集合转换为 Method 数组并返回
    return methods.toArray(new Method[0]);
  }

  /**
   *
   * @param uniqueMethods 签名与方法的映射
   * @param methods 方法集合
   */
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    // 遍历每个方法
    for (Method currentMethod : methods) {
      // 如果不是桥接方法
      if (!currentMethod.isBridge()) {
        // 计算签名
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        // 如果 map映射中不存在，则添加
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 方法签名，（格式：返回类型#方法名:参数类型1,参数类型2,...）
   * @param method
   * @return
   */
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    sb.append(returnType.getName()).append('#');
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   *
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    }
    throw new ReflectionException("There is no default constructor for " + type);
  }

  /**
   * 是否存在默认构造 存在 return true
   * @return
   */
  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * 属性对应的 getMethod 方法
   * @param propertyName
   * @return
   */
  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName
   *          - the name of the property
   *
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   *  属性对应的 getter 方法的返回值类型
   * @param propertyName
   *          - the name of the property
   *
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName
   *          - the name of the property to check
   *
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.containsKey(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *  检查属性是否有 getter 方法
   * @param propertyName
   *          - the name of the property to check
   *
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.containsKey(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }

  /**
   * Class.isRecord() alternative for Java 15 and older.
   */
  private static boolean isRecord(Class<?> clazz) {
    try {
      return isRecordMethodHandle != null && (boolean) isRecordMethodHandle.invokeExact(clazz);
    } catch (Throwable e) {
      throw new ReflectionException("Failed to invoke 'Class.isRecord()'.", e);
    }
  }

  private static MethodHandle getIsRecordMethodHandle() {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    MethodType mt = MethodType.methodType(boolean.class);
    try {
      return lookup.findVirtual(Class.class, "isRecord", mt);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }
}
