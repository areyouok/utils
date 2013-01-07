/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.namiao.jdbcutil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.support.JdbcDaoSupport;
import org.springframework.util.Assert;


/**
 * 所有继承这个类的DAO，将自动拥有CRUD的方法。 如：
 * 
 * <pre>
 * public class UserDAO extends DaoTemplate&lt;User&gt; {
 * }
 * </pre>
 * 
 * <p>Value对象的属性必须是下列类型才能够支持：int, long, String, BigDecimal, java.util.Date, 
 * float, double, boolean, enum。其中：
 * <ul>
 * <li>对于int等简单的类型，如果Value对象上的属性是其包装对象Integer，同样也支持，并且支持NULL。即如果
 *     从数据库读出来是null，value对象相应的值也是null；如果value对象的值是null，更新的时候会setNull到数据库。</li>
 * <li>boolean对象按ResultSet的getBoolean和PrepareStatement的setBoolean来读写。</li>
 * <li>如果是enum，会按ResultSet的getInt和PrepareStatement的setInt来读写，
 *    需要数据库对应的字段是数字型，第一个枚举值对应的数字是0。</li>
 * </ul>
 * </p>

 * 
 * @author <a href="mailto:main_shorttime@163.com">tengfei.fangtf</a>
 * @author <a href="mailto:areyouok@gmail.com">huangli</a>
 */
public abstract class DaoTemplate<T> extends JdbcDaoSupport {

    private static final Logger log = Logger.getLogger(DaoTemplate.class);

    private Class<T> entityClass;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 主键，可以为空，表示没有主键。
     */
    private Field PK;

    /**
     * java属性名到Field对象的Map，包含PK。
     */
    private Map<String, Field> javaNameToField;

    /**
     * 数据库列名到Field对象的Map，包含PK。
     */
    private Map<String, Field> columnNameToField;

    /**
     * Field对象列表，包含PK。
     */
    private List<Field> fields = new ArrayList<Field>();

    private String fullInsertSql;
    private String fullUpdateSql;
    protected String allFields;

    public DaoTemplate() {
        javaNameToField = new HashMap<String, Field>();
        columnNameToField = new HashMap<String, Field>();
        initByAnnotation();
        initSql();
    }

