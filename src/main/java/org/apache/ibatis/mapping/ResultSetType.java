/**
 *    Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.mapping;

import java.sql.ResultSet;

/**
 * @author Clinton Begin
 */
public enum ResultSetType {
  /**
   * behavior with same as unset (driver dependent).
   *
   * @since 3.5.0
   */
  DEFAULT(-1),
  /**
   * 只能向前滚动
   * 在从 ResultSet（结果集）中读取记录的时，对于访问过的记录就自动释放了内存
   */
  FORWARD_ONLY(ResultSet.TYPE_FORWARD_ONLY),
  /**
   * 能够实现任意的前后滚动，使用各种移动的ResultSet指针的方法
   * 对于修改不敏感
   * 为了保证能游标能向上移动到任意位置，已经访问过的所有都保留在内存中不能释放
   */
  SCROLL_INSENSITIVE(ResultSet.TYPE_SCROLL_INSENSITIVE),
  /**
   * 能够实现任意的前后滚动，使用各种移动的ResultSet指针的方法
   * 对于修改敏感
   * 为了保证能游标能向上移动到任意位置，已经访问过的所有都保留在内存中不能释放
   */
  SCROLL_SENSITIVE(ResultSet.TYPE_SCROLL_SENSITIVE);

  private final int value;

  ResultSetType(int value) {
    this.value = value;
  }

  public int getValue() {
    return value;
  }
}
