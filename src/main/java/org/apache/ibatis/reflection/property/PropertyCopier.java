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
package org.apache.ibatis.reflection.property;

import java.lang.reflect.Field;

import org.apache.ibatis.reflection.Reflector;

/**
 * @author Clinton Begin
 */
public final class PropertyCopier {

  private PropertyCopier() {
    // Prevent Instantiation of Static Class
  }

  /**
   * 将 sourceBean 的属性复制到 destinationBean 中
   * @param type
   * @param sourceBean
   * @param destinationBean
   */
  public static void copyBeanProperties(Class<?> type, Object sourceBean, Object destinationBean) {
    // 从指定的Class对象开始向上追溯父类
    Class<?> parent = type;
    // 直到追溯到最顶层的父类（Object类）
    while (parent != null) {
      // 获取当前类的所有声明字段（包括私有字段）
      final Field[] fields = parent.getDeclaredFields();
      // 遍历每个字段
      for (Field field : fields) {
        try {
          try {
            // 尝试直接通过反射将源Bean中的字段值赋给目标Bean中的对应字段
            field.set(destinationBean, field.get(sourceBean));
          } catch (IllegalAccessException e) {
            // 检查是否可以控制成员可访问性,如果不能控制直接抛出异常
            if (!Reflector.canControlMemberAccessible()) {
              throw e;
            }
            // 设置可访问性
            field.setAccessible(true);
            // 再次尝试通过反射将源Bean中的字段值赋给目标Bean中的对应字段
            field.set(destinationBean, field.get(sourceBean));
          }
        } catch (Exception e) {
          // Nothing useful to do, will only fail on final fields, which will be ignored.
        }
      }
      // 获取当前类的父类，继续遍历父类中的字段
      parent = parent.getSuperclass();
    }
  }

}
