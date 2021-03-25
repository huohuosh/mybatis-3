/**
 *    Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.parsing;

/**
 * 通用的 Token 解析器
 * @author Clinton Begin
 */
public class GenericTokenParser {

  /**
   * 开始的 Token 字符串
   */
  private final String openToken;
  /**
   * 结束的 Token 字符串
   */
  private final String closeToken;
  private final TokenHandler handler;

  public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
    this.openToken = openToken;
    this.closeToken = closeToken;
    this.handler = handler;
  }

  /**
   * 循环寻找 openToken 和 closeToken 之间的表达式（去掉转义符）
   * 使用 handler 解析表达式
   * @param text
   * @return
   */
  public String parse(String text) {
    // 空字符串返回
    if (text == null || text.isEmpty()) {
      return "";
    }
    // search open token
    // 没有起始 token 字符串，直接返回
    int start = text.indexOf(openToken);
    if (start == -1) {
      return text;
    }
    char[] src = text.toCharArray();
    // 起始查找位置
    int offset = 0;
    final StringBuilder builder = new StringBuilder();
    StringBuilder expression = null;
    while (start > -1) {
      // 前一个字符为转义字符，去掉转义字符
      if (start > 0 && src[start - 1] == '\\') {
        // this open token is escaped. remove the backslash and continue.
        // 前一个字符是转移字符
        // 添加 [offset, start - offset - 1] 和 openToken 的内容，添加到 builder 中
        builder.append(src, offset, start - offset - 1).append(openToken);
        // 修改 offset
        offset = start + openToken.length();
      } else {
        // found open token. let's search close token.
        // 创建/重置 expression 对象
        if (expression == null) {
          expression = new StringBuilder();
        } else {
          expression.setLength(0);
        }
        // 将 offset 和 openToken 之间的内容放入 builder
        builder.append(src, offset, start - offset);
        // offset 从 openToken 之后开始
        offset = start + openToken.length();
        // 查找 closeToken
        int end = text.indexOf(closeToken, offset);
        // 循环查找非转义的 closeToken
        // 如果是转义的 closeToken，和前面内容一起加入 expression,继续查找 closeToken
        // 如果找到 closeToken，和前面内容一起加入 expression，设置 offset，跳出循环
        while (end > -1) {
          if (end > offset && src[end - 1] == '\\') {
            // this close token is escaped. remove the backslash and continue.
            expression.append(src, offset, end - offset - 1).append(closeToken);
            offset = end + closeToken.length();
            end = text.indexOf(closeToken, offset);
          } else {
            expression.append(src, offset, end - offset);
            offset = end + closeToken.length();
            break;
          }
        }
        // 如果上面没有找到 closeToken，builder 放入之后 src 所有内容， offset 为字符串长度
        // 如果找到 closeToken，使用 handler 处理表达式后加入 builder， offset 为 closeToken 之后
        if (end == -1) {
          // close token was not found.
          builder.append(src, start, src.length - start);
          offset = src.length;
        } else {
          builder.append(handler.handleToken(expression.toString()));
          offset = end + closeToken.length();
        }
      }
      // 继续，寻找新的 openToken 位置
      start = text.indexOf(openToken, offset);
    }
    // 后续没有 openToken，拼接剩余字符串
    if (offset < src.length) {
      builder.append(src, offset, src.length - offset);
    }
    return builder.toString();
  }
}
