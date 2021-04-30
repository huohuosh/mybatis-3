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
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

  private static final Object DEFERRED = new Object();

  private final Executor executor;
  private final Configuration configuration;
  private final MappedStatement mappedStatement;
  private final RowBounds rowBounds;
  private final ParameterHandler parameterHandler;
  /**
   * 用户指定的用于处理结果的处理器
   * 一般情况下，不设置
   */
  private final ResultHandler<?> resultHandler;
  private final BoundSql boundSql;
  private final TypeHandlerRegistry typeHandlerRegistry;
  private final ObjectFactory objectFactory;
  private final ReflectorFactory reflectorFactory;

  // nested resultmaps
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
  private final Map<String, Object> ancestorObjects = new HashMap<>();
  private Object previousRowValue;

  // multiple resultsets
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

  // Cached Automappings
  /**
   * 自动映射的缓存
   */
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  /**
   * 是否使用构造方法创建该结果对象
   */
  private boolean useConstructorMappings;

  private static class PendingRelation {
    public MetaObject metaObject;
    public ResultMapping propertyMapping;
  }

  private static class UnMappedColumnAutoMapping {
    private final String column;
    private final String property;
    private final TypeHandler<?> typeHandler;
    private final boolean primitive;

    public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
      this.column = column;
      this.property = property;
      this.typeHandler = typeHandler;
      this.primitive = primitive;
    }
  }

  public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                 RowBounds rowBounds) {
    this.executor = executor;
    this.configuration = mappedStatement.getConfiguration();
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;
    this.parameterHandler = parameterHandler;
    this.boundSql = boundSql;
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();
    this.reflectorFactory = configuration.getReflectorFactory();
    this.resultHandler = resultHandler;
  }

  //
  // HANDLE OUTPUT PARAMETER
  //

  @Override
  public void handleOutputParameters(CallableStatement cs) throws SQLException {
    final Object parameterObject = parameterHandler.getParameterObject();
    final MetaObject metaParam = configuration.newMetaObject(parameterObject);
    final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    for (int i = 0; i < parameterMappings.size(); i++) {
      final ParameterMapping parameterMapping = parameterMappings.get(i);
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        if (ResultSet.class.equals(parameterMapping.getJavaType())) {
          handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
        } else {
          final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
          metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
        }
      }
    }
  }

  private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
    if (rs == null) {
      return;
    }
    try {
      final String resultMapId = parameterMapping.getResultMapId();
      final ResultMap resultMap = configuration.getResultMap(resultMapId);
      final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
      if (this.resultHandler == null) {
        final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
        metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
      } else {
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rs);
    }
  }

  //
  // HANDLE RESULT SETS
  //
  @Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

    final List<Object> multipleResults = new ArrayList<>();

    int resultSetCount = 0;
    // 获得首个 ResultSet 对象，并封装成 ResultSetWrapper 对象
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    // 获得 ResultMap 数组
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    int resultMapCount = resultMaps.size();
    validateResultMapsCount(rsw, resultMapCount);
    while (rsw != null && resultMapCount > resultSetCount) {
      // 获得 ResultMap 对象
      ResultMap resultMap = resultMaps.get(resultSetCount);
      // 处理 ResultSet ，将结果添加到 multipleResults 中
      handleResultSet(rsw, resultMap, multipleResults, null);
      // 获得下一个 ResultSet 对象，并封装成 ResultSetWrapper 对象
      rsw = getNextResultSet(stmt);
      // 清除 nestedResultObjects
      cleanUpAfterHandlingResultSet();
      resultSetCount++;
    }

    String[] resultSets = mappedStatement.getResultSets();
    if (resultSets != null) {
      while (rsw != null && resultSetCount < resultSets.length) {
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        if (parentMapping != null) {
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          handleResultSet(rsw, resultMap, null, parentMapping);
        }
        rsw = getNextResultSet(stmt);
        cleanUpAfterHandlingResultSet();
        resultSetCount++;
      }
    }
    // 如果是 multipleResults 单元素，则取首元素返回
    return collapseSingleResultList(multipleResults);
  }

  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

    // 获得首个 ResultSet 对象，并封装成 ResultSetWrapper 对象
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    // 游标方式的查询，只允许一个 ResultSet 对象
    // 因此，resultMaps 数组的数量，元素只能有一个
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();

    int resultMapCount = resultMaps.size();
    validateResultMapsCount(rsw, resultMapCount);
    if (resultMapCount != 1) {
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }

    // 获得 ResultMap 对象，后创建 DefaultCursor 对象
    ResultMap resultMap = resultMaps.get(0);
    return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
  }

  private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
    ResultSet rs = stmt.getResultSet();
    while (rs == null) {
      // move forward to get the first resultset in case the driver
      // doesn't return the resultset as the first result (HSQLDB 2.1)
      if (stmt.getMoreResults()) {
        rs = stmt.getResultSet();
      } else {
        if (stmt.getUpdateCount() == -1) {
          // no more results. Must be no resultset
          break;
        }
      }
    }
    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
  }

  private ResultSetWrapper getNextResultSet(Statement stmt) {
    // Making this method tolerant of bad JDBC drivers
    try {
      if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
        // Crazy Standard JDBC way of determining if there are more results
        if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
          ResultSet rs = stmt.getResultSet();
          if (rs == null) {
            return getNextResultSet(stmt);
          } else {
            return new ResultSetWrapper(rs, configuration);
          }
        }
      }
    } catch (Exception e) {
      // Intentionally ignored.
    }
    return null;
  }

  private void closeResultSet(ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    }
  }

  private void cleanUpAfterHandlingResultSet() {
    nestedResultObjects.clear();
  }

  private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
    if (rsw != null && resultMapCount < 1) {
      throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
          + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
    }
  }

  private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
    try {
      if (parentMapping != null) {
        handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
      } else {
        if (resultHandler == null) {
          // 如果没有自定义的 resultHandler ，则创建默认的 DefaultResultHandler 对象
          DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
          // 处理 ResultSet 返回的每一行 Row
          handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
          // 添加 defaultResultHandler 的处理的结果，到 multipleResults 中
          // 自定义的 resultHandler 不需要加入 multipleResults 中
          // 它是作为参数传入的，自身可以获取处理后的结果
          multipleResults.add(defaultResultHandler.getResultList());
        } else {
          //  处理 ResultSet 返回的每一行 Row
          handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
        }
      }
    } finally {
      // issue #228 (close resultsets)
      // 关闭 ResultSet 对象
      closeResultSet(rsw.getResultSet());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  //
  // HANDLE ROWS FOR SIMPLE RESULTMAP
  //

  public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // 处理嵌套映射的情况
    if (resultMap.hasNestedResultMaps()) {
      // 校验不要使用 RowBounds
      ensureNoRowBounds();
      // 校验不要使用自定义的 resultHandler
      checkResultHandler();
      // 处理嵌套映射的结果
      handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    } else {
      // 处理简单映射的结果
      handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    }
  }

  private void ensureNoRowBounds() {
    if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
          + "Use safeRowBoundsEnabled=false setting to bypass this check.");
    }
  }

  protected void checkResultHandler() {
    if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
          + "Use safeResultHandlerEnabled=false setting to bypass this check "
          + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
    }
  }

  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
      throws SQLException {
    // 创建 DefaultResultContext 对象
    DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    // 获得 ResultSet 对象，并跳到 rowBounds 指定的开始位置
    ResultSet resultSet = rsw.getResultSet();
    skipRows(resultSet, rowBounds);
    // 循环处理每条记录
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      // 根据该行记录以及 ResultMap.discriminator ，决定映射使用的 ResultMap 对象
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      // 得到该行的值
      Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
      // 将映射创建的结果对象添加到 ResultHandler.resultList 中保存
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
    }
  }

  private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
    if (parentMapping != null) {
      linkToParents(rs, parentMapping, rowValue);
    } else {
      // 调用 ResultHandler ，进行结果的处理
      callResultHandler(resultHandler, resultContext, rowValue);
    }
  }

  @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
  private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
    // 设置结果对象到 resultContext 中
    resultContext.nextResultObject(rowValue);
    // 使用 ResultHandler 处理结果
    ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
  }

  private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
    return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
  }

  private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
    if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
      // 直接跳转到指定开始的位置
      if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        rs.absolute(rowBounds.getOffset());
      }
    } else {
      // 循环，跳到开始的位置
      for (int i = 0; i < rowBounds.getOffset(); i++) {
        if (!rs.next()) {
          break;
        }
      }
    }
  }

  //
  // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
  //

  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    // 创建 ResultLoaderMap 对象
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();
    // 创建映射后的结果对象
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
    // 结果不为空且不是基本类型
    if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      // 创建 MetaObject 对象，用于访问 rowValue 对象
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      boolean foundValues = this.useConstructorMappings;
      // 设置未明确映射的列值
      if (shouldApplyAutomaticMappings(resultMap, false)) {
        foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
      }
      // 设置明确映射的列值
      foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
      foundValues = lazyLoader.size() > 0 || foundValues;
      // 不是空对象或者 returnInstanceForEmptyRow 为true，返回创建的对象
      // 否则返回 false
      rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
    }
    return rowValue;
  }

  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    if (resultMap.getAutoMapping() != null) {
      return resultMap.getAutoMapping();
    } else {
      if (isNested) {
        return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
      } else {
        return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
      }
    }
  }

  //
  // PROPERTY MAPPINGS
  //

  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    // 得到所有映射的列
    final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    boolean foundValues = false;
    // 得到所有属性列集合
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
    for (ResultMapping propertyMapping : propertyMappings) {
      // 根据前缀得到列名
      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      // 内嵌 resultMap 的列不做处理
      if (propertyMapping.getNestedResultMapId() != null) {
        // the user added a column attribute to a nested result map, ignore it
        column = null;
      }
      // 如果是组合列、列存在于已匹配的列中或者有 resultSet 属性
      // 获取该列在结果集中的值
      // 跳过 java 属性为空或者需要延迟加载的记录
      // 给对应的属性设值
      if (propertyMapping.isCompositeResult()
          || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
          || propertyMapping.getResultSet() != null) {
        Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
        // issue #541 make property optional
        final String property = propertyMapping.getProperty();
        if (property == null) {
          continue;
        } else if (value == DEFERRED) {
          foundValues = true;
          continue;
        }
        if (value != null) {
          foundValues = true;
        }
        if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(property, value);
        }
      }
    }
    return foundValues;
  }

  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    // 有内嵌查询
    if (propertyMapping.getNestedQueryId() != null) {
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
    // 包含 resultSet 属性
    } else if (propertyMapping.getResultSet() != null) {
      addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
      return DEFERRED;
    // 其他情况直接获取对应的 TypeHandler，从结果集中获取属性值
    } else {
      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      return typeHandler.getResult(rs, column);
    }
  }

  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    // 生成 autoMappingsCache 的 KEY
    final String mapKey = resultMap.getId() + ":" + columnPrefix;
    // 从缓存 autoMappingsCache 中，获得 UnMappedColumnAutoMapping 数组
    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
    if (autoMapping == null) {
      autoMapping = new ArrayList<>();
      // 获取未匹配的列集合
      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
      // 遍历 unmappedColumnNames 数组
      for (String columnName : unmappedColumnNames) {
        String propertyName = columnName;
        // 如果有前缀
        // 判断当前属性是否包含前缀
        // 如果包含，propertyName 去掉前缀
        // 如果不包含，跳过
        if (columnPrefix != null && !columnPrefix.isEmpty()) {
          // When columnPrefix is specified,
          // ignore columns without the prefix.
          if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
            propertyName = columnName.substring(columnPrefix.length());
          } else {
            continue;
          }
        }
        // 结果对象的 metaObject 中，获得对应的属性名
        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
        // 属性存在，有 setter 方法
        if (property != null && metaObject.hasSetter(property)) {
          // 排除已映射的属性
          if (resultMap.getMappedProperties().contains(property)) {
            continue;
          }
          // 得到属性类型
          final Class<?> propertyType = metaObject.getSetterType(property);
          // 判断是否有对应的 TypeHandler 对象
          // 有就创建 UnMappedColumnAutoMapping，加入 autoMapping 集合
          // 没有就执行 AutoMappingUnknownColumnBehavior 对应逻辑
          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
            final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
            autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
          } else {
            configuration.getAutoMappingUnknownColumnBehavior()
                .doAction(mappedStatement, columnName, property, propertyType);
          }
        } else {
          configuration.getAutoMappingUnknownColumnBehavior()
              .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
        }
      }
      autoMappingsCache.put(mapKey, autoMapping);
    }
    return autoMapping;
  }

  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    // 获得 UnMappedColumnAutoMapping 数组
    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    boolean foundValues = false;
    // 非空遍历集合
    // 获取对应的结果集中的值
    // 设置 metaObject 的值
    if (!autoMapping.isEmpty()) {
      for (UnMappedColumnAutoMapping mapping : autoMapping) {
        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
        if (value != null) {
          foundValues = true;
        }
        if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(mapping.property, value);
        }
      }
    }
    return foundValues;
  }

  // MULTIPLE RESULT SETS

  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
    List<PendingRelation> parents = pendingRelations.get(parentKey);
    if (parents != null) {
      for (PendingRelation parent : parents) {
        if (parent != null && rowValue != null) {
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
    PendingRelation deferLoad = new PendingRelation();
    deferLoad.metaObject = metaResultObject;
    deferLoad.propertyMapping = parentMapping;
    List<PendingRelation> relations = pendingRelations.computeIfAbsent(cacheKey, k -> new ArrayList<>());
    // issue #255
    relations.add(deferLoad);
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    if (previous == null) {
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else {
      if (!previous.equals(parentMapping)) {
        throw new ExecutorException("Two different properties are mapped to the same resultSet");
      }
    }
  }

  private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
    CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMapping);
    if (columns != null && names != null) {
      String[] columnsArray = columns.split(",");
      String[] namesArray = names.split(",");
      for (int i = 0; i < columnsArray.length; i++) {
        Object value = rs.getString(columnsArray[i]);
        if (value != null) {
          cacheKey.update(namesArray[i]);
          cacheKey.update(value);
        }
      }
    }
    return cacheKey;
  }

  //
  // INSTANTIATION & CONSTRUCTOR MAPPING
  //

  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    // 表示是否使用构造方法创建该结果对象。此处将其重置
    this.useConstructorMappings = false; // reset previous mapping result
    //  记录使用的构造方法的参数类型的数组
    final List<Class<?>> constructorArgTypes = new ArrayList<>();
    // 记录使用的构造方法的参数值的数组
    final List<Object> constructorArgs = new ArrayList<>();
    /**
     * 创建结果对象
     * - TypeHandler 可以处理的基本类型
     * - 使用 `<constructor />` 节点 或 @ConstructorArgs 构造对象
     *   这其中 <arg>会包含内嵌查询和内嵌 resultMap 的情况
     * - 使用默认构造函数构造对象
     * - 没有默认构造函数，构造函数参数和数据库列匹配构造对象
     * 循环属性，判断是否有内嵌查询并且需要延迟加载的，创建对象代理
     */
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
    // 结果已经生成且不是 TypeHandler 可以处理的类型
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      // 如果有内嵌的查询，并且开启延迟加载，则创建结果对象的代理对象
      for (ResultMapping propertyMapping : propertyMappings) {
        // issue gcode #109 && issue #149
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
          resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
          break;
        }
      }
    }
    this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
    return resultObject;
  }

  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
      throws SQLException {
    // resultType
    final Class<?> resultType = resultMap.getType();
    final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
    // 构造参数对应的 ResultMapping
    final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
    // 如果有对应的 TypeHandler 对象，则意味着是基本类型，直接创建对结果应对象
    if (hasTypeHandlerForResultObject(rsw, resultType)) {
      return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
    // 如果 ResultMap 中，如果定义了 `<constructor />` 节点 或 @ConstructorArgs
    // 则通过反射调用该构造方法，创建对应结果对象
    } else if (!constructorMappings.isEmpty()) {
      return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
    // 如果有默认的无参的构造方法，则使用该构造方法，创建对应结果对象
    } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
      return objectFactory.create(resultType);
    // 通过自动映射的方式查找合适的构造方法，后使用该构造方法，创建对应结果对象
    // 需要查询结果和构造函数参数列相对应
    } else if (shouldApplyAutomaticMappings(resultMap, false)) {
      return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix);
    }
    // 其他抛出异常
    throw new ExecutorException("Do not know how to create an instance of " + resultType);
  }

  Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                         List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
    // 获得到任一的属性值。即，只要一个结果对象，有一个属性非空，就会设置为 true
    boolean foundValues = false;
    for (ResultMapping constructorMapping : constructorMappings) {
      // 获得参数类型
      final Class<?> parameterType = constructorMapping.getJavaType();
      // 获得数据库的字段名
      final String column = constructorMapping.getColumn();
      final Object value;
      try {
        // 如果是内嵌的查询，则获得内嵌的值
        if (constructorMapping.getNestedQueryId() != null) {
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
        // 如果是内嵌的 resultMap ，则递归 getRowValue 方法，获得对应的属性值
        } else if (constructorMapping.getNestedResultMapId() != null) {
          final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
          value = getRowValue(rsw, resultMap, constructorMapping.getColumnPrefix());
        // 最常用的情况，直接使用 TypeHandler 获取当前 ResultSet 的当前行的指定字段的值
        } else {
          final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
          value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
        }
      } catch (ResultMapException | SQLException e) {
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      }
      // 添加到 constructorArgTypes 和 constructorArgs 中
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      // 判断是否获得到属性值
      foundValues = value != null || foundValues;
    }
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                              String columnPrefix) throws SQLException {
    // 获取所有的构造函数
    final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
    // 查找被 @AutomapConstructor 注解的构造方法或者只有一个构造方法
    final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
    if (defaultConstructor != null) {
      return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, defaultConstructor);
    } else {
      for (Constructor<?> constructor : constructors) {
        if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
          return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, constructor);
        }
      }
    }
    throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
  }

  private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix, Constructor<?> constructor) throws SQLException {
    boolean foundValues = false;
    // 遍历构造函数参数类型
    for (int i = 0; i < constructor.getParameterTypes().length; i++) {
      Class<?> parameterType = constructor.getParameterTypes()[i];
      // 获得数据库的字段名
      String columnName = rsw.getColumnNames().get(i);
      // 获得 TypeHandler 对象
      TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
      // 获取当前列的值
      Object value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
      // 加入集合
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      foundValues = value != null || foundValues;
    }
    // 创建对象
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
    // 如果只有一个构造函数，返回
    if (constructors.length == 1) return constructors[0];

    // 查找被 @AutomapConstructor 注解的构造方法
    for (final Constructor<?> constructor : constructors) {
      if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
        return constructor;
      }
    }
    return null;
  }

  private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
    final Class<?>[] parameterTypes = constructor.getParameterTypes();
    if (parameterTypes.length != jdbcTypes.size()) return false;
    for (int i = 0; i < parameterTypes.length; i++) {
      if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
        return false;
      }
    }
    return true;
  }

  private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    final Class<?> resultType = resultMap.getType();
    final String columnName;
    if (!resultMap.getResultMappings().isEmpty()) {
      final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
      final ResultMapping mapping = resultMappingList.get(0);
      columnName = prependPrefix(mapping.getColumn(), columnPrefix);
    } else {
      columnName = rsw.getColumnNames().get(0);
    }
    // 获得 TypeHandler 对象
    final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
    return typeHandler.getResult(rsw.getResultSet(), columnName);
  }

  //
  // NESTED QUERY
  //

  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
    // 获取内嵌查询 id
    final String nestedQueryId = constructorMapping.getNestedQueryId();
    // 获取到对应的 MappedStatement
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    // 获取参数类型
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    // 获取参数值
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    // 如果参数值不为空，查询内嵌查询得到的结果返回
    if (nestedQueryParameterObject != null) {
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      final Class<?> targetType = constructorMapping.getJavaType();
      // 创建 ResultLoader 对象
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
      // 加载结果
      value = resultLoader.loadResult();
    }
    return value;
  }

  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    // 获取内嵌查询 id
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    // 获取对应的属性、MappedStatement
    final String property = propertyMapping.getProperty();
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    // 得到内嵌查询的参数值
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    // 如果内嵌查询参数不为 null
    // 判断是否有缓存
    // 如果有缓存，创建 DeferredLoad，加载对象
    // 没有缓存，创建 ResultLoader 对象，如果需要延迟加载加入 lazyLoader，否则直接查询得到数据
    if (nestedQueryParameterObject != null) {
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      final Class<?> targetType = propertyMapping.getJavaType();
      if (executor.isCached(nestedQuery, key)) {
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        value = DEFERRED;
      } else {
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
        if (propertyMapping.isLazy()) {
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          value = DEFERRED;
        } else {
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  /**
   * 获取内嵌查询的参数对象
   * @param rs
   * @param resultMapping
   * @param parameterType
   * @param columnPrefix
   * @return
   * @throws SQLException
   */
  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    // 组合
    if (resultMapping.isCompositeResult()) {
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    } else {
      // 普通
      return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
  }

  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final TypeHandler<?> typeHandler;
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final Object parameterObject = instantiateParameterObject(parameterType);
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    boolean foundValues = false;
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
      // issue #353 & #560 do not execute nested query if key is null
      if (propValue != null) {
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        foundValues = true;
      }
    }
    return foundValues ? parameterObject : null;
  }

  private Object instantiateParameterObject(Class<?> parameterType) {
    if (parameterType == null) {
      return new HashMap<>();
    } else if (ParamMap.class.equals(parameterType)) {
      return new HashMap<>(); // issue #649
    } else {
      return objectFactory.create(parameterType);
    }
  }

  //
  // DISCRIMINATOR
  //

  public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
    Set<String> pastDiscriminators = new HashSet<>();
    Discriminator discriminator = resultMap.getDiscriminator();
    // 如果存在 Discriminator 对象，则基于其获得 ResultMap 对象
    while (discriminator != null) {
      // 获得 Discriminator 的指定字段，在 ResultSet 中该字段的值
      final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
      // 从 Discriminator 获取该值对应的 ResultMap 的编号
      final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
      // 如果存在，则使用该 ResultMap 对象
      if (configuration.hasResultMap(discriminatedMapId)) {
        // 获得该 ResultMap 对象
        resultMap = configuration.getResultMap(discriminatedMapId);
        // 设置当前 discriminator 为 lastDiscriminator
        // 获取当前 discriminator 对应 resultMap 中的 discriminator
        Discriminator lastDiscriminator = discriminator;
        discriminator = resultMap.getDiscriminator();
        // 如果 discriminator 和 lastDiscriminator 相同
        // 或者 pastDiscriminators 已存在当前 discriminatedMapId
        // 退出循环
        if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
          break;
        }
      } else {
        break;
      }
    }
    return resultMap;
  }

  private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
    final ResultMapping resultMapping = discriminator.getResultMapping();
    final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private String prependPrefix(String columnName, String prefix) {
    if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
      return columnName;
    }
    return prefix + columnName;
  }

  //
  // HANDLE NESTED RESULT MAPS
  //

  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // // 创建 DefaultResultContext 对象
    final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    //  获得 ResultSet 对象，并跳到 rowBounds 指定的开始位置
    ResultSet resultSet = rsw.getResultSet();
    skipRows(resultSet, rowBounds);
    Object rowValue = previousRowValue;
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      // 为该行记录生成 CacheKey，作为缓存中的 key 值
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      // 根据缓存 key 先获取映射缓存
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      if (mappedStatement.isResultOrdered()) {
        // 如果从缓存中没有取到，并且 rowValue 有值
        // 说明当前记录和上一条记录不一致，清空缓存，保存上一条记录
        if (partialObject == null && rowValue != null) {
          nestedResultObjects.clear();
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
        // 获取当前行记录
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
        // 获取当前行记录
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        // 如果缓存中没有，保存当前记录
        if (partialObject == null) {
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
      }
    }
    if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
      previousRowValue = null;
    } else if (rowValue != null) {
      previousRowValue = rowValue;
    }
  }

  //
  // GET VALUE FROM ROW FOR NESTED RESULT MAP
  //

  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
    final String resultMapId = resultMap.getId();
    Object rowValue = partialObject;
    // 外层对象存在
    if (rowValue != null) {
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      // 将外层对象添加到 ancestorObjects
      putAncestor(rowValue, resultMapId);
      // 处理嵌套映射，其中会将生成的结果对象设置到外层对象的相应属性中
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      // 将外层对象从 ancestorObjects 移除
      ancestorObjects.remove(resultMapId);
    // 外层对象不存在
    } else {
      final ResultLoaderMap lazyLoader = new ResultLoaderMap();
      // 创建外层对象
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      // 结果集不为空，不是 TypeHandler 可以处理的（非简单类型）
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        boolean foundValues = this.useConstructorMappings;
        // 检测是否开启自动映射
        if (shouldApplyAutomaticMappings(resultMap, true)) {
          // 自动映射
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        // 处理 ResultMap 需要映射的列
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        putAncestor(rowValue, resultMapId);
        // 处理嵌套映射，将生成的结果对象设置到外层对象的相应的属性中
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
        // 将外层对象从 ancestorObjects 集合中移除
        ancestorObjects.remove(resultMapId);
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
        // 将外层对象添加到 nestedResultObjects
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    return rowValue;
  }

  private void putAncestor(Object resultObject, String resultMapId) {
    ancestorObjects.put(resultMapId, resultObject);
  }

  //
  // NESTED RESULT MAP (JOIN MAPPING)
  //

  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    boolean foundValues = false;
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
      // 获取内嵌 resultMapId
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
        try {
          // 获取列前缀
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          // 根据上面获取到的嵌套映射 id 去从配置中找到对应的 ResultMap
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            if (ancestorObject != null) {
              if (newObject) {
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              continue;
            }
          }
          // 为嵌套对象创建 CacheKey，该过程创建的 CacheKey 还会与外层对象的 CacheKey 合并
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          // 合并 CacheKey
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;
          // 如果嵌套对象是集合，并且没有初始化，会调用该方法对其进行初始化
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
          // 根据 notNullColumn 属性，检测结果集中相应的列是否为空
          // 如果不为空，获取内嵌对象
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            if (rowValue != null && !knownValue) {
              linkObjects(metaObject, resultMapping, rowValue);
              foundValues = true;
            }
          }
        } catch (SQLException e) {
          throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
        }
      }
    }
    return foundValues;
  }

  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
    Set<String> notNullColumns = resultMapping.getNotNullColumns();
    if (notNullColumns != null && !notNullColumns.isEmpty()) {
      ResultSet rs = rsw.getResultSet();
      for (String column : notNullColumns) {
        rs.getObject(prependPrefix(column, columnPrefix));
        if (!rs.wasNull()) {
          return true;
        }
      }
      return false;
    } else if (columnPrefix != null) {
      for (String columnName : rsw.getColumnNames()) {
        if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    final CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMap.getId());
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    if (resultMappings.isEmpty()) {
      if (Map.class.isAssignableFrom(resultMap.getType())) {
        createRowKeyForMap(rsw, cacheKey);
      } else {
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    if (cacheKey.getUpdateCount() < 2) {
      return CacheKey.NULL_CACHE_KEY;
    }
    return cacheKey;
  }

  private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
    if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
      CacheKey combinedKey;
      try {
        combinedKey = rowKey.clone();
      } catch (CloneNotSupportedException e) {
        throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
      }
      combinedKey.update(parentRowKey);
      return combinedKey;
    }
    return CacheKey.NULL_CACHE_KEY;
  }

  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    for (ResultMapping resultMapping : resultMappings) {
      if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
        // Issue #392
        final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
        createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
            prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
      } else if (resultMapping.getNestedQueryId() == null) {
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        // Issue #114
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          final Object value = th.getResult(rsw.getResultSet(), column);
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            cacheKey.update(column);
            cacheKey.update(value);
          }
        }
      }
    }
  }

  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
    final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
    for (String column : unmappedColumnNames) {
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
        if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          property = column.substring(columnPrefix.length());
        } else {
          continue;
        }
      }
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        String value = rsw.getResultSet().getString(column);
        if (value != null) {
          cacheKey.update(column);
          cacheKey.update(value);
        }
      }
    }
  }

  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    List<String> columnNames = rsw.getColumnNames();
    for (String columnName : columnNames) {
      final String value = rsw.getResultSet().getString(columnName);
      if (value != null) {
        cacheKey.update(columnName);
        cacheKey.update(value);
      }
    }
  }

  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    if (collectionProperty != null) {
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      targetMetaObject.add(rowValue);
    } else {
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    final String propertyName = resultMapping.getProperty();
    Object propertyValue = metaObject.getValue(propertyName);
    if (propertyValue == null) {
      Class<?> type = resultMapping.getJavaType();
      if (type == null) {
        type = metaObject.getSetterType(propertyName);
      }
      try {
        if (objectFactory.isCollection(type)) {
          propertyValue = objectFactory.create(type);
          metaObject.setValue(propertyName, propertyValue);
          return propertyValue;
        }
      } catch (Exception e) {
        throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
      }
    } else if (objectFactory.isCollection(propertyValue.getClass())) {
      return propertyValue;
    }
    return null;
  }

  private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
    if (rsw.getColumnNames().size() == 1) {
      return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
    }
    return typeHandlerRegistry.hasTypeHandler(resultType);
  }

}