    @SuppressWarnings("unchecked")
    private void initByAnnotation() {
        entityClass = (Class<T>) getSuperClassGenricType();

        // 表名
        Table table = entityClass.getAnnotation(Table.class);
        tableName = (table == null) ? entityClass.getSimpleName().toUpperCase()
                : table.name().toUpperCase();

        // 字段
        Method methodArray[] = entityClass.getMethods();
        for (Method method : methodArray) {
            String methodName = method.getName();
            if (!methodName.startsWith("get") && !methodName.startsWith("is")) {
                continue;
            }
            if (method.getParameterTypes().length != 0) {
                continue;// getter方法不应该有参数
            }
            String propertyName = null;
            if (methodName.startsWith("is")) {
                propertyName = methodName.substring(2);
            } else {
                propertyName = methodName.substring(3);
            }
            if (propertyName.length() == 0) {
                continue;// 方法名就是is或者get或者set
            }
            if (!Character.isUpperCase(propertyName.charAt(0))) {
                continue;// getter方法书写不规范
            }

            String propertyNameWithFirstLetterUpper = propertyName;
            if (propertyName.length() == 1) {
                propertyName = propertyName.toLowerCase();
            } else {
                propertyName = Character.toLowerCase(propertyName.charAt(0))
                        + propertyName.substring(1);
            }

            if (method.isAnnotationPresent((Transient.class))) {
                continue;// 过滤掉Transient
            }

            Class<?> retClass = method.getReturnType();

            Method setter = null;
            try {
                setter = entityClass.getMethod("set"
                        + propertyNameWithFirstLetterUpper, retClass);
            } catch (SecurityException e) {
                continue;// 没有setter方法
            } catch (NoSuchMethodException e) {
                continue;// 没有setter方法
            }
            if (setter == null) {
                continue;// 没有setter方法
            }

            Field f = new Field();
            f.setGetter(method);
            f.setSetter(setter);
            f.setJavaName(propertyName);

            Column columnAnnotation = method.getAnnotation(Column.class);
            if (columnAnnotation == null) {
                f.setColumnName(propertyName.toUpperCase());
            } else {
                f.setColumnName(columnAnnotation.name().toUpperCase());
            }

            if (retClass.equals(String.class)) {
                f.setJavaType(FieldJavaType.STRING);
            } else if (retClass.equals(Long.class)
                    || retClass.equals(long.class)) {
                f.setJavaType(FieldJavaType.LONG);
            } else if (retClass.equals(Integer.class)
                    || retClass.equals(int.class)) {
                f.setJavaType(FieldJavaType.INT);
            } else if (retClass.equals(Date.class)) {
                f.setJavaType(FieldJavaType.DATE);
            } else if (retClass.equals(BigDecimal.class)) {
                f.setJavaType(FieldJavaType.BIGDECIMAL);
            } else if (retClass.equals(Boolean.class)
                    || retClass.equals(boolean.class)) {
                f.setJavaType(FieldJavaType.BOOLEAN);
            } else if (retClass.equals(Double.class)
                    || retClass.equals(double.class)) {
                f.setJavaType(FieldJavaType.DOUBLE);
            } else if (retClass.equals(Float.class)
                    || retClass.equals(float.class)) {
                f.setJavaType(FieldJavaType.FLOAT);
            } else if (retClass.isEnum()) {
                f.setJavaType(FieldJavaType.ENUM);
            } else {
                throw new RuntimeException("类" + getClass().getName() + "上的属性"
                        + propertyName + "的类型不被支持");
            }

            if (method.isAnnotationPresent(Id.class)) {
                PK = f;
                f.setPK(true);
                Field old = javaNameToField.put(propertyName, f);
                if (old != null) {
                    throw new RuntimeException("类" + getClass().getName()
                            + "上定义了多个主键");
                }
                columnNameToField.put(f.getColumnName(), f);
                fields.add(0, f);
            } else {
                f.setPK(false);
                Field old = javaNameToField.put(propertyName, f);
                if (old != null) {
                    // 既有isXXX，又有getXXX会出现这个问题
                    throw new RuntimeException("类" + getClass().getName()
                            + "上的属性" + propertyName + "的getter方法不正确");
                }
                columnNameToField.put(f.getColumnName(), f);
                fields.add(f);
            }
        }
    }

    /**
     * 获得T的Class。
     */
    private Class<?> getSuperClassGenricType() {
        Class<?> clazz = getClass();
        Type genType = clazz.getGenericSuperclass();
        if (!(genType instanceof ParameterizedType)) {
            log.warn(clazz.getSimpleName() + "没有通过泛型指定Value对象");
            throw new RuntimeException(clazz.getSimpleName()
                    + "没有通过泛型指定Value对象");
        }

        Type[] params = ((ParameterizedType) genType).getActualTypeArguments();

        if (params.length == 0 || !(params[0] instanceof Class<?>)) {
            throw new RuntimeException(clazz.getSimpleName()
                    + "没有通过泛型指定Value对象");
        }
        return (Class<?>) params[0];
    }

    private void initSql() {
        {// full insert sql
            StringBuffer sb = new StringBuffer();
            sb.append("INSERT INTO ").append(tableName).append("(");

            for (Field f : fields) {
                sb.append(f.getColumnName()).append(",");
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
                sb.deleteCharAt(sb.length() - 1);
            }
            sb.append(") VALUES(");

            for (int i = 0; i < fields.size(); i++) {
                sb.append("?,");
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
                sb.deleteCharAt(sb.length() - 1);
            }
            sb.append(")");
            fullInsertSql = sb.toString();
        }
        {
            StringBuffer sb = new StringBuffer();
            for (Field f : fields) {
                sb.append(f.getColumnName()).append(",");
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
                sb.deleteCharAt(sb.length() - 1);
            }
            allFields = sb.toString();
        }

        if (PK != null) {// full update sql
            StringBuffer sb = new StringBuffer();
            sb.append("UPDATE ").append(tableName).append(" SET ");
            for (Field f : fields) {
                if (!f.isPK()) {
                    sb.append(f.getColumnName()).append("=?,");
                }
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
                sb.deleteCharAt(sb.length() - 1);
            }
            sb.append(" WHERE ").append(PK.getColumnName()).append("=?");
            fullUpdateSql = sb.toString();
        }
    }

