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
package org.apache.ibatis.builder.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;

/**
 * 基于方法上的 SQL Provider 注解的 SqlSource 实现类
 * @see MapperAnnotationBuilder#SQL_PROVIDER_ANNOTATION_TYPES
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class ProviderSqlSource implements SqlSource {

  private final Configuration configuration;
  private final SqlSourceBuilder sqlSourceParser;
  /**
   * SQL Provider 注解对应的 type
   * 例如 {@link SelectProvider#type()}
   */
  private final Class<?> providerType;
  /**
   * SQL Provider 注解对应的 method
   * 例如 {@link SelectProvider#method()} 对应的 Method
   */
  private Method providerMethod;
  /**
   * {@link #providerMethod} 对应的参数名列表
   */
  private String[] providerMethodArgumentNames;
  /**
   * {@link #providerMethod} 对应的参数类型列表
   */
  private Class<?>[] providerMethodParameterTypes;
  /**
   * 如果参数有 ProviderContext 类型，创建该对象
   */
  private ProviderContext providerContext;
  /**
   * 参数 ProviderContext 类型在 {@link #providerMethodParameterTypes} 对应的下标
   */
  private Integer providerContextIndex;

  /**
   * @deprecated Please use the {@link #ProviderSqlSource(Configuration, Object, Class, Method)} instead of this.
   */
  @Deprecated
  public ProviderSqlSource(Configuration configuration, Object provider) {
    this(configuration, provider, null, null);
  }

  /**
   * @since 3.4.5
   */
  public ProviderSqlSource(Configuration configuration, Object provider, Class<?> mapperType, Method mapperMethod) {
    String providerMethodName;
    try {
      this.configuration = configuration;
      // 创建 SqlSourceBuilder 对象
      this.sqlSourceParser = new SqlSourceBuilder(configuration);
      //  SQL Provider 注解对应的 type
      this.providerType = (Class<?>) provider.getClass().getMethod("type").invoke(provider);
      // //  SQL Provider 注解对应的 method
      providerMethodName = (String) provider.getClass().getMethod("method").invoke(provider);

      for (Method m : this.providerType.getMethods()) {
        if (providerMethodName.equals(m.getName()) && CharSequence.class.isAssignableFrom(m.getReturnType())) {
          if (providerMethod != null){
            throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                    + providerMethodName + "' is found multiple in SqlProvider '" + this.providerType.getName()
                    + "'. Sql provider method can not overload.");
          }
          this.providerMethod = m;
          // 解析参数名和参数类型
          this.providerMethodArgumentNames = new ParamNameResolver(configuration, m).getNames();
          this.providerMethodParameterTypes = m.getParameterTypes();
        }
      }
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error creating SqlSource for SqlProvider.  Cause: " + e, e);
    }
    if (this.providerMethod == null) {
      throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
          + providerMethodName + "' not found in SqlProvider '" + this.providerType.getName() + "'.");
    }
    // 初始化 providerContext 和 providerContextIndex 属性
    for (int i = 0; i< this.providerMethodParameterTypes.length; i++) {
      Class<?> parameterType = this.providerMethodParameterTypes[i];
      if (parameterType == ProviderContext.class) {
        if (this.providerContext != null){
          throw new BuilderException("Error creating SqlSource for SqlProvider. ProviderContext found multiple in SqlProvider method ("
              + this.providerType.getName() + "." + providerMethod.getName()
              + "). ProviderContext can not define multiple in SqlProvider method argument.");
        }
        this.providerContext = new ProviderContext(mapperType, mapperMethod);
        this.providerContextIndex = i;
      }
    }
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    // 创建 SqlSource 对象
    SqlSource sqlSource = createSqlSource(parameterObject);
    return sqlSource.getBoundSql(parameterObject);
  }

  private SqlSource createSqlSource(Object parameterObject) {
    try {
      int bindParameterCount = providerMethodParameterTypes.length - (providerContext == null ? 0 : 1);
      String sql;
      // 根据条件获得 SQL
      if (providerMethodParameterTypes.length == 0) {
        sql = invokeProviderMethod();
      } else if (bindParameterCount == 0) {
        sql = invokeProviderMethod(providerContext);
      } else if (bindParameterCount == 1 &&
              (parameterObject == null || providerMethodParameterTypes[providerContextIndex == null || providerContextIndex == 1 ? 0 : 1].isAssignableFrom(parameterObject.getClass()))) {
        sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
      } else if (parameterObject instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) parameterObject;
        sql = invokeProviderMethod(extractProviderMethodArguments(params, providerMethodArgumentNames));
      } else {
        throw new BuilderException("Error invoking SqlProvider method ("
                + providerType.getName() + "." + providerMethod.getName()
                + "). Cannot invoke a method that holds "
                + (bindParameterCount == 1 ? "named argument(@Param)": "multiple arguments")
                + " using a specifying parameterObject. In this case, please specify a 'java.util.Map' object.");
      }
      // 获取参数类型，解析出 SqlSource 对象
      Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
      return sqlSourceParser.parse(replacePlaceholder(sql), parameterType, new HashMap<>());
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error invoking SqlProvider method ("
          + providerType.getName() + "." + providerMethod.getName()
          + ").  Cause: " + e, e);
    }
  }

  private Object[] extractProviderMethodArguments(Object parameterObject) {
    if (providerContext != null) {
      Object[] args = new Object[2];
      args[providerContextIndex == 0 ? 1 : 0] = parameterObject;
      args[providerContextIndex] = providerContext;
      return args;
    } else {
      return new Object[] { parameterObject };
    }
  }

  private Object[] extractProviderMethodArguments(Map<String, Object> params, String[] argumentNames) {
    Object[] args = new Object[argumentNames.length];
    for (int i = 0; i < args.length; i++) {
      if (providerContextIndex != null && providerContextIndex == i) {
        args[i] = providerContext;
      } else {
        args[i] = params.get(argumentNames[i]);
      }
    }
    return args;
  }

  private String invokeProviderMethod(Object... args) throws Exception {
    Object targetObject = null;
    if (!Modifier.isStatic(providerMethod.getModifiers())) {
      targetObject = providerType.newInstance();
    }
    CharSequence sql = (CharSequence) providerMethod.invoke(targetObject, args);
    return sql != null ? sql.toString() : null;
  }

  private String replacePlaceholder(String sql) {
    return PropertyParser.parse(sql, configuration.getVariables());
  }

}
