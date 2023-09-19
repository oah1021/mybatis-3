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
package org.apache.ibatis.reflection.invoker;

import java.lang.reflect.Field;

import org.apache.ibatis.reflection.Reflector;

/**
 * @author Clinton Begin
 */
public class GetFieldInvoker implements Invoker {
  /**
   * 要获取其值的字段
   */
  private final Field field;

  public GetFieldInvoker(Field field) {
    this.field = field;
  }

  @Override
  public Object invoke(Object target, Object[] args) throws IllegalAccessException {
    try {
      // 尝试直接通过 field.get(target) 获取目标对象的字段值
      return field.get(target);
    } catch (IllegalAccessException e) {
      // 检查是否可以控制成员可访问性,如果可以
      if (Reflector.canControlMemberAccessible()) {
        // 设置字段的可访问性为 true
        field.setAccessible(true);
        // 再次获取字段的值
        return field.get(target);
      }
      throw e;
    }
  }

  @Override
  public Class<?> getType() {
    // 返回字段的类型
    return field.getType();
  }
}