    private void setValue(PreparedStatement ps, int index, T obj, Field f)
            throws SQLException {
        Object param = null;
        try {
            param = f.getGetter().invoke(obj);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("获取属性" + f.getJavaName() + "的值时失败");
        } catch (IllegalAccessException e) {
            throw new RuntimeException("获取属性" + f.getJavaName() + "的值时失败");
        } catch (InvocationTargetException e) {
            throw new RuntimeException("获取属性" + f.getJavaName() + "的值时失败", e
                    .getTargetException());
        }
        switch (f.getJavaType()) {
        case BIGDECIMAL:
            if (param == null) {
                ps.setNull(index, Types.DECIMAL);
            } else {
                ps.setBigDecimal(index, (BigDecimal) param);
            }
            break;
        case BOOLEAN:
            if (param == null) {
                ps.setNull(index, Types.BOOLEAN);
            } else {
                ps.setBoolean(index, ((Boolean) param).booleanValue());
            }
            break;
        case DATE:
            if (param == null) {
                ps.setNull(index, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(index, new Timestamp(((Date) param).getTime()));
            }
            break;
        case DOUBLE:
            if (param == null) {
                ps.setNull(index, Types.DOUBLE);
            } else {
                ps.setDouble(index, ((Double) param).doubleValue());
            }
            break;
        case FLOAT:
            if (param == null) {
                ps.setNull(index, Types.FLOAT);
            } else {
                ps.setFloat(index, ((Float) param).floatValue());
            }
            break;
        case INT:
            if (param == null) {
                ps.setNull(index, Types.INTEGER);
            } else {
                ps.setInt(index, ((Integer) param).intValue());
            }
            break;
        case LONG:
            if (param == null) {
                ps.setNull(index, Types.BIGINT);
            } else {
                ps.setLong(index, ((Long) param).longValue());
            }
            break;
        case STRING:
            ps.setString(index, (String) param);
            break;
        case ENUM:
            ps.setInt(index, ((Enum<?>) param).ordinal());
            break;
        }
    }

    private void getValue(ResultSet rs, int index, Object obj, Field f)
            throws SQLException {
        Object value = null;
        switch (f.getJavaType()) {
        case BIGDECIMAL:
            value = rs.getBigDecimal(index);
            break;
        case BOOLEAN:
            value = rs.getBoolean(index);
            break;
        case DATE:
            value = rs.getTimestamp(index);
            break;
        case DOUBLE:
            value = rs.getDouble(index);
            break;
        case FLOAT:
            value = rs.getFloat(index);
            break;
        case INT:
            value = rs.getInt(index);
            break;
        case LONG:
            value = rs.getLong(index);
            break;
        case STRING:
            value = rs.getString(index);
            break;
        case ENUM:
            int enumIntValue = rs.getInt(index);
            value = f.getGetter().getReturnType().getEnumConstants()[enumIntValue];
            break;
        }
        if (rs.wasNull()) {
            value = null;
            if (f.getGetter().getReturnType().isPrimitive()) {// 如果是简单类型并且值为空，就不调用setter方法
                return;
            }
        }
        try {
            f.getSetter().invoke(obj, value);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("设置属性" + f.getJavaName() + "的值时失败");
        } catch (IllegalAccessException e) {
            throw new RuntimeException("设置属性" + f.getJavaName() + "的值时失败");
        } catch (InvocationTargetException e) {
            throw new RuntimeException("设置属性" + f.getJavaName() + "的值时失败", e
                    .getTargetException());
        }
    }

    private void getValue(ResultSet rs, int index, BaseDataObject obj,
            String colName) throws SQLException {
        Object value = rs.getObject(index);
        obj.getProps().put(colName, value);
    }

    /**
     * 保存对象
     * 
     * @param object
     */
    public void add(final T object) {
        Assert.notNull(object, "对象不能为空");
        getJdbcTemplate().update(fullInsertSql, new PreparedStatementSetter() {
            public void setValues(PreparedStatement ps) throws SQLException {
                int index = 1;
                for (Field f : fields) {
                    setValue(ps, index++, object, f);
                }
            }
        });
    }

    /**
     * 批量保存对象，内部是批次更新，调用它的代码需要保证事务。
     * 
     * @param object
     */
    public void add(final T[] objects) {
        Assert.notNull(objects, "对象不能为空");
        List<Object[]> batchs = extractBatchs(objects);
        for (final Object[] batch : batchs) {
            getJdbcTemplate().batchUpdate(fullInsertSql,
                    new BatchPreparedStatementSetter() {
                        public int getBatchSize() {
                            return batch.length;
                        }

                        @SuppressWarnings("unchecked")
                        public void setValues(PreparedStatement ps, int i)
                                throws SQLException {
                            T t = (T) batch[i];
                            int index = 1;
                            for (Field f : fields) {
                                setValue(ps, index++, t, f);
                            }
                        }
                    });
        }
    }

    /**
     * 更新对象
     * 
     * @param object
     */
    public boolean update(final T object) {
        Assert.notNull(object, "对象不能为空");
        if (PK == null) {
            throw new UnsupportedOperationException("没有主键");
        }
        int x = getJdbcTemplate().update(fullUpdateSql,
                new PreparedStatementSetter() {
                    public void setValues(PreparedStatement ps)
                            throws SQLException {
                        int index = 1;
                        for (Field f : fields) {
                            if (f.isPK()) {
                                continue;
                            }
                            setValue(ps, index++, object, f);
                        }
                        setValue(ps, index++, object, PK);
                    }
                });
        return x == 1;
    }

    /**
     * 批量更新对象，内部可能是多次批操作，调用它的代码要保证事务。
     * 
     * @param object
     */
    public int update(final T[] objects) {
        Assert.notNull(objects, "对象不能为空");
        if (PK == null) {
            throw new UnsupportedOperationException("没有主键");
        }
        List<Object[]> batchs = extractBatchs(objects);
        int count = 0;
        for (final Object[] batch : batchs) {
            int[] x = getJdbcTemplate().batchUpdate(fullUpdateSql,
                    new BatchPreparedStatementSetter() {
                        public int getBatchSize() {
                            return batch.length;
                        }

                        @SuppressWarnings("unchecked")
                        public void setValues(PreparedStatement ps, int i)
                                throws SQLException {
                            T t = (T) batch[i];
                            int index = 1;
                            for (Field f : fields) {
                                if (f.isPK()) {
                                    continue;
                                }
                                setValue(ps, index++, t, f);
                            }
                            setValue(ps, index++, t, PK);
                        }
                    });
            for (int i = 0; i < x.length; i++) {
                count += x[i];
            }
        }
        return count;
    }

    /**
     * 按照指定条件和参数进行查询操作
     * 
     * @param conditionSql
     *            条件sql语句 如 name=? && password=?
     * @param params
     *            参数 如 new Object[]{"f","f"}
     * @return 存放对象的列表
     */
    @SuppressWarnings("unchecked")
    protected List<T> queryByCondition(String conditionSql, Object[] params) {
        return getJdbcTemplate().query(
                "SELECT " + allFields + " FROM " + tableName + " WHERE "
                        + conditionSql, params, fullRowMapper);
    }

    /**
     * 使用sql语句进行查询。当Value对象继承了<code>EntityValue</code>时，会把无法匹配的
     * 字段存入<code>EntityValue</code>的props中。
     * 
     * @param sql
     *            sql语句
     * @param params
     *            参数 如 new Object[]{"f","f"}
     * @return 存放对象的列表
     */
    @SuppressWarnings("unchecked")
    protected List<T> queryBySql(String sql, Object[] params) {
        return getJdbcTemplate().query(sql, params, rowMapper);
    }

    /**
     * 按照指定条件和参数进行删除操作
     * 
     * @param conditionSql
     *            条件sql语句 如 name=? && password=?
     * @param params
     *            参数 如 new Object[]{"f","f"}
     * @return 删除的记录数
     */
    protected int deleteByCondition(String conditionSql, Object[] params) {
        return getJdbcTemplate().update(
                "DELETE FROM " + tableName + " WHERE  " + conditionSql, params);
    }

    /**
     * 查询指定ID的对象，如果查找不到返回null。
     * 
     * @param id
     * @return
     */
    public T queryById(Object id) {
        Assert.notNull(id, "ID不能为空");
        if (PK == null) {
            throw new UnsupportedOperationException("没有指定主键");
        }
        final List<T> object = queryByProperty(PK.getColumnName(), id);
        return (object.size() == 0) ? null : (T) object.get(0);
    }

    /**
     * 根据属性名和属性值查询对象.
     * 
     * @param propertyName
     *            数据库的字段名
     * @param value
     *            查询的值
     * @return
     */
    @SuppressWarnings("unchecked")
    protected List<T> queryByProperty(String propertyName, Object value) {
        return getJdbcTemplate().query(
                "SELECT " + allFields + " FROM " + tableName + " WHERE "
                        + propertyName + " = ?", new Object[] { value },
                fullRowMapper);
    }

    /**
     * 得到所有的对象
     */
    @SuppressWarnings("unchecked")
    public List<T> queryAll() {
        return getJdbcTemplate().query(
                "SELECT " + allFields + " FROM " + tableName, fullRowMapper);
    }

    /**
     * 删除指定ID的对象
     * 
     * @param id
     * @return 是否删除成功
     */
    public boolean deleteById(Object id) {
        Assert.notNull(id, "ID不能为空");
        if (PK == null) {
            throw new UnsupportedOperationException("没有指定主键");
        }
        int x = getJdbcTemplate().update(
                "DELETE FROM " + tableName + " WHERE " + PK.getColumnName()
                        + "=?", new Object[] { id });
        return x == 1;
    }

    /**
     * 批量删除，这个方法内部是分批进行的，调用它的代码要保证事务。
     * 
     * @param ids
     *            id数组 类型可以是Long或者String
     */
    public int deleteById(Object[] ids) {
        Assert.notNull(ids, "ID不能为空");
        if (PK == null) {
            throw new UnsupportedOperationException("没有指定主键");
        }
        String sql = "DELETE FROM " + tableName + " WHERE "
                + PK.getColumnName() + "=?";
        final List<Object[]> idList = extractBatchs(ids);
        int count = 0;
        for (final Object[] batchIds : idList) {
            int[] x = getJdbcTemplate().batchUpdate(sql,
                    new BatchPreparedStatementSetter() {
                        public int getBatchSize() {
                            return batchIds.length;
                        }

                        public void setValues(PreparedStatement ps, int i)
                                throws SQLException {
                            Object id = batchIds[i];
                            if (id == null) {
                                throw new IllegalArgumentException("ID不能为null");
                            }
                            if (id instanceof String) {
                                ps.setString(1, (String) batchIds[i]);
                            } else if (id instanceof Long) {
                                ps.setLong(1, ((Long) id).longValue());
                            }
                        }
                    });
            for (int i = 0; i < x.length; i++) {
                count += x[i];
            }
        }
        return count;
    }

    /**
     * 根据属性名和属性值删除对象。
     * 
     * @param propertyName
     *            数据库字段名
     * @param value
     *            值
     * @return 删除的记录数
     */
    protected int deleteByProperty(String propertyName, Object value) {
        Assert.notNull(propertyName, "propertyName不能为空");
        return getJdbcTemplate().update(
                "DELETE FROM " + tableName + " WHERE " + propertyName + "=?",
                new Object[] { value });
    }

    private List<Object[]> extractBatchs(Object[] values) {
        List<Object[]> list = new ArrayList<Object[]>();
        if (values == null || values.length == 0) {
            return list;
        }
        int batch = 40;
        int index = 0;
        while (true) {
            Object[] s = null;
            if (index + batch < values.length) {
                s = new Object[batch];
            } else {
                s = new Object[values.length - index];
            }
            System.arraycopy(values, index, s, 0, s.length);
            list.add(s);
            index += batch;
            if (index > values.length) {
                break;
            }
        }
        return list;
    }

    /**
     * 这个变量（内部类）假定查询出来的字段顺序和<code>fields</code>指定的顺序完全一样，
     * 这时使用这个RowMapper，可以不用读取ResultSetMetaData。
     * 这个变量子类不应该用，因此定义为私有。
     */
    private RowMapper fullRowMapper = new RowMapper() {
        public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
            T newEntity = null;
            try {
                newEntity = entityClass.newInstance();
            } catch (InstantiationException e1) {
                throw new RuntimeException("字段映射时出现异常", e1);
            } catch (IllegalAccessException e1) {
                throw new RuntimeException("字段映射时出现异常", e1);
            }
            for (int i = 0; i < fields.size(); i++) {
                getValue(rs, i + 1, newEntity, fields.get(i));
            }
            return newEntity;
        }

    };

    /**
     * 字段映射，子类直接使用，假设选取了这个表的部分字段，选取了那些字段从rs中获取。
     * 使用这个RowMapper，当Value对象继承了<code>EntityValue</code>时，会把无法匹配的
     * 字段存入<code>EntityValue</code>的props中。
     */
    protected RowMapper rowMapper = new RowMapper() {

        public Object mapRow(ResultSet rs, int Index) throws SQLException {
            Object[] types;
            ResultSetMetaData rsmd = rs.getMetaData();
            types = new Object[rsmd.getColumnCount()];
            for (int i = 1; i <= types.length; i++) {
                String colName = rsmd.getColumnLabel(i).toUpperCase();
                Field f = columnNameToField.get(colName);
                if (f != null) {
                    types[i - 1] = f;
                } else {
                    types[i - 1] = colName;
                }
            }
            
            
            T newEntity = null;
            try {
                newEntity = entityClass.newInstance();
            } catch (InstantiationException e1) {
                throw new RuntimeException("字段映射时出现异常", e1);
            } catch (IllegalAccessException e1) {
                throw new RuntimeException("字段映射时出现异常", e1);
            }
            for (int i = 1; i <= types.length; i++) {
                Object type = types[i - 1];
                if (type instanceof String) {
                    if (newEntity instanceof BaseDataObject) {
                        getValue(rs, i, (BaseDataObject) newEntity, (String) type);
                    } else {
                        // 忽略
                    }
                } else {
                    getValue(rs, i, newEntity, (Field) type);
                }
            }
            return newEntity;
        }
    };
    
    /**
     * 用来把一行数据转换为Map对象并且字段统一转为大写字母
     * 
     */
    protected RowMapper mapRowMapper = new RowMapper(){
    	/**
    	 * 遍历ResultSet，将数据压制到Map<String, Object>中，并返回
    	 * 应用场景：适用于自己拼写sql的情况 注意:有别名时的情况
    	 */
    	public Object mapRow(ResultSet rs, int Index) throws SQLException {
    		Map<String, Object> vo = new HashMap<String, Object>();
    		//1-get rsmetadata
    		ResultSetMetaData meta = rs.getMetaData();
    		// 2-get map<String,Object>
    		for (int i = 1, len = meta.getColumnCount(); i <= len; i++) {
    			vo.put(meta.getColumnName(i).toUpperCase(), rs.getObject(i));
    		}
    		return vo;
    	}
    };

}

/**
 * 用来描述一个解析后的字段的信息，包括java属性名、列名、类型、getter、setter。
 * 
 * @author huangli
 */
class Field {
    private String javaName;
    private String columnName;
    private Method getter;
    private Method Setter;
    private FieldJavaType javaType;
    private boolean PK;

    public String getJavaName() {
        return javaName;
    }

    public void setJavaName(String fieldName) {
        this.javaName = fieldName;
    }

    public Method getGetter() {
        return getter;
    }

    public void setGetter(Method getter) {
        this.getter = getter;
    }

    public Method getSetter() {
        return Setter;
    }

    public void setSetter(Method setter) {
        Setter = setter;
    }

    public FieldJavaType getJavaType() {
        return javaType;
    }

    public void setJavaType(FieldJavaType javaType) {
        this.javaType = javaType;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String dbName) {
        this.columnName = dbName;
    }

    public boolean isPK() {
        return PK;
    }

    public void setPK(boolean pk) {
        PK = pk;
    }
}

enum FieldJavaType {
    INT, LONG, STRING, BIGDECIMAL, DATE, FLOAT, DOUBLE, BOOLEAN, ENUM;
}
