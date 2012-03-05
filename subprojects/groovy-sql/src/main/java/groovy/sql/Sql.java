/*
 * Copyright 2003-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package groovy.sql;

import groovy.lang.Closure;
import groovy.lang.GString;

import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import groovy.lang.Tuple;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.SqlGroovyMethods;

/**
 * A facade over Java's normal JDBC APIs providing greatly simplified
 * resource management and result set handling. Under the covers the
 * facade hides away details associated with getting connections,
 * constructing and configuring statements, interacting with the
 * connection, closing resources and logging errors. Special
 * features of the facade include using closures to iterate
 * through result sets, a special GString syntax for representing
 * prepared statements and treating result sets like collections
 * of maps with the normal Groovy collection methods available.
 *
 * <h4>Typical usage</h4>
 *
 * First you need to set up your sql instance. There are several constructors
 * and a few <code>newInstance</code> factory methods available to do this.
 * In simple cases, you can just provide
 * the necessary details to set up a connection (e.g. for hsqldb):
 * <pre>
 * def db = [url:'jdbc:hsqldb:mem:testDB', user:'sa', password:'', driver:'org.hsqldb.jdbc.JDBCDriver']
 * def sql = Sql.newInstance(db.url, db.user, db.password, db.driver)
 * </pre>
 * or if you have an existing connection (perhaps from a connection pool) or a
 * datasource use one of the constructors:
 * <pre>
 * def sql = new Sql(datasource)
 * </pre>
 * Now you can invoke sql, e.g. to create a table:
 * <pre>
 * sql.execute '''
 *     create table PROJECT (
 *         id integer not null,
 *         name varchar(50),
 *         url varchar(100),
 *     )
 * '''
 * </pre>
 * Or insert a row using JDBC PreparedStatement inspired syntax:
 * <pre>
 * def params = [10, 'Groovy', 'http://groovy.codehaus.org']
 * sql.execute 'insert into PROJECT (id, name, url) values (?, ?, ?)', params
 * </pre>
 * Or insert a row using GString syntax:
 * <pre>
 * def map = [id:20, name:'Grails', url:'http://grails.codehaus.org']
 * sql.execute "insert into PROJECT (id, name, url) values ($map.id, $map.name, $map.url)"
 * </pre>
 * Or a row update:
 * <pre>
 * def newUrl = 'http://grails.org'
 * def project = 'Grails'
 * sql.executeUpdate "update PROJECT set url=$newUrl where name=$project"
 * </pre>
 * Now try a query using <code>eachRow</code>:
 * <pre>
 * println 'Some GR8 projects:'
 * sql.eachRow('select * from PROJECT') { row ->
 *     println "${row.name.padRight(10)} ($row.url)"
 * }
 * </pre>
 * Which will produce something like this:
 * <pre>
 * Some GR8 projects:
 * Groovy     (http://groovy.codehaus.org)
 * Grails     (http://grails.org)
 * Griffon    (http://griffon.codehaus.org)
 * Gradle     (http://gradle.org)
 * </pre>
 * Now try a query using <code>rows</code>:
 * <pre>
 * def rows = sql.rows("select * from PROJECT where name like 'Gra%'")
 * assert rows.size() == 2
 * println rows.join('\n')
 * </pre>
 * with output like this:
 * <pre>
 * [ID:20, NAME:Grails, URL:http://grails.org]
 * [ID:40, NAME:Gradle, URL:http://gradle.org]
 * </pre>
 * Also, <code>eachRow</code> and <code>rows</code> support paging.  Here's an example: 
 * <pre>
 * sql.eachRow('select * from PROJECT', 2, 2) { row ->
 *     println "${row.name.padRight(10)} ($row.url)"
 * }
 * </pre>
 * Which will start at the second row and return a maximum of 2 rows.  Here's an example result:
 * <pre>
 * Grails     (http://grails.org)
 * Griffon    (http://griffon.codehaus.org)
 * </pre>
 * 
 * Finally, we should clean up:
 * <pre>
 * sql.close()
 * </pre>
 * If we are using a DataSource and we haven't enabled statement caching, then
 * strictly speaking the final <code>close()</code> method isn't required - as all connection
 * handling is performed transparently on our behalf; however, it doesn't hurt to
 * have it there as it will return silently in that case.
 * <p/>
 *
 * <h4>Named and named ordinal parameters</h4>
 *
 * Several of the methods in this class which have a String-based sql query and
 * params in a List<Object> or Object[] support <em>named</em> or <em>named ordinal</em> parameters.
 * These methods are useful for queries with large numbers of parameters - though the GString
 * variations are often preferred in such cases too.
 * <p/>
 * Named parameter queries use placeholder values in the query String. Two forms are supported
 * ':propname1' and '?.propname2'. For these variations, a single <em>model</em> object is
 * supplied in the parameter list. The propname refers to a property of that model object.
 * The model object could be a map, Expando or domain class instance. Here are some examples:
 * <pre>
 * println sql.rows('select * from PROJECT where name=:foo', [foo:'Gradle'])
 * println sql.rows('select * from PROJECT where name=:foo and id=?.bar', [foo:'Gradle', bar:40])
 * class MyDomainClass { def baz = 'Griffon' }
 * println sql.rows('select * from PROJECT where name=?.baz', new MyDomainClass())
 * </pre>
 * Named ordinal parameter queries have multiple model objects with the index number (starting
 * at 1) also supplied in the placeholder. Only the question mark variation of placeholder is supported.
 * Here is an example:
 * <pre>
 * println sql.rows("select * from PROJECT where name=?1.baz and id=?2.num", new MyDomainClass(), [num:30])
 * </pre>
 *
 * <h4>More details</h4>
 *
 * See the method and constructor JavaDoc for more details.
 * <p/>
 * For advanced usage, the class provides numerous extension points for overriding the
 * facade behavior associated with the various aspects of managing
 * the interaction with the underlying database.
 *
 * @author Chris Stevenson
 * @author <a href="mailto:james@coredevelopers.net">James Strachan</a>
 * @author Paul King
 * @author Marc DeXeT
 * @author John Bito
 * @author John Hurst
 * @author David Durham
 * @author Daniel Henrique Alves Lima
 */
public class Sql {

    /**
     * Hook to allow derived classes to access the log
     */
    protected static final Logger LOG = Logger.getLogger(Sql.class.getName());

    private static final List<Object> EMPTY_LIST = Collections.emptyList();

    private static final Pattern NAMED_QUERY_PATTERN = Pattern.compile("(?::|\\?(\\d?)\\.?)(\\w*)");

    private DataSource dataSource;

    private Connection useConnection;

    private int resultSetType = ResultSet.TYPE_FORWARD_ONLY;
    private int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;
    private int resultSetHoldability = -1;

    // store last row update count for executeUpdate, executeInsert and execute
    private int updateCount = 0;

    // allows a closure to be used to configure Statement objects before its use
    private Closure configureStatement;

    private boolean cacheConnection;

    private boolean cacheStatements;

    private boolean cacheNamedQueries = true;

    private boolean enableNamedQueries = true;

    private boolean withinBatch;

    private final Map<String, Statement> statementCache = new HashMap<String, Statement>();
    private final Map<String, String> namedParamSqlCache = new HashMap<String, String>();
    private final Map<String, List<Tuple>> namedParamIndexPropCache = new HashMap<String, List<Tuple>>();

    /**
     * Creates a new Sql instance given a JDBC connection URL.
     *
     * @param url a database url of the form
     *            <code> jdbc:<em>subprotocol</em>:<em>subname</em></code>
     * @return a new Sql instance with a connection
     * @throws SQLException if a database access error occurs
     */
    public static Sql newInstance(String url) throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        return new Sql(connection);
    }

    /**
     * Creates a new Sql instance given a JDBC connection URL
     * and some properties.
     *
     * @param url        a database url of the form
     *                   <code> jdbc:<em>subprotocol</em>:<em>subname</em></code>
     * @param properties a list of arbitrary string tag/value pairs
     *                   as connection arguments; normally at least a "user" and
     *                   "password" property should be included
     * @return a new Sql instance with a connection
     * @throws SQLException if a database access error occurs
     */
    public static Sql newInstance(String url, Properties properties) throws SQLException {
        Connection connection = DriverManager.getConnection(url, properties);
        return new Sql(connection);
    }

    /**
     * Creates a new Sql instance given a JDBC connection URL,
     * some properties and a driver class name.
     *
     * @param url             a database url of the form
     *                        <code>jdbc:<em>subprotocol</em>:<em>subname</em></code>
     * @param properties      a list of arbitrary string tag/value pairs
     *                        as connection arguments; normally at least a "user" and
     *                        "password" property should be included
     * @param driverClassName the fully qualified class name of the driver class
     * @return a new Sql instance with a connection
     * @throws SQLException           if a database access error occurs
     * @throws ClassNotFoundException if the class cannot be found or loaded
     */
    public static Sql newInstance(String url, Properties properties, String driverClassName)
            throws SQLException, ClassNotFoundException {
        loadDriver(driverClassName);
        return newInstance(url, properties);
    }

    /**
     * Creates a new Sql instance given a JDBC connection URL,
     * a username and a password.
     *
     * @param url      a database url of the form
     *                 <code>jdbc:<em>subprotocol</em>:<em>subname</em></code>
     * @param user     the database user on whose behalf the connection
     *                 is being made
     * @param password the user's password
     * @return a new Sql instance with a connection
     * @throws SQLException if a database access error occurs
     */
    public static Sql newInstance(String url, String user, String password) throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        return new Sql(connection);
    }

    /**
     * Creates a new Sql instance given a JDBC connection URL,
     * a username, a password and a driver class name.
     *
     * @param url             a database url of the form
     *                        <code>jdbc:<em>subprotocol</em>:<em>subname</em></code>
     * @param user            the database user on whose behalf the connection
     *                        is being made
     * @param password        the user's password
     * @param driverClassName the fully qualified class name of the driver class
     * @return a new Sql instance with a connection
     * @throws SQLException           if a database access error occurs
     * @throws ClassNotFoundException if the class cannot be found or loaded
     */
    public static Sql newInstance(String url, String user, String password, String driverClassName) throws SQLException,
            ClassNotFoundException {
        loadDriver(driverClassName);
        return newInstance(url, user, password);
    }

    /**
     * Creates a new Sql instance given a JDBC connection URL
     * and a driver class name.
     *
     * @param url             a database url of the form
     *                        <code>jdbc:<em>subprotocol</em>:<em>subname</em></code>
     * @param driverClassName the fully qualified class name of the driver class
     * @return a new Sql instance with a connection
     * @throws SQLException           if a database access error occurs
     * @throws ClassNotFoundException if the class cannot be found or loaded
     */
    public static Sql newInstance(String url, String driverClassName) throws SQLException, ClassNotFoundException {
        loadDriver(driverClassName);
        return newInstance(url);
    }

    /**
     * Creates a new Sql instance given parameters in a Map.
     * Recognized keys for the Map include:
     * <pre>
     * driverClassName the fully qualified class name of the driver class
     * driver          a synonym for driverClassName
     * url             a database url of the form: jdbc:<em>subprotocol</em>:<em>subname</em>
     * user            the database user on whose behalf the connection is being made
     * password        the user's password
     * properties      a list of arbitrary string tag/value pairs as connection arguments;
     *                 normally at least a "user" and "password" property should be included
     * <em>other</em>           any of the public setter methods of this class may be used with property notation
     *                 e.g. <em>cacheStatements: true, resultSetConcurrency: ResultSet.CONCUR_READ_ONLY</em>
     * </pre>
     * Of these, '<code>url</code>' is required. Others may be needed depending on your database.<br>
     * If '<code>properties</code>' is supplied, neither '<code>user</code>' nor '<code>password</code>' should be supplied.<br>
     * If one of '<code>user</code>' or '<code>password</code>' is supplied, both should be supplied.
     *<p/>
     * Example usage:
     * <pre>
     * import groovy.sql.Sql
     * import static java.sql.ResultSet.*
     *
     * def sql = Sql.newInstance(
     *     url:'jdbc:hsqldb:mem:testDB',
     *     user:'sa',
     *     password:'',
     *     driver:'org.hsqldb.jdbc.JDBCDriver',
     *     cacheStatements: true,
     *     resultSetConcurrency: CONCUR_READ_ONLY
     * )
     * </pre>
     * 
     * @param args a Map contain further arguments
     * @return a new Sql instance with a connection
     * @throws SQLException           if a database access error occurs
     * @throws ClassNotFoundException if the class cannot be found or loaded
     */
    public static Sql newInstance(Map<String, Object> args) throws SQLException, ClassNotFoundException {
        if (!args.containsKey("url"))
            throw new IllegalArgumentException("Argument 'url' is required");

        if (args.get("url") == null)
            throw new IllegalArgumentException("Argument 'url' must not be null");

        if (args.containsKey("driverClassName") && args.containsKey("driver"))
            throw new IllegalArgumentException("Only one of 'driverClassName' and 'driver' should be provided");

        // Make a copy so destructive operations will not affect the caller
        Map<String, Object> sqlArgs = new HashMap<String, Object>(args);

        Object driverClassName = sqlArgs.remove("driverClassName");
        if (driverClassName == null) driverClassName = sqlArgs.remove("driver");
        if (driverClassName != null) loadDriver(driverClassName.toString());

        Properties props = (Properties) sqlArgs.remove("properties");
        if (props != null && sqlArgs.containsKey("user"))
            throw new IllegalArgumentException("Only one of 'properties' and 'user' should be supplied");
        if (props != null && sqlArgs.containsKey("password"))
            throw new IllegalArgumentException("Only one of 'properties' and 'password' should be supplied");
        if (sqlArgs.containsKey("user") ^ sqlArgs.containsKey("password"))
            throw new IllegalArgumentException("Found one but not both of 'user' and 'password'");

        Object url = sqlArgs.remove("url");
        Connection connection;
        if (props != null) {
            System.err.println("url = " + url);
            System.err.println("props = " + props);
            connection = DriverManager.getConnection(url.toString(), new Properties(props));
        } else if (sqlArgs.containsKey("user")) {
            Object user = sqlArgs.remove("user");
            Object password = sqlArgs.remove("password");
            connection = DriverManager.getConnection(url.toString(),
                    (user == null ? null : user.toString()),
                    (password == null ? null : password.toString()));
        } else {
            connection = DriverManager.getConnection(url.toString());
        }

        Sql result = (Sql) InvokerHelper.invokeConstructorOf(Sql.class, sqlArgs);
        result.setConnection(connection);
        return result;
    }

    /**
     * Gets the resultSetType for statements created using the connection.
     *
     * @return the current resultSetType value
     * @since 1.5.2
     */
    public int getResultSetType() {
        return resultSetType;
    }

    /**
     * Sets the resultSetType for statements created using the connection.
     * May cause SQLFeatureNotSupportedException exceptions to occur if the
     * underlying database doesn't support the requested type value.
     *
     * @param resultSetType one of the following <code>ResultSet</code>
     *                      constants:
     *                      <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     *                      <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     *                      <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @since 1.5.2
     */
    public void setResultSetType(int resultSetType) {
        this.resultSetType = resultSetType;
    }

    /**
     * Gets the resultSetConcurrency for statements created using the connection.
     *
     * @return the current resultSetConcurrency value
     * @since 1.5.2
     */
    public int getResultSetConcurrency() {
        return resultSetConcurrency;
    }

    /**
     * Sets the resultSetConcurrency for statements created using the connection.
     * May cause SQLFeatureNotSupportedException exceptions to occur if the
     * underlying database doesn't support the requested concurrency value.
     *
     * @param resultSetConcurrency one of the following <code>ResultSet</code>
     *                             constants:
     *                             <code>ResultSet.CONCUR_READ_ONLY</code> or
     *                             <code>ResultSet.CONCUR_UPDATABLE</code>
     * @since 1.5.2
     */
    public void setResultSetConcurrency(int resultSetConcurrency) {
        this.resultSetConcurrency = resultSetConcurrency;
    }

    /**
     * Gets the resultSetHoldability for statements created using the connection.
     *
     * @return the current resultSetHoldability value or -1 if not set
     * @since 1.5.2
     */
    public int getResultSetHoldability() {
        return resultSetHoldability;
    }

    /**
     * Sets the resultSetHoldability for statements created using the connection.
     * May cause SQLFeatureNotSupportedException exceptions to occur if the
     * underlying database doesn't support the requested holdability value.
     *
     * @param resultSetHoldability one of the following <code>ResultSet</code>
     *                             constants:
     *                             <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *                             <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @since 1.5.2
     */
    public void setResultSetHoldability(int resultSetHoldability) {
        this.resultSetHoldability = resultSetHoldability;
    }

    /**
     * Attempts to load the JDBC driver on the thread, current or system class
     * loaders
     *
     * @param driverClassName the fully qualified class name of the driver class
     * @throws ClassNotFoundException if the class cannot be found or loaded
     */
    public static void loadDriver(String driverClassName) throws ClassNotFoundException {
        // let's try the thread context class loader first
        // let's try to use the system class loader
        try {
            Class.forName(driverClassName);
        }
        catch (ClassNotFoundException e) {
            try {
                Thread.currentThread().getContextClassLoader().loadClass(driverClassName);
            }
            catch (ClassNotFoundException e2) {
                // now let's try the classloader which loaded us
                try {
                    Sql.class.getClassLoader().loadClass(driverClassName);
                }
                catch (ClassNotFoundException e3) {
                    throw e;
                }
            }
        }
    }

    public static final OutParameter ARRAY         = new OutParameter(){ public int getType() { return Types.ARRAY; }};
    public static final OutParameter BIGINT        = new OutParameter(){ public int getType() { return Types.BIGINT; }};
    public static final OutParameter BINARY        = new OutParameter(){ public int getType() { return Types.BINARY; }};
    public static final OutParameter BIT           = new OutParameter(){ public int getType() { return Types.BIT; }};
    public static final OutParameter BLOB          = new OutParameter(){ public int getType() { return Types.BLOB; }};
    public static final OutParameter BOOLEAN       = new OutParameter(){ public int getType() { return Types.BOOLEAN; }};
    public static final OutParameter CHAR          = new OutParameter(){ public int getType() { return Types.CHAR; }};
    public static final OutParameter CLOB          = new OutParameter(){ public int getType() { return Types.CLOB; }};
    public static final OutParameter DATALINK      = new OutParameter(){ public int getType() { return Types.DATALINK; }};
    public static final OutParameter DATE          = new OutParameter(){ public int getType() { return Types.DATE; }};
    public static final OutParameter DECIMAL       = new OutParameter(){ public int getType() { return Types.DECIMAL; }};
    public static final OutParameter DISTINCT      = new OutParameter(){ public int getType() { return Types.DISTINCT; }};
    public static final OutParameter DOUBLE        = new OutParameter(){ public int getType() { return Types.DOUBLE; }};
    public static final OutParameter FLOAT         = new OutParameter(){ public int getType() { return Types.FLOAT; }};
    public static final OutParameter INTEGER       = new OutParameter(){ public int getType() { return Types.INTEGER; }};
    public static final OutParameter JAVA_OBJECT   = new OutParameter(){ public int getType() { return Types.JAVA_OBJECT; }};
    public static final OutParameter LONGVARBINARY = new OutParameter(){ public int getType() { return Types.LONGVARBINARY; }};
    public static final OutParameter LONGVARCHAR   = new OutParameter(){ public int getType() { return Types.LONGVARCHAR; }};
    public static final OutParameter NULL          = new OutParameter(){ public int getType() { return Types.NULL; }};
    public static final OutParameter NUMERIC       = new OutParameter(){ public int getType() { return Types.NUMERIC; }};
    public static final OutParameter OTHER         = new OutParameter(){ public int getType() { return Types.OTHER; }};
    public static final OutParameter REAL          = new OutParameter(){ public int getType() { return Types.REAL; }};
    public static final OutParameter REF           = new OutParameter(){ public int getType() { return Types.REF; }};
    public static final OutParameter SMALLINT      = new OutParameter(){ public int getType() { return Types.SMALLINT; }};
    public static final OutParameter STRUCT        = new OutParameter(){ public int getType() { return Types.STRUCT; }};
    public static final OutParameter TIME          = new OutParameter(){ public int getType() { return Types.TIME; }};
    public static final OutParameter TIMESTAMP     = new OutParameter(){ public int getType() { return Types.TIMESTAMP; }};
    public static final OutParameter TINYINT       = new OutParameter(){ public int getType() { return Types.TINYINT; }};
    public static final OutParameter VARBINARY     = new OutParameter(){ public int getType() { return Types.VARBINARY; }};
    public static final OutParameter VARCHAR       = new OutParameter(){ public int getType() { return Types.VARCHAR; }};

    public static InParameter ARRAY(Object value) { return in(Types.ARRAY, value); }
    public static InParameter BIGINT(Object value) { return in(Types.BIGINT, value); }
    public static InParameter BINARY(Object value) { return in(Types.BINARY, value); }
    public static InParameter BIT(Object value) { return in(Types.BIT, value); }
    public static InParameter BLOB(Object value) { return in(Types.BLOB, value); }
    public static InParameter BOOLEAN(Object value) { return in(Types.BOOLEAN, value); }
    public static InParameter CHAR(Object value) { return in(Types.CHAR, value); }
    public static InParameter CLOB(Object value) { return in(Types.CLOB, value); }
    public static InParameter DATALINK(Object value) { return in(Types.DATALINK, value); }
    public static InParameter DATE(Object value) { return in(Types.DATE, value); }
    public static InParameter DECIMAL(Object value) { return in(Types.DECIMAL, value); }
    public static InParameter DISTINCT(Object value) { return in(Types.DISTINCT, value); }
    public static InParameter DOUBLE(Object value) { return in(Types.DOUBLE, value); }
    public static InParameter FLOAT(Object value) { return in(Types.FLOAT, value); }
    public static InParameter INTEGER(Object value) { return in(Types.INTEGER, value); }
    public static InParameter JAVA_OBJECT(Object value) { return in(Types.JAVA_OBJECT, value); }
    public static InParameter LONGVARBINARY(Object value) { return in(Types.LONGVARBINARY, value); }
    public static InParameter LONGVARCHAR(Object value) { return in(Types.LONGVARCHAR, value); }
    public static InParameter NULL(Object value) { return in(Types.NULL, value); }
    public static InParameter NUMERIC(Object value) { return in(Types.NUMERIC, value); }
    public static InParameter OTHER(Object value) { return in(Types.OTHER, value); }
    public static InParameter REAL(Object value) { return in(Types.REAL, value); }
    public static InParameter REF(Object value) { return in(Types.REF, value); }
    public static InParameter SMALLINT(Object value) { return in(Types.SMALLINT, value); }
    public static InParameter STRUCT(Object value) { return in(Types.STRUCT, value); }
    public static InParameter TIME(Object value) { return in(Types.TIME, value); }
    public static InParameter TIMESTAMP(Object value) { return in(Types.TIMESTAMP, value); }
    public static InParameter TINYINT(Object value) { return in(Types.TINYINT, value); }
    public static InParameter VARBINARY(Object value) { return in(Types.VARBINARY, value); }
    public static InParameter VARCHAR(Object value) { return in(Types.VARCHAR, value); }

    /**
     * Create a new InParameter
     *
     * @param type  the JDBC data type
     * @param value the object value
     * @return an InParameter
     */
    public static InParameter in(final int type, final Object value) {
        return new InParameter() {
            public int getType() {
                return type;
            }

            public Object getValue() {
                return value;
            }
        };
    }

    /**
     * Create a new OutParameter
     *
     * @param type the JDBC data type.
     * @return an OutParameter
     */
    public static OutParameter out(final int type) {
        return new OutParameter() {
            public int getType() {
                return type;
            }
        };
    }

    /**
     * Create an inout parameter using this in parameter.
     *
     * @param in the InParameter of interest
     * @return the resulting InOutParameter
     */
    public static InOutParameter inout(final InParameter in) {
        return new InOutParameter() {
            public int getType() {
                return in.getType();
            }

            public Object getValue() {
                return in.getValue();
            }
        };
    }

    /**
     * Create a new ResultSetOutParameter
     *
     * @param type the JDBC data type.
     * @return a ResultSetOutParameter
     */
    public static ResultSetOutParameter resultSet(final int type) {
        return new ResultSetOutParameter() {
            public int getType() {
                return type;
            }
        };
    }

    /**
     * When using GString SQL queries, allows a variable to be expanded
     * in the Sql string rather than representing an sql parameter.
     * <p/>
     * Example usage:
     * <pre>
     * def fieldName = 'firstname'
     * def fieldOp = Sql.expand('like')
     * def fieldVal = '%a%'
     * sql.query "select * from PERSON where ${Sql.expand(fieldName)} $fieldOp ${fieldVal}", { ResultSet rs ->
     *     while (rs.next()) println rs.getString('firstname')
     * }
     * // query will be 'select * from PERSON where firstname like ?'
     * // params will be [fieldVal]
     * </pre>
     *
     * @param object the object of interest
     * @return the expanded variable
     * @see #expand(Object)
     */
    public static ExpandedVariable expand(final Object object) {
        return new ExpandedVariable() {
            public Object getObject() {
                return object;
            }
        };
    }

    /**
     * Constructs an SQL instance using the given DataSource. Each operation
     * will use a Connection from the DataSource pool and close it when the
     * operation is completed putting it back into the pool.
     *
     * @param dataSource the DataSource to use
     */
    public Sql(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Constructs an SQL instance using the given Connection. It is the caller's
     * responsibility to close the Connection after the Sql instance has been
     * used. Depending on which features you are using, you may be able to do
     * this on the connection object directly but the preferred approach is to
     * call the {@link #close()} method which will close the connection but also
     * free any caches resources.
     *
     * @param connection the Connection to use
     */
    public Sql(Connection connection) {
        if (connection == null) {
            throw new NullPointerException("Must specify a non-null Connection");
        }
        this.useConnection = connection;
    }

    public Sql(Sql parent) {
        this.dataSource = parent.dataSource;
        this.useConnection = parent.useConnection;
    }

    private Sql() {
        // supports Map style newInstance method
    }

    public DataSet dataSet(String table) {
        return new DataSet(this, table);
    }

    public DataSet dataSet(Class<?> type) {
        return new DataSet(this, type);
    }

    /**
     * Performs the given SQL query, which should return a single
     * <code>ResultSet</code> object. The given closure is called
     * with the <code>ResultSet</code> as its argument.
     * <p/>
     * Example usages:
     * <pre>
     * sql.query("select * from PERSON where firstname like 'S%'") { ResultSet rs ->
     *     while (rs.next()) println rs.getString('firstname') + ' ' + rs.getString(3)
     * }
     *
     * sql.query("call get_people_places()") { ResultSet rs ->
     *     while (rs.next()) println rs.toRowResult().firstname
     * }
     * </pre>
     *
     * All resources including the ResultSet are closed automatically
     * after the closure is called.
     *
     * @param sql     the sql statement
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void query(String sql, Closure closure) throws SQLException {
        Connection connection = createConnection();
        Statement statement = getStatement(connection, sql);
        ResultSet results = null;
        try {
            results = statement.executeQuery(sql);
            closure.call(results);
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement, results);
        }
    }

    /**
     * Performs the given SQL query, which should return a single
     * <code>ResultSet</code> object. The given closure is called
     * with the <code>ResultSet</code> as its argument.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Example usage:
     * <pre>
     * sql.query('select * from PERSON where lastname like ?', ['%a%']) { ResultSet rs ->
     *     while (rs.next()) println rs.getString('lastname')
     * }
     * </pre>
     *
     * This method supports named and named ordinal parameters.
     * See the class Javadoc for more details.
     * <p/>
     * All resources including the ResultSet are closed automatically
     * after the closure is called.
     *
     * @param sql     the sql statement
     * @param params  a list of parameters
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void query(String sql, List<Object> params, Closure closure) throws SQLException {
        Connection connection = createConnection();
        PreparedStatement statement = null;
        ResultSet results = null;
        try {
            statement = getPreparedStatement(connection, sql, params);
            results = statement.executeQuery();
            closure.call(results);
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement, results);
        }
    }

    /**
     * Performs the given SQL query, which should return a single
     * <code>ResultSet</code> object. The given closure is called
     * with the <code>ResultSet</code> as its argument.
     * The query may contain GString expressions.
     * <p/>
     * Example usage:
     * <pre>
     * def location = 25
     * sql.query "select * from PERSON where location_id < $location", { ResultSet rs ->
     *     while (rs.next()) println rs.getString('firstname')
     * }
     * </pre>
     *
     * All resources including the ResultSet are closed automatically
     * after the closure is called.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     */
    public void query(GString gstring, Closure closure) throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        query(sql, params, closure);
    }

    /**
     * Performs the given SQL query calling the given Closure with each row of the result set.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * <p/>
     * Example usages:
     * <pre>
     * sql.eachRow("select * from PERSON where firstname like 'S%'") { row ->
     *    println "$row.firstname ${row[2]}}"
     * }
     *
     * sql.eachRow "call my_stored_proc_returning_resultset()", {
     *     println it.firstname
     * }
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql     the sql statement
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(String sql, Closure closure) throws SQLException {
        eachRow(sql, (Closure) null, closure);
    }
    
    /**
     * Performs the given SQL query calling the given <code>closure</code> with each row of the result set starting at 
     * the provided <code>offset</code>, and including up to <code>maxRows</code> number of rows.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * 
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * 
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql     the sql statement
     * @param offset  the 1-based offset for the first row to be processed
     * @param maxRows the maximum number of rows to be processed
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(String sql, int offset, int maxRows, Closure closure) throws SQLException {
        eachRow(sql, (Closure) null, offset, maxRows, closure);
    }

    /**
     * Performs the given SQL query calling the given <code>rowClosure</code> with each row of the
     * result set.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * <p/>
     * Example usage:
     * <pre>
     * def printColNames = { meta ->
     *     (1..meta.columnCount).each {
     *         print meta.getColumnLabel(it).padRight(20)
     *     }
     *     println()
     * }
     * def printRow = { row ->
     *     row.toRowResult().values().each{ print it.toString().padRight(20) }
     *     println()
     * }
     * sql.eachRow("select * from PERSON", printColNames, printRow)
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql         the sql statement
     * @param metaClosure called for meta data (only once after sql execution)
     * @param rowClosure  called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(String sql, Closure metaClosure, Closure rowClosure) throws SQLException {
        eachRow(sql, metaClosure, 0, 0, rowClosure);
    }

    /**
     * Performs the given SQL query calling the given <code>rowClosure</code> with each row of the result set starting at 
     * the provided <code>offset</code>, and including up to <code>maxRows</code> number of rows.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * <p/>
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * 
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * 
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql         the sql statement
     * @param offset      the 1-based offset for the first row to be processed
     * @param maxRows     the maximum number of rows to be processed
     * @param metaClosure called for meta data (only once after sql execution)
     * @param rowClosure  called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(String sql, Closure metaClosure, int offset, int maxRows, Closure rowClosure) throws SQLException {
        Connection connection = createConnection();
        Statement statement = getStatement(connection, sql);
        ResultSet results = null;
        try {
            results = statement.executeQuery(sql);

            if (metaClosure != null) metaClosure.call(results.getMetaData());
            
            boolean cursorAtRow = moveCursor(results, offset);
            if (!cursorAtRow) return;

            GroovyResultSet groovyRS = new GroovyResultSetProxy(results).getImpl();
            int i = 0;
            while (groovyRS.next() && (maxRows <= 0 || i++ < maxRows)) {
                rowClosure.call(groovyRS);
            }
        } catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        } finally {
            closeResources(connection, statement, results);
        }
    }
    
    private boolean moveCursor(ResultSet results, int offset) throws SQLException {
        boolean cursorAtRow = true;
        if (results.getType() == ResultSet.TYPE_FORWARD_ONLY) {
            int i = 1;
            while (i++ < offset && cursorAtRow) {
                cursorAtRow = results.next();
            }
        } else if (offset > 1) {
            cursorAtRow = results.absolute(offset - 1);
        }
        return cursorAtRow;
    }
    
    /**
     * Performs the given SQL query calling the given <code>rowClosure</code> with each row of the result set starting at 
     * the provided <code>offset</code>, and including up to <code>maxRows</code> number of rows.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * <p/>
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * 
     * @param sql         the sql statement
     * @param params      a list of parameters
     * @param offset      the 1-based offset for the first row to be processed
     * @param maxRows     the maximum number of rows to be processed
     * @param metaClosure called for meta data (only once after sql execution)
     * @param rowClosure  called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(String sql, List<Object> params, Closure metaClosure, int offset, int maxRows, Closure rowClosure) throws SQLException {
        Connection connection = createConnection();
        PreparedStatement statement = null;
        ResultSet results = null;
        try {
            statement = getPreparedStatement(connection, sql, params);
            results = statement.executeQuery();

            if (metaClosure != null) metaClosure.call(results.getMetaData());

            boolean cursorAtRow = moveCursor(results, offset);
            if (!cursorAtRow) return;            
            
            GroovyResultSet groovyRS = new GroovyResultSetProxy(results).getImpl();
            int i = 0;
            while (groovyRS.next() && (maxRows <= 0 || i++ < maxRows)) {
                rowClosure.call(groovyRS);
            }
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement, results);
        }
    }    
    

    /**
     * Performs the given SQL query calling the given Closure with each row of the result set.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Example usage:
     * <pre>
     * def printColNames = { meta ->
     *     (1..meta.columnCount).each {
     *         print meta.getColumnLabel(it).padRight(20)
     *     }
     *     println()
     * }
     * def printRow = { row ->
     *     row.toRowResult().values().each{ print it.toString().padRight(20) }
     *     println()
     * }
     * sql.eachRow("select * from PERSON where lastname like ?", ['%a%'], printColNames, printRow)
     * </pre>
     *
     * This method supports named and named ordinal parameters.
     * See the class Javadoc for more details.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql         the sql statement
     * @param params      a list of parameters
     * @param metaClosure called for meta data (only once after sql execution)
     * @param rowClosure  called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(String sql, List<Object> params, Closure metaClosure, Closure rowClosure) throws SQLException {
        eachRow(sql, params, metaClosure, 0, 0, rowClosure);
    }

    /**
     * Performs the given SQL query calling the given Closure with each row of the result set.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Example usage:
     * <pre>
     * sql.eachRow("select * from PERSON where lastname like ?", ['%a%']) { row ->
     *     println "${row[1]} $row.lastname"
     * }
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql     the sql statement
     * @param params  a list of parameters
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(String sql, List<Object> params, Closure closure) throws SQLException {
        eachRow(sql, params, null, closure);
    }

    /**
     * Performs the given SQL query calling the given <code>closure</code> with each row of the result set starting at 
     * the provided <code>offset</code>, and including up to <code>maxRows</code> number of rows.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * 
     * @param sql     the sql statement
     * @param params  a list of parameters
     * @param offset  the 1-based offset for the first row to be processed
     * @param maxRows the maximum number of rows to be processed
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(String sql, List<Object> params, int offset, int maxRows, Closure closure) throws SQLException {
        eachRow(sql, params, null, offset, maxRows, closure);
    }

    /**
     * Performs the given SQL query calling the given Closure with each row of the result set.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * <p/>
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * The query may contain GString expressions.
     * <p/>
     * Example usage:
     * <pre>
     * def location = 25
     * def printColNames = { meta ->
     *     (1..meta.columnCount).each {
     *         print meta.getColumnLabel(it).padRight(20)
     *     }
     *     println()
     * }
     * def printRow = { row ->
     *     row.toRowResult().values().each{ print it.toString().padRight(20) }
     *     println()
     * }
     * sql.eachRow("select * from PERSON where location_id < $location", printColNames, printRow)
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @param metaClosure called for meta data (only once after sql execution)
     * @param rowClosure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     */
    public void eachRow(GString gstring, Closure metaClosure, Closure rowClosure) throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        eachRow(sql, params, metaClosure, rowClosure);
    }

    /**
     * Performs the given SQL query calling the given <code>closure</code> with each row of the result set starting at 
     * the provided <code>offset</code>, and including up to <code>maxRows</code> number of rows.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * The query may contain GString expressions.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * 
     * @param gstring a GString containing the SQL query with embedded params
     * @param metaClosure called for meta data (only once after sql execution)
     * @param offset  the 1-based offset for the first row to be processed
     * @param maxRows the maximum number of rows to be processed
     * @param rowClosure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(GString gstring, Closure metaClosure, int offset, int maxRows, Closure rowClosure) throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        eachRow(sql, params, metaClosure, offset, maxRows, rowClosure);
    }

    /**
     * Performs the given SQL query calling the given <code>closure</code> with each row of the result set starting at 
     * the provided <code>offset</code>, and including up to <code>maxRows</code> number of rows.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * The query may contain GString expressions.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * 
     * @param gstring a GString containing the SQL query with embedded params
     * @param offset  the 1-based offset for the first row to be processed
     * @param maxRows the maximum number of rows to be processed
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void eachRow(GString gstring, int offset, int maxRows, Closure closure) throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        eachRow(sql, params, offset, maxRows, closure);
    }
    
    /**
     * Performs the given SQL query calling the given Closure with each row of the result set.
     * The row will be a <code>GroovyResultSet</code> which is a <code>ResultSet</code>
     * that supports accessing the fields using property style notation and ordinal index values.
     * The query may contain GString expressions.
     * <p/>
     * Example usage:
     * <pre>
     * def location = 25
     * sql.eachRow("select * from PERSON where location_id < $location") { row ->
     *     println row.firstname
     * }
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     */
    public void eachRow(GString gstring, Closure closure) throws SQLException {
        eachRow(gstring, null, closure);
    }

    /**
     * Performs the given SQL query and return the rows of the result set.
     * <p/>
     * Example usage:
     * <pre>
     * def ans = sql.rows("select * from PERSON where firstname like 'S%'")
     * println "Found ${ans.size()} rows"
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql the SQL statement
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql) throws SQLException {
        return rows(sql, 0, 0, null);
    }
    
    /**
     * Performs the given SQL query and return a "page" of rows from the result set.  A page is defined as starting at
     * a 1-based offset, and containing a maximum number of rows.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql the SQL statement
     * @param offset      the 1-based offset for the first row to be processed
     * @param maxRows     the maximum number of rows to be processed
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql, int offset, int maxRows) throws SQLException {
        return rows(sql, offset, maxRows, null);
    }
    

    /**
     * Performs the given SQL query and return the rows of the result set.
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * <p/>
     * Example usage:
     * <pre>
     * def printNumCols = { meta -> println "Found $meta.columnCount columns" }
     * def ans = sql.rows("select * from PERSON", printNumCols)
     * println "Found ${ans.size()} rows"
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql         the SQL statement
     * @param metaClosure called with meta data of the ResultSet
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql, Closure metaClosure) throws SQLException {
        return rows(sql, 0, 0, metaClosure);
    }
    
    /**
     * Performs the given SQL query and return a "page" of rows from the result set.  A page is defined as starting at
     * a 1-based offset, and containing a maximum number of rows.
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql the SQL statement
     * @param offset      the 1-based offset for the first row to be processed
     * @param maxRows     the maximum number of rows to be processed
     * @param metaClosure called for meta data (only once after sql execution)
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql, int offset, int maxRows, Closure metaClosure) throws SQLException {
        AbstractQueryCommand command = createQueryCommand(sql);
        ResultSet rs = null;
        try {
            rs = command.execute();
            List<GroovyRowResult> result = asList(sql, rs, offset, maxRows, metaClosure);
            rs = null;
            return result;
        } finally {
            command.closeResources(rs);
        }
    }

    /**
     * Performs the given SQL query and return the rows of the result set.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Example usage:
     * <pre>
     * def ans = sql.rows("select * from PERSON where lastname like ?", ['%a%'])
     * println "Found ${ans.size()} rows"
     * </pre>
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql    the SQL statement
     * @param params a list of parameters
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql, List<Object> params)
            throws SQLException {
        return rows(sql, params, null);
    }
    
    /**
     * Performs the given SQL query and return a "page" of rows from the result set.  A page is defined as starting at
     * a 1-based offset, and containing a maximum number of rows.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql the SQL statement
     * @param params an array of parameters
     * @param offset      the 1-based offset for the first row to be processed
     * @param maxRows     the maximum number of rows to be processed
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql, List<Object> params, int offset, int maxRows) throws SQLException {
        return rows(sql, params, offset, maxRows, null);
    }

    /**
     * Performs the given SQL query and return the rows of the result set.
     *
     * An Object array variant of {@link #rows(String, List)}.
     *
     * @param sql    the SQL statement
     * @param params an array of parameters
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql, Object[] params)
            throws SQLException {
        return rows(sql, params, 0, 0);
    }
    
    /**
     * Performs the given SQL query and return the rows of the result set.
     *
     * An Object array variant of {@link #rows(String, List, int, int)}.
     *
     * @param sql    the SQL statement
     * @param params an array of parameters
     * @param offset      the 1-based offset for the first row to be processed
     * @param maxRows     the maximum number of rows to be processed
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql, Object[] params, int offset, int maxRows) throws SQLException {
        return rows(sql, Arrays.asList(params), offset, maxRows, null);
    }

    /**
     * Performs the given SQL query and return the rows of the result set.
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Example usage:
     * <pre>
     * def printNumCols = { meta -> println "Found $meta.columnCount columns" }
     * def ans = sql.rows("select * from PERSON where lastname like ?", ['%a%'], printNumCols)
     * println "Found ${ans.size()} rows"
     * </pre>
     *
     * This method supports named and named ordinal parameters by supplying such
     * parameters in the <code>params</code> list. Here is an example:
     * <pre>
     * def printNumCols = { meta -> println "Found $meta.columnCount columns" }
     *
     * def mapParam = [foo: 'Smith']
     * def domainParam = new MyDomainClass(bar: 'John')
     * def qry = 'select * from PERSON where lastname=?1.foo and firstname=?2.bar'
     * def ans = sql.rows(qry, [mapParam, domainParam], printNumCols)
     * println "Found ${ans.size()} rows"
     *
     * def qry2 = 'select * from PERSON where firstname=:first and lastname=:last'
     * def ans2 = sql.rows(qry2, [[last:'Smith', first:'John']], printNumCols)
     * println "Found ${ans2.size()} rows"
     * </pre>
     * See the class Javadoc for more details.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql         the SQL statement
     * @param params      a list of parameters
     * @param metaClosure called for meta data (only once after sql execution)
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql, List<Object> params, Closure metaClosure)
            throws SQLException {
        return rows(sql, params, 0, 0, metaClosure);
    }
        
    /**
     * Performs the given SQL query and return a "page" of rows from the result set.  A page is defined as starting at
     * a 1-based offset, and containing a maximum number of rows.
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql the SQL statement
     * @param params      a list of parameters
     * @param offset      the 1-based offset for the first row to be processed
     * @param maxRows     the maximum number of rows to be processed
     * @param metaClosure called for meta data (only once after sql execution)
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(String sql, List<Object> params, int offset, int maxRows, Closure metaClosure)
        throws SQLException {
        
        AbstractQueryCommand command = createPreparedQueryCommand(sql, params);
        try {
            return asList(sql, command.execute(), offset, maxRows, metaClosure);
        }
        finally {
            command.closeResources();
        }
    }

    /**
     * Performs the given SQL query and return a "page" of rows from the result set.  A page is defined as starting at
     * a 1-based offset, and containing a maximum number of rows.
     * The query may contain GString expressions.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql     the SQL statement
     * @param offset  the 1-based offset for the first row to be processed
     * @param maxRows the maximum number of rows to be processed
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(GString sql, int offset, int maxRows) throws SQLException {
        return rows(sql, offset, maxRows, null);
    }

    /**
     * Performs the given SQL query and return the rows of the result set.
     * The query may contain GString expressions.
     * <p/>
     * Example usage:
     * <pre>
     * def location = 25
     * def ans = sql.rows("select * from PERSON where location_id < $location")
     * println "Found ${ans.size()} rows"
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     */
    public List<GroovyRowResult> rows(GString gstring) throws SQLException {
        return rows(gstring, null);
    }

    /**
     * Performs the given SQL query and return the rows of the result set.
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * The query may contain GString expressions.
     * <p/>
     * Example usage:
     * <pre>
     * def location = 25
     * def printNumCols = { meta -> println "Found $meta.columnCount columns" }
     * def ans = sql.rows("select * from PERSON where location_id < $location", printNumCols)
     * println "Found ${ans.size()} rows"
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @param metaClosure called with meta data of the ResultSet
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     */
    public List<GroovyRowResult> rows(GString gstring, Closure metaClosure)
            throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        return rows(sql, params, metaClosure);
    }

    /**
     * Performs the given SQL query and return a "page" of rows from the result set.  A page is defined as starting at
     * a 1-based offset, and containing a maximum number of rows.
     * In addition, the <code>metaClosure</code> will be called once passing in the
     * <code>ResultSetMetaData</code> as argument.
     * The query may contain GString expressions.
     * <p/>
     * Note that the underlying implementation is based on either invoking <code>ResultSet.absolute()</code>, 
     * or if the ResultSet type is <code>ResultSet.TYPE_FORWARD_ONLY</code>, the <code>ResultSet.next()</code> method
     * is invoked equivalently.  The first row of a ResultSet is 1, so passing in an offset of 1 or less has no effect 
     * on the initial positioning within the result set.
     * <p/>
     * Note that different database and JDBC driver implementations may work differently with respect to this method.  
     * Specifically, one should expect that <code>ResultSet.TYPE_FORWARD_ONLY</code> may be less efficient than a 
     * "scrollable" type.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring the SQL statement
     * @param offset  the 1-based offset for the first row to be processed
     * @param maxRows the maximum number of rows to be processed
     * @param metaClosure called for meta data (only once after sql execution)
     * @return a list of GroovyRowResult objects
     * @throws SQLException if a database access error occurs
     */
    public List<GroovyRowResult> rows(GString gstring, int offset, int maxRows, Closure metaClosure) throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        return rows(sql, params, offset, maxRows, metaClosure);
    }    
    

    /**
     * Performs the given SQL query and return the first row of the result set.
     * <p/>
     * Example usage:
     * <pre>
     * def ans = sql.firstRow("select * from PERSON where firstname like 'S%'")
     * println ans.firstname
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql the SQL statement
     * @return a GroovyRowResult object or <code>null</code> if no row is found
     * @throws SQLException if a database access error occurs
     */
    public GroovyRowResult firstRow(String sql) throws SQLException {
        List<GroovyRowResult> rows = rows(sql);
        if (rows.isEmpty()) return null;
        return (rows.get(0));
    }

    /**
     * Performs the given SQL query and return
     * the first row of the result set.
     * The query may contain GString expressions.
     * <p/>
     * Example usage:
     * <pre>
     * def location = 25
     * def ans = sql.firstRow("select * from PERSON where location_id < $location")
     * println ans.firstname
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @return a GroovyRowResult object or <code>null</code> if no row is found
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     */
    public GroovyRowResult firstRow(GString gstring) throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        return firstRow(sql, params);
    }

    /**
     * Performs the given SQL query and return the first row of the result set.
     * The query may contain placeholder question marks which match the given list of parameters.
     * <p/>
     * Example usages:
     * <pre>
     * def ans = sql.firstRow("select * from PERSON where lastname like ?", ['%a%'])
     * println ans.firstname
     * </pre>
     * If your database returns scalar functions as ResultSets, you can also use firstRow
     * to gain access to stored procedure results, e.g. using hsqldb 1.9 RC4:
     * <pre>
     * sql.execute """
     *     create function FullName(p_firstname VARCHAR(40)) returns VARCHAR(80)
     *     BEGIN atomic
     *     DECLARE ans VARCHAR(80);
     *     SET ans = (SELECT firstname || ' ' || lastname FROM PERSON WHERE firstname = p_firstname);
     *     RETURN ans;
     *     END
     * """
     *
     * assert sql.firstRow("{call FullName(?)}", ['Sam'])[0] == 'Sam Pullara'
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql    the SQL statement
     * @param params a list of parameters
     * @return a GroovyRowResult object or <code>null</code> if no row is found
     * @throws SQLException if a database access error occurs
     */
    public GroovyRowResult firstRow(String sql, List<Object> params) throws SQLException {
        List<GroovyRowResult> rows = rows(sql, params);
        if (rows.isEmpty()) return null;
        return rows.get(0);
    }

    /**
     * Performs the given SQL query and return the first row of the result set.
     *
     * An Object array variant of {@link #firstRow(String, List)}.
     *
     * @param sql    the SQL statement
     * @param params an array of parameters
     * @return a GroovyRowResult object or <code>null</code> if no row is found
     * @throws SQLException if a database access error occurs
     */
    public GroovyRowResult firstRow(String sql, Object[] params) throws SQLException {
        return firstRow(sql, Arrays.asList(params));
    }

    /**
     * Executes the given piece of SQL.
     * Also saves the updateCount, if any, for subsequent examination.
     * <p/>
     * Example usages:
     * <pre>
     * sql.execute "DROP TABLE IF EXISTS person"
     *
     * sql.execute """
     *     CREATE TABLE person (
     *         id INTEGER NOT NULL,
     *         firstname VARCHAR(100),
     *         lastname VARCHAR(100),
     *         location_id INTEGER
     *     )
     * """
     *
     * sql.execute """
     *     INSERT INTO person (id, firstname, lastname, location_id) VALUES (4, 'Paul', 'King', 40)
     * """
     * assert sql.updateCount == 1
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql the SQL to execute
     * @return <code>true</code> if the first result is a <code>ResultSet</code>
     *         object; <code>false</code> if it is an update count or there are
     *         no results
     * @throws SQLException if a database access error occurs
     */
    public boolean execute(String sql) throws SQLException {
        Connection connection = createConnection();
        Statement statement = null;
        try {
            statement = getStatement(connection, sql);
            // TODO handle multiple results
            boolean isResultSet = statement.execute(sql);
            this.updateCount = statement.getUpdateCount();
            return isResultSet;
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement);
        }
    }

    /**
     * Executes the given piece of SQL with parameters.
     * Also saves the updateCount, if any, for subsequent examination.
     * <p/>
     * Example usage:
     * <pre>
     * sql.execute """
     *     insert into PERSON (id, firstname, lastname, location_id) values (?, ?, ?, ?)
     * """, [1, "Guillaume", "Laforge", 10]
     * assert sql.updateCount == 1
     * </pre>
     *
     * This method supports named and named ordinal parameters.
     * See the class Javadoc for more details.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql    the SQL statement
     * @param params a list of parameters
     * @return <code>true</code> if the first result is a <code>ResultSet</code>
     *         object; <code>false</code> if it is an update count or there are
     *         no results
     * @throws SQLException if a database access error occurs
     */
    public boolean execute(String sql, List<Object> params) throws SQLException {
        Connection connection = createConnection();
        PreparedStatement statement = null;
        try {
            statement = getPreparedStatement(connection, sql, params);
            // TODO handle multiple results
            boolean isResultSet = statement.execute();
            this.updateCount = statement.getUpdateCount();
            return isResultSet;
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement);
        }
    }

    /**
     * Executes the given piece of SQL with parameters.
     *
     * An Object array variant of {@link #execute(String, List)}.
     *
     * @param sql    the SQL statement
     * @param params an array of parameters
     * @return <code>true</code> if the first result is a <code>ResultSet</code>
     *         object; <code>false</code> if it is an update count or there are
     *         no results
     * @throws SQLException if a database access error occurs
     */
    public boolean execute(String sql, Object[] params) throws SQLException {
        return execute(sql, Arrays.asList(params));
    }

    /**
     * Executes the given SQL with embedded expressions inside.
     * Also saves the updateCount, if any, for subsequent examination.
     * <p/>
     * Example usage:
     * <pre>
     * def scott = [firstname: "Scott", lastname: "Davis", id: 5, location_id: 50]
     * sql.execute """
     *     insert into PERSON (id, firstname, lastname, location_id) values ($scott.id, $scott.firstname, $scott.lastname, $scott.location_id)
     * """
     * assert sql.updateCount == 1
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @return <code>true</code> if the first result is a <code>ResultSet</code>
     *         object; <code>false</code> if it is an update count or there are
     *         no results
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     */
    public boolean execute(GString gstring) throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        return execute(sql, params);
    }

    /**
     * Executes the given SQL statement (typically an INSERT statement).
     * Use this variant when you want to receive the values of any
     * auto-generated columns, such as an autoincrement ID field.
     * See {@link #executeInsert(GString)} for more details.
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql The SQL statement to execute
     * @return A list of the auto-generated column values for each
     *         inserted row (typically auto-generated keys)
     * @throws SQLException if a database access error occurs
     */
    public List<List<Object>> executeInsert(String sql) throws SQLException {
        Connection connection = createConnection();
        Statement statement = null;
        try {
            statement = getStatement(connection, sql);
            this.updateCount = statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            ResultSet keys = statement.getGeneratedKeys();
            return calculateKeys(keys);
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement);
        }
    }

    /**
     * Executes the given SQL statement (typically an INSERT statement).
     * Use this variant when you want to receive the values of any
     * auto-generated columns, such as an autoincrement ID field.
     * The query may contain placeholder question marks which match the given list of parameters.
     * See {@link #executeInsert(GString)} for more details.
     *
     * This method supports named and named ordinal parameters.
     * See the class Javadoc for more details.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql    The SQL statement to execute
     * @param params The parameter values that will be substituted
     *               into the SQL statement's parameter slots
     * @return A list of the auto-generated column values for each
     *         inserted row (typically auto-generated keys)
     * @throws SQLException if a database access error occurs
     */
    public List<List<Object>> executeInsert(String sql, List<Object> params) throws SQLException {
        Connection connection = createConnection();
        PreparedStatement statement = null;
        try {
            statement = getPreparedStatement(connection, sql, params, Statement.RETURN_GENERATED_KEYS);
            this.updateCount = statement.executeUpdate();
            ResultSet keys = statement.getGeneratedKeys();
            return calculateKeys(keys);
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement);
        }
    }

    /**
     * Executes the given SQL statement (typically an INSERT statement).
     *
     * An Object array variant of {@link #executeInsert(String, List)}.
     *
     * @param sql    The SQL statement to execute
     * @param params The parameter values that will be substituted
     *               into the SQL statement's parameter slots
     * @return A list of the auto-generated column values for each
     *         inserted row (typically auto-generated keys)
     * @throws SQLException if a database access error occurs
     */
    public List<List<Object>> executeInsert(String sql, Object[] params) throws SQLException {
        return executeInsert(sql, Arrays.asList(params));
    }

    /**
     * <p>Executes the given SQL statement (typically an INSERT statement).
     * Use this variant when you want to receive the values of any
     * auto-generated columns, such as an autoincrement ID field.
     * The query may contain GString expressions.</p>
     *
     * <p>Generated key values can be accessed using
     * array notation. For example, to return the second auto-generated
     * column value of the third row, use <code>keys[3][1]</code>. The
     * method is designed to be used with SQL INSERT statements, but is
     * not limited to them.</p>
     *
     * <p>The standard use for this method is when a table has an
     * autoincrement ID column and you want to know what the ID is for
     * a newly inserted row. In this example, we insert a single row
     * into a table in which the first column contains the autoincrement
     * ID:</p>
     * <pre>
     *     def sql = Sql.newInstance("jdbc:mysql://localhost:3306/groovy",
     *                               "user",
     *                               "password",
     *                               "com.mysql.jdbc.Driver")
     * <p/>
     *     def keys = sql.executeInsert("insert into test_table (INT_DATA, STRING_DATA) "
     *                           + "VALUES (1, 'Key Largo')")
     * <p/>
     *     def id = keys[0][0]
     * <p/>
     *     // 'id' now contains the value of the new row's ID column.
     *     // It can be used to update an object representation's
     *     // id attribute for example.
     *     ...
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @return A list of the auto-generated column values for each
     *         inserted row (typically auto-generated keys)
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     */
    public List<List<Object>> executeInsert(GString gstring) throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        return executeInsert(sql, params);
    }

    /**
     * Executes the given SQL update.
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql the SQL to execute
     * @return the number of rows updated or 0 for SQL statements that return nothing
     * @throws SQLException if a database access error occurs
     */
    public int executeUpdate(String sql) throws SQLException {
        Connection connection = createConnection();
        Statement statement = null;
        try {
            statement = getStatement(connection, sql);
            this.updateCount = statement.executeUpdate(sql);
            return this.updateCount;
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement);
        }
    }

    /**
     * Executes the given SQL update with parameters.
     *
     * This method supports named and named ordinal parameters.
     * See the class Javadoc for more details.
     * <p/>
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql    the SQL statement
     * @param params a list of parameters
     * @return the number of rows updated or 0 for SQL statements that return nothing
     * @throws SQLException if a database access error occurs
     */
    public int executeUpdate(String sql, List<Object> params) throws SQLException {
        Connection connection = createConnection();
        PreparedStatement statement = null;
        try {
            statement = getPreparedStatement(connection, sql, params);
            this.updateCount = statement.executeUpdate();
            return this.updateCount;
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement);
        }
    }

    /**
     * Executes the given SQL update with parameters.
     *
     * An Object array variant of {@link #executeUpdate(String, List)}.
     *
     * @param sql    the SQL statement
     * @param params an array of parameters
     * @return the number of rows updated or 0 for SQL statements that return nothing
     * @throws SQLException if a database access error occurs
     */
    public int executeUpdate(String sql, Object[] params) throws SQLException {
        return executeUpdate(sql, Arrays.asList(params));
    }

    /**
     * Executes the given SQL update with embedded expressions inside.
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @return the number of rows updated or 0 for SQL statements that return nothing
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     */
    public int executeUpdate(GString gstring) throws SQLException {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        return executeUpdate(sql, params);
    }

    /**
     * Performs a stored procedure call.
     * <p/>
     * Example usage (tested with MySQL) - suppose we have the following stored procedure:
     * <pre>
     * sql.execute """
     *     CREATE PROCEDURE HouseSwap(_first1 VARCHAR(50), _first2 VARCHAR(50))
     *     BEGIN
     *         DECLARE _loc1 INT;
     *         DECLARE _loc2 INT;
     *         SELECT location_id into _loc1 FROM PERSON where firstname = _first1;
     *         SELECT location_id into _loc2 FROM PERSON where firstname = _first2;
     *         UPDATE PERSON
     *         set location_id = case firstname
     *             when _first1 then _loc2
     *             when _first2 then _loc1
     *         end
     *         where (firstname = _first1 OR firstname = _first2);
     *     END
     * """
     * </pre>
     * then you can invoke the procedure as follows:
     * <pre>
     * def rowsChanged = sql.call("{call HouseSwap('Guillaume', 'Paul')}")
     * assert rowsChanged == 2
     * </pre>
     *
     * @param sql the SQL statement
     * @return the number of rows updated or 0 for SQL statements that return nothing
     * @throws SQLException if a database access error occurs
     */
    public int call(String sql) throws Exception {
        return call(sql, EMPTY_LIST);
    }

    /**
     * Performs a stored procedure call with the given embedded parameters.
     * <p/>
     * Example usage - see {@link #call(String)} for more details about
     * creating a <code>HouseSwap(IN name1, IN name2)</code> stored procedure.
     * Once created, it can be called like this:
     * <pre>
     * def p1 = 'Paul'
     * def p2 = 'Guillaume'
     * def rowsChanged = sql.call("{call HouseSwap($p1, $p2)}")
     * assert rowsChanged == 2
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @return the number of rows updated or 0 for SQL statements that return nothing
     * @throws SQLException if a database access error occurs
     * @see #expand(Object)
     * @see #call(String)
     */
    public int call(GString gstring) throws Exception {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        return call(sql, params);
    }

    /**
     * Performs a stored procedure call with the given parameters.
     * <p/>
     * Example usage - see {@link #call(String)} for more details about
     * creating a <code>HouseSwap(IN name1, IN name2)</code> stored procedure.
     * Once created, it can be called like this:
     * <pre>
     * def rowsChanged = sql.call("{call HouseSwap(?, ?)}", ['Guillaume', 'Paul'])
     * assert rowsChanged == 2
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql    the SQL statement
     * @param params a list of parameters
     * @return the number of rows updated or 0 for SQL statements that return nothing
     * @throws SQLException if a database access error occurs
     * @see #call(String)
     */
    public int call(String sql, List<Object> params) throws Exception {
        Connection connection = createConnection();
        CallableStatement statement = connection.prepareCall(sql);
        try {
            LOG.fine(sql + " | " + params);
            setParameters(params, statement);
            configure(statement);
            return statement.executeUpdate();
        }
        catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(connection, statement);
        }
    }

    /**
     * Performs a stored procedure call with the given parameters.
     * <p/>
     * An Object array variant of {@link #call(String, List)}.
     *
     * @param sql    the SQL statement
     * @param params an array of parameters
     * @return the number of rows updated or 0 for SQL statements that return nothing
     * @throws SQLException if a database access error occurs
     * @see #call(String)
     */
    public int call(String sql, Object[] params) throws Exception {
        return call(sql, Arrays.asList(params));
    }

    /**
     * Performs a stored procedure call with the given parameters.  The closure
     * is called once with all the out parameters.
     * <p/>
     * Example usage - suppose we create a stored procedure (ignore its simplistic implementation):
     * <pre>
     * // Tested with MySql 5.0.75
     * sql.execute """
     *     CREATE PROCEDURE Hemisphere(
     *         IN p_firstname VARCHAR(50),
     *         IN p_lastname VARCHAR(50),
     *         OUT ans VARCHAR(50))
     *     BEGIN
     *     DECLARE loc INT;
     *     SELECT location_id into loc FROM PERSON where firstname = p_firstname and lastname = p_lastname;
     *     CASE loc
     *         WHEN 40 THEN
     *             SET ans = 'Southern Hemisphere';
     *         ELSE
     *             SET ans = 'Northern Hemisphere';
     *     END CASE;
     *     END;
     * """
     * </pre>
     * we can now call the stored procedure as follows:
     * <pre>
     * sql.call '{call Hemisphere(?, ?, ?)}', ['Guillaume', 'Laforge', Sql.VARCHAR], { dwells ->
     *     println dwells
     * }
     * </pre>
     * which will output '<code>Northern Hemisphere</code>'.
     * <p/>
     * We can also access stored functions with scalar return values where the return value
     * will be treated as an OUT parameter. Here are examples for various databases for
     * creating such a procedure:
     * <pre>
     * // Tested with MySql 5.0.75
     * sql.execute """
     *     create function FullName(p_firstname VARCHAR(40)) returns VARCHAR(80)
     *     begin
     *         declare ans VARCHAR(80);
     *         SELECT CONCAT(firstname, ' ', lastname) INTO ans FROM PERSON WHERE firstname = p_firstname;
     *         return ans;
     *     end
     * """
     *
     * // Tested with MS SQLServer Express 2008
     * sql.execute """
     *     {@code create function FullName(@firstname VARCHAR(40)) returns VARCHAR(80)}
     *     begin
     *         declare {@code @ans} VARCHAR(80)
     *         {@code SET @ans = (SELECT firstname + ' ' + lastname FROM PERSON WHERE firstname = @firstname)}
     *         return {@code @ans}
     *     end
     * """
     *
     * // Tested with Oracle XE 10g
     * sql.execute """
     *     create function FullName(p_firstname VARCHAR) return VARCHAR is
     *     ans VARCHAR(80);
     *     begin
     *         SELECT CONCAT(CONCAT(firstname, ' '), lastname) INTO ans FROM PERSON WHERE firstname = p_firstname;
     *         return ans;
     *     end;
     * """
     * </pre>
     * and here is how you access the stored function for all databases:
     * <pre>
     * sql.call("{? = call FullName(?)}", [Sql.VARCHAR, 'Sam']) { name ->
     *     assert name == 'Sam Pullara'
     * }
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param sql     the sql statement
     * @param params  a list of parameters
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     */
    public void call(String sql, List<Object> params, Closure closure) throws Exception {
        Connection connection = createConnection();
        CallableStatement statement = connection.prepareCall(sql);
        List<GroovyResultSet> resultSetResources = new ArrayList<GroovyResultSet>();
        try {
            LOG.fine(sql + " | " + params);
            setParameters(params, statement);
            // TODO handle multiple results and mechanism for retrieving ResultSet if any (GROOVY-3048)
            statement.execute();
            List<Object> results = new ArrayList<Object>();
            int indx = 0;
            int inouts = 0;
            for (Object value : params) {
                if (value instanceof OutParameter) {
                    if (value instanceof ResultSetOutParameter) {
                        GroovyResultSet resultSet = CallResultSet.getImpl(statement, indx);
                        resultSetResources.add(resultSet);
                        results.add(resultSet);
                    } else {
                        Object o = statement.getObject(indx + 1);
                        if (o instanceof ResultSet) {
                            GroovyResultSet resultSet = new GroovyResultSetProxy((ResultSet) o).getImpl();
                            results.add(resultSet);
                            resultSetResources.add(resultSet);
                        } else {
                            results.add(o);
                        }
                    }
                    inouts++;
                }
                indx++;
            }
            closure.call(results.toArray(new Object[inouts]));
        } catch (SQLException e) {
            LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
            throw e;
        } finally {
            closeResources(connection, statement);
            for (GroovyResultSet rs : resultSetResources) {
                closeResources(null, null, rs);
            }
        }
    }

    /**
     * Performs a stored procedure call with the given parameters,
     * calling the closure once with all result objects.
     * <p/>
     * See {@link #call(String, List, Closure)} for more details about
     * creating a <code>Hemisphere(IN first, IN last, OUT dwells)</code> stored procedure.
     * Once created, it can be called like this:
     * <pre>
     * def first = 'Scott'
     * def last = 'Davis'
     * sql.call "{call Hemisphere($first, $last, ${Sql.VARCHAR})}", { dwells ->
     *     println dwells
     * }
     * </pre>
     * <p/>
     * As another example, see {@link #call(String, List, Closure)} for more details about
     * creating a <code>FullName(IN first)</code> stored function.
     * Once created, it can be called like this:
     * <pre>
     * def first = 'Sam'
     * sql.call("{$Sql.VARCHAR = call FullName($first)}") { name ->
     *     assert name == 'Sam Pullara'
     * }
     * </pre>
     *
     * Resource handling is performed automatically where appropriate.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @param closure called for each row with a GroovyResultSet
     * @throws SQLException if a database access error occurs
     * @see #call(String, List, Closure)
     * @see #expand(Object)
     */
    public void call(GString gstring, Closure closure) throws Exception {
        List<Object> params = getParameters(gstring);
        String sql = asSql(gstring, params);
        call(sql, params, closure);
    }

    /**
     * If this SQL object was created with a Connection then this method closes
     * the connection. If this SQL object was created from a DataSource then
     * this method only frees any cached objects (statements in particular).
     */
    public void close() {
        namedParamSqlCache.clear();
        namedParamIndexPropCache.clear();
        clearStatementCache();
        if (useConnection != null) {
            try {
                useConnection.close();
            }
            catch (SQLException e) {
                LOG.finest("Caught exception closing connection: " + e.getMessage());
            }
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }


    /**
     * If this SQL object was created with a Connection then this method commits
     * the connection. If this SQL object was created from a DataSource then
     * this method does nothing.
     *
     * @throws SQLException if a database access error occurs
     */
    public void commit() throws SQLException {
        if (useConnection == null) {
            LOG.info("Commit operation not supported when using datasets unless using withTransaction or cacheConnection - attempt to commit ignored");
            return;
        }
        try {
            useConnection.commit();
        }
        catch (SQLException e) {
            LOG.warning("Caught exception committing connection: " + e.getMessage());
            throw e;
        }
    }

    /**
     * If this SQL object was created with a Connection then this method rolls back
     * the connection. If this SQL object was created from a DataSource then
     * this method does nothing.
     *
     * @throws SQLException if a database access error occurs
     */
    public void rollback() throws SQLException {
        if (useConnection == null) {
            LOG.info("Rollback operation not supported when using datasets unless using withTransaction or cacheConnection - attempt to rollback ignored");
            return;
        }
        try {
            useConnection.rollback();
        }
        catch (SQLException e) {
            LOG.warning("Caught exception rolling back connection: " + e.getMessage());
            throw e;
        }
    }

    /**
     * @return Returns the updateCount.
     */
    public int getUpdateCount() {
        return updateCount;
    }

    /**
     * If this instance was created with a single Connection then the connection
     * is returned. Otherwise if this instance was created with a DataSource
     * then this method returns null
     *
     * @return the connection wired into this object, or null if this object
     *         uses a DataSource
     */
    public Connection getConnection() {
        return useConnection;
    }

    private void setConnection(Connection connection) {
        useConnection = connection;
    }

    /**
     * Allows a closure to be passed in to configure the JDBC statements before they are executed.
     * It can be used to do things like set the query size etc. When this method is invoked, the supplied
     * closure is saved. Statements subsequently created from other methods will then be
     * configured using this closure. The statement being configured is passed into the closure
     * as its single argument, e.g.:
     * <pre>
     * sql.withStatement{ stmt -> stmt.maxRows = 10 }
     * def firstTenRows = sql.rows("select * from table")
     * </pre>
     *
     * @param configureStatement the closure
     */
    public void withStatement(Closure configureStatement) {
        this.configureStatement = configureStatement;
    }

    /**
     * Enables statement caching.</br>
     * if <i>cacheStatements</i> is true, cache is created and all created prepared statements will be cached.</br>
     * if <i>cacheStatements</i> is false, all cached statements will be properly closed.
     *
     * @param cacheStatements the new value
     */
    public synchronized void setCacheStatements(boolean cacheStatements) {
        this.cacheStatements = cacheStatements;
        if (!cacheStatements) {
            clearStatementCache();
        }
    }

    /**
     * @return boolean true if cache is enabled (default is false)
     */
    public boolean isCacheStatements() {
        return cacheStatements;
    }

    /**
     * Caches the connection used while the closure is active.
     * If the closure takes a single argument, it will be called
     * with the connection, otherwise it will be called with no arguments.
     *
     * @param closure the given closure
     * @throws SQLException if a database error occurs
     */
    public synchronized void cacheConnection(Closure closure) throws SQLException {
        boolean savedCacheConnection = cacheConnection;
        cacheConnection = true;
        Connection connection = null;
        try {
            connection = createConnection();
            callClosurePossiblyWithConnection(closure, connection);
        }
        finally {
            cacheConnection = false;
            closeResources(connection, null);
            cacheConnection = savedCacheConnection;
            if (dataSource != null && !cacheConnection) {
                useConnection = null;
            }
        }
    }

    /**
     * Performs the closure within a transaction using a cached connection.
     * If the closure takes a single argument, it will be called
     * with the connection, otherwise it will be called with no arguments.
     *
     * @param closure the given closure
     * @throws SQLException if a database error occurs
     */
    public synchronized void withTransaction(Closure closure) throws SQLException {
        boolean savedCacheConnection = cacheConnection;
        cacheConnection = true;
        Connection connection = null;
        boolean savedAutoCommit = true;
        try {
            connection = createConnection();
            savedAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            callClosurePossiblyWithConnection(closure, connection);
            connection.commit();
        } catch (SQLException e) {
            handleError(connection, e);
            throw e;
        } catch (RuntimeException e) {
            handleError(connection, e);
            throw e;
        } catch (Error e) {
            handleError(connection, e);
            throw e;
        } finally {
            if (connection != null) connection.setAutoCommit(savedAutoCommit);
            cacheConnection = false;
            closeResources(connection, null);
            cacheConnection = savedCacheConnection;
            if (dataSource != null && !cacheConnection) {
                useConnection = null;
            }
        }
    }

    /**
     * Returns true if the current Sql object is currently executing a withBatch
     * method call.
     *
     * @return true if a withBatch call is currently being executed.
     */
    public boolean isWithinBatch() {
        return withinBatch;
    }

    /**
     * Performs the closure (containing batch operations) within a batch.
     * Uses a batch size of zero, i.e. no automatic partitioning of batches.
     *
     * This means that <code>executeBatch()</code> will be called automatically after the <code>withBatch</code>
     * closure has finished but may be called explicitly if desired as well for more fine-grained
     * partitioning of the batch.
     *
     * The closure will be called with a single argument; the database
     * statement (actually a <code>BatchingStatementWrapper</code> helper object)
     * associated with this batch.
     *
     * Use it like this:
     * <pre>
     * def updateCounts = sql.withBatch { stmt ->
     *     stmt.addBatch("insert into TABLENAME ...")
     *     stmt.addBatch("insert into TABLENAME ...")
     *     stmt.addBatch("insert into TABLENAME ...")
     *     ...
     * }
     * </pre>
     * For integrity and performance reasons, you may wish to consider executing your batch command(s) within a transaction:
     * <pre>
     * sql.withTransaction {
     *     def result1 = sql.withBatch { ... }
     *     ...
     * }
     * </pre>
     *
     * @param closure the closure containing batch and optionally other statements
     * @return an array of update counts containing one element for each
     *         command in the batch.  The elements of the array are ordered according
     *         to the order in which commands were added to the batch.
     * @throws SQLException if a database access error occurs,
     *         or this method is called on a closed <code>Statement</code>, or the
     *         driver does not support batch statements. Throws {@link java.sql.BatchUpdateException}
     *         (a subclass of <code>SQLException</code>) if one of the commands sent to the
     *         database fails to execute properly or attempts to return a result set.
     * @see #withBatch(int, Closure)
     */
    public int[] withBatch(Closure closure) throws SQLException {
        return withBatch(0, closure);
    }

    /**
     * Performs the closure (containing batch operations) within a batch using a given batch size.
     *
     * After every <code>batchSize</code> <code>addBatch(sqlBatchOperation)</code>
     * operations, automatically calls an <code>executeBatch()</code> operation to "chunk" up the database operations
     * into partitions. Though not normally needed, you can also explicitly call <code>executeBatch()</code> which
     * after executing the current batch, resets the batch count back to zero.
     *
     * The closure will be called with a single argument; the database statement
     * (actually a <code>BatchingStatementWrapper</code> helper object)
     * associated with this batch.
     *
     * Use it like this for batchSize of 20:
     * <pre>
     * def updateCounts = sql.withBatch(20) { stmt ->
     *     stmt.addBatch("insert into TABLENAME ...")
     *     stmt.addBatch("insert into TABLENAME ...")
     *     stmt.addBatch("insert into TABLENAME ...")
     *     ...
     * }
     * </pre>
     * For integrity and performance reasons, you may wish to consider executing your batch command(s) within a transaction:
     * <pre>
     * sql.withTransaction {
     *     def result1 = sql.withBatch { ... }
     *     ...
     * }
     * </pre>
     *
     * @param batchSize partition the batch into batchSize pieces, i.e. after batchSize
     *        <code>addBatch()</code> invocations, call <code>executeBatch()</code> automatically;
     *        0 means manual calls to executeBatch are required
     * @param closure the closure containing batch and optionally other statements
     * @return an array of update counts containing one element for each
     *         command in the batch.  The elements of the array are ordered according
     *         to the order in which commands were added to the batch.
     * @throws SQLException if a database access error occurs,
     *         or this method is called on a closed <code>Statement</code>, or the
     *         driver does not support batch statements. Throws {@link java.sql.BatchUpdateException}
     *         (a subclass of <code>SQLException</code>) if one of the commands sent to the
     *         database fails to execute properly or attempts to return a result set.
     * @see #withBatch(Closure)
     * @see BatchingStatementWrapper
     * @see Statement
     */
    public int[] withBatch(int batchSize, Closure closure) throws SQLException {
        Connection connection = createConnection();
        BatchingStatementWrapper statement = null;
        boolean savedWithinBatch = withinBatch;
        try {
            withinBatch = true;
            statement = new BatchingStatementWrapper(createStatement(connection), batchSize, LOG);
            closure.call(statement);
            return statement.executeBatch();
        }
        catch (SQLException e) {
            LOG.warning("Error during batch execution: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(statement);
            closeResources(connection);
            withinBatch = savedWithinBatch;
        }
    }

    /**
     * Performs the closure (containing batch operations specific to an associated prepared statement)
     * within a batch. Uses a batch size of zero, i.e. no automatic partitioning of batches.
     *
     * This means that <code>executeBatch()</code> will be called automatically after the <code>withBatch</code>
     * closure has finished but may be called explicitly if desired as well for more fine-grained
     * partitioning of the batch.
     *
     * The closure will be called with a single argument; the prepared
     * statement (actually a <code>BatchingPreparedStatementWrapper</code> helper object)
     * associated with this batch.
     *
     * An example:
     * <pre>
     * def updateCounts = sql.withBatch('insert into TABLENAME(a, b, c) values (?, ?, ?)') { ps ->
     *     ps.addBatch([10, 12, 5])
     *     ps.addBatch([7, 3, 98])
     *     ps.addBatch(22, 67, 11)
     *     def partialUpdateCounts = ps.executeBatch() // optional interim batching
     *     ps.addBatch(30, 40, 50)
     *     ...
     * }
     * </pre>
     * For integrity and performance reasons, you may wish to consider executing your batch command(s) within a transaction:
     * <pre>
     * sql.withTransaction {
     *     def result1 = sql.withBatch { ... }
     *     ...
     * }
     * </pre>
     *
     * @param sql batch update statement
     * @param closure the closure containing batch statements (to bind parameters) and optionally other statements
     * @return an array of update counts containing one element for each
     *         binding in the batch.  The elements of the array are ordered according
     *         to the order in which commands were executed.
     * @throws SQLException if a database access error occurs,
     *                      or this method is called on a closed <code>Statement</code>, or the
     *                      driver does not support batch statements. Throws {@link java.sql.BatchUpdateException}
     *                      (a subclass of <code>SQLException</code>) if one of the commands sent to the
     *                      database fails to execute properly or attempts to return a result set.
     * @see #withBatch(int, String, Closure)
     * @see BatchingPreparedStatementWrapper
     * @see PreparedStatement
     */
    public int[] withBatch(String sql, Closure closure) throws SQLException {
        return withBatch(0, sql, closure);
    }

    /**
     * Performs the closure (containing batch operations specific to an associated prepared statement)
     * within a batch using a given batch size.
     *
     * After every <code>batchSize</code> <code>addBatch(params)</code>
     * operations, automatically calls an <code>executeBatch()</code> operation to "chunk" up the database operations
     * into partitions. Though not normally needed, you can also explicitly call <code>executeBatch()</code> which
     * after executing the current batch, resets the batch count back to zero.
     *
     * The closure will be called with a single argument; the prepared
     * statement (actually a <code>BatchingPreparedStatementWrapper</code> helper object)
     * associated with this batch.
     *
     * Below is an example using a batchSize of 20:
     * <pre>
     * def updateCounts = sql.withBatch(20, 'insert into TABLENAME(a, b, c) values (?, ?, ?)') { ps ->
     *     ps.addBatch(10, 12, 5)      // varargs style
     *     ps.addBatch([7, 3, 98])     // list
     *     ps.addBatch([22, 67, 11])
     *     ...
     * }
     * </pre>
     * Named parameters (into maps or domain objects) are also supported:
     * <pre>
     * def updateCounts = sql.withBatch(20, 'insert into TABLENAME(a, b, c) values (:foo, :bar, :baz)') { ps ->
     *     ps.addBatch([foo:10, bar:12, baz:5])  // map
     *     ps.addBatch(foo:7, bar:3, baz:98)     // Groovy named args allow outer brackets to be dropped
     *     ...
     * }
     * </pre>
     * Named ordinal parameters (into maps or domain objects) are also supported:
     * <pre>
     * def updateCounts = sql.withBatch(20, 'insert into TABLENAME(a, b, c) values (?1.foo, ?2.bar, ?2.baz)') { ps ->
     *     ps.addBatch([[foo:22], [bar:67, baz:11]])  // list of maps or domain objects
     *     ps.addBatch([foo:10], [bar:12, baz:5])     // varargs allows outer brackets to be dropped
     *     ps.addBatch([foo:7], [bar:3, baz:98])
     *     ...
     * }
     * // swap to batch size of 5 and illustrate simple and domain object cases ...
     * class Person { String first, last }
     * def updateCounts2 = sql.withBatch(5, 'insert into PERSON(id, first, last) values (?1, ?2.first, ?2.last)') { ps ->
     *     ps.addBatch(1, new Person(first:'Peter', last:'Pan'))
     *     ps.addBatch(2, new Person(first:'Snow', last:'White'))
     *     ...
     * }
     * </pre>
     * For integrity and performance reasons, you may wish to consider executing your batch command(s) within a transaction:
     * <pre>
     * sql.withTransaction {
     *     def result1 = sql.withBatch { ... }
     *     ...
     * }
     * </pre>
     *
     * @param batchSize partition the batch into batchSize pieces, i.e. after batchSize
     *                  <code>addBatch()</code> invocations, call <code>executeBatch()</code> automatically;
     *                  0 means manual calls to executeBatch are required if additional partitioning of the batch is required
     * @param sql batch update statement
     * @param closure the closure containing batch statements (to bind parameters) and optionally other statements
     * @return an array of update counts containing one element for each
     *         binding in the batch.  The elements of the array are ordered according
     *         to the order in which commands were executed.
     * @throws SQLException if a database access error occurs,
     *                      or this method is called on a closed <code>Statement</code>, or the
     *                      driver does not support batch statements. Throws {@link java.sql.BatchUpdateException}
     *                      (a subclass of <code>SQLException</code>) if one of the commands sent to the
     *                      database fails to execute properly or attempts to return a result set.
     * @see BatchingPreparedStatementWrapper
     * @see PreparedStatement
     */
    public int[] withBatch(int batchSize, String sql, Closure closure) throws SQLException {
        Connection connection = createConnection();
        List<Tuple> indexPropList = null;
        SqlWithParams preCheck = preCheckForNamedParams(sql);
        boolean savedWithinBatch = withinBatch;
        BatchingPreparedStatementWrapper psWrapper = null;
        if (preCheck != null) {
            indexPropList = new ArrayList<Tuple>();
            for (Object next : preCheck.getParams()) {
                indexPropList.add((Tuple) next);
            }
            sql = preCheck.getSql();
        }

        try {
            withinBatch = true;
            PreparedStatement statement = (PreparedStatement) getAbstractStatement(new CreatePreparedStatementCommand(0), connection, sql);
            configure(statement);
            psWrapper = new BatchingPreparedStatementWrapper(statement, indexPropList, batchSize, LOG, this);
            closure.call(psWrapper);
            return psWrapper.executeBatch();
        }
        catch (SQLException e) {
            LOG.warning("Error during batch execution of '" + sql + "' with message: " + e.getMessage());
            throw e;
        }
        finally {
            closeResources(psWrapper);
            closeResources(connection);
            withinBatch = savedWithinBatch;
        }
    }

    /**
     * Caches every created preparedStatement in Closure <i>closure</i></br>
     * Every cached preparedStatement is closed after closure has been called.
     * If the closure takes a single argument, it will be called
     * with the connection, otherwise it will be called with no arguments.
     *
     * @param closure the given closure
     * @throws SQLException if a database error occurs
     * @see #setCacheStatements(boolean)
     */
    public synchronized void cacheStatements(Closure closure) throws SQLException {
        boolean savedCacheStatements = cacheStatements;
        cacheStatements = true;
        Connection connection = null;
        try {
            connection = createConnection();
            callClosurePossiblyWithConnection(closure, connection);
        }
        finally {
            cacheStatements = false;
            closeResources(connection, null);
            cacheStatements = savedCacheStatements;
        }
    }

    // protected implementation methods - extension points for subclasses
    //-------------------------------------------------------------------------

    /**
     * Hook to allow derived classes to access ResultSet returned from query.
     *
     * @param sql query to execute
     * @return the resulting ResultSet
     * @throws SQLException if a database error occurs
     */
    protected final ResultSet executeQuery(String sql) throws SQLException {
        AbstractQueryCommand command = createQueryCommand(sql);
        ResultSet rs = null;
        try {
            rs = command.execute();
        } finally {
            command.closeResources();
        }
        return rs;
    }

    /**
     * Hook to allow derived classes to access ResultSet returned from query.
     *
     * @param sql query to execute
     * @param params parameters matching question mark placeholders in the query
     * @return the resulting ResultSet
     * @throws SQLException if a database error occurs
     */
    protected final ResultSet executePreparedQuery(String sql, List<Object> params)
            throws SQLException {
        AbstractQueryCommand command = createPreparedQueryCommand(sql, params);
        ResultSet rs = null;
        try {
            rs = command.execute();
        } finally {
            command.closeResources();
        }
        return rs;
    }

    /**
     * Hook to allow derived classes to override list of result collection behavior.
     * The default behavior is to return a list of GroovyRowResult objects corresponding
     * to each row in the ResultSet.
     *
     * @param sql query to execute
     * @param rs the ResultSet to process
     * @return the resulting list of rows
     * @throws SQLException if a database error occurs
     */
    protected List<GroovyRowResult> asList(String sql, ResultSet rs) throws SQLException {
        return asList(sql, rs, null);
    }

    /**
     * Hook to allow derived classes to override list of result collection behavior.
     * The default behavior is to return a list of GroovyRowResult objects corresponding
     * to each row in the ResultSet.
     *
     * @param sql query to execute
     * @param rs the ResultSet to process
     * @param metaClosure called for meta data (only once after sql execution)
     * @return the resulting list of rows
     * @throws SQLException if a database error occurs
     */
    protected List<GroovyRowResult> asList(String sql, ResultSet rs, Closure metaClosure) throws SQLException {
        return asList(sql, rs, 0, 0, metaClosure);
    }
    
    protected List<GroovyRowResult> asList(String sql, ResultSet rs, int offset, int maxRows, Closure metaClosure) throws SQLException {
        List<GroovyRowResult> results = new ArrayList<GroovyRowResult>();

        try {
            if (metaClosure != null) {
                metaClosure.call(rs.getMetaData());
            }

            boolean cursorAtRow = moveCursor(rs, offset);
            if (!cursorAtRow) return null;
            
            int i = 0;
            while (rs.next() && (maxRows <= 0 || i++ < maxRows)) {
                results.add(SqlGroovyMethods.toRowResult(rs));
            }
            return (results);
        } catch (SQLException e) {
            LOG.warning("Failed to retrieve row from ResultSet for: " + sql + " because: " + e.getMessage());
            throw e;
        } finally {
            rs.close();
        }
    }

    /**
     * Hook to allow derived classes to override sql generation from GStrings.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @param values  the values to embed
     * @return the SQL version of the given query using ? instead of any parameter
     * @see #expand(Object)
     */
    protected String asSql(GString gstring, List<Object> values) {
        String[] strings = gstring.getStrings();
        if (strings.length <= 0) {
            throw new IllegalArgumentException("No SQL specified in GString: " + gstring);
        }
        boolean nulls = false;
        StringBuilder buffer = new StringBuilder();
        boolean warned = false;
        Iterator<Object> iter = values.iterator();
        for (int i = 0; i < strings.length; i++) {
            String text = strings[i];
            if (text != null) {
                buffer.append(text);
            }
            if (iter.hasNext()) {
                Object value = iter.next();
                if (value != null) {
                    if (value instanceof ExpandedVariable) {
                        buffer.append(((ExpandedVariable) value).getObject());
                        iter.remove();
                    } else {
                        boolean validBinding = true;
                        if (i < strings.length - 1) {
                            String nextText = strings[i + 1];
                            if ((text.endsWith("\"") || text.endsWith("'")) && (nextText.startsWith("'") || nextText.startsWith("\""))) {
                                if (!warned) {
                                    LOG.warning("In Groovy SQL please do not use quotes around dynamic expressions " +
                                            "(which start with $) as this means we cannot use a JDBC PreparedStatement " +
                                            "and so is a security hole. Groovy has worked around your mistake but the security hole is still there. " +
                                            "The expression so far is: " + buffer.toString() + "?" + nextText);
                                    warned = true;
                                }
                                buffer.append(value);
                                iter.remove();
                                validBinding = false;
                            }
                        }
                        if (validBinding) {
                            buffer.append("?");
                        }
                    }
                } else {
                    nulls = true;
                    iter.remove();
                    buffer.append("?'\"?"); // will replace these with nullish values
                }
            }
        }
        String sql = buffer.toString();
        if (nulls) {
            sql = nullify(sql);
        }
        return sql;
    }

    /**
     * Hook to allow derived classes to override null handling.
     * Default behavior is to replace ?'"? references with NULLish
     *
     * @param sql the SQL statement
     * @return the modified SQL String
     */
    protected String nullify(String sql) {
        /*
         * Some drivers (Oracle classes12.zip) have difficulty resolving data
         * type if setObject(null). We will modify the query to pass 'null', 'is
         * null', and 'is not null'
         */
        //could be more efficient by compiling expressions in advance.
        int firstWhere = findWhereKeyword(sql);
        if (firstWhere >= 0) {
            Pattern[] patterns = {Pattern.compile("(?is)^(.{" + firstWhere + "}.*?)!=\\s{0,1}(\\s*)\\?'\"\\?(.*)"),
                    Pattern.compile("(?is)^(.{" + firstWhere + "}.*?)<>\\s{0,1}(\\s*)\\?'\"\\?(.*)"),
                    Pattern.compile("(?is)^(.{" + firstWhere + "}.*?[^<>])=\\s{0,1}(\\s*)\\?'\"\\?(.*)"),};
            String[] replacements = {"$1 is not $2null$3", "$1 is not $2null$3", "$1 is $2null$3",};
            for (int i = 0; i < patterns.length; i++) {
                Matcher matcher = patterns[i].matcher(sql);
                while (matcher.matches()) {
                    sql = matcher.replaceAll(replacements[i]);
                    matcher = patterns[i].matcher(sql);
                }
            }
        }
        return sql.replaceAll("\\?'\"\\?", "null");
    }

    /**
     * Hook to allow derived classes to override where clause sniffing.
     * Default behavior is to find the first 'where' keyword in the sql
     * doing simple avoidance of the word 'where' within quotes.
     *
     * @param sql the SQL statement
     * @return the index of the found keyword or -1 if not found
     */
    protected int findWhereKeyword(String sql) {
        char[] chars = sql.toLowerCase().toCharArray();
        char[] whereChars = "where".toCharArray();
        int i = 0;
        boolean inString = false; //TODO: Cater for comments?
        int inWhere = 0;
        while (i < chars.length) {
            switch (chars[i]) {
                case '\'':
                    inString = !inString;
                    break;
                default:
                    if (!inString && chars[i] == whereChars[inWhere]) {
                        inWhere++;
                        if (inWhere == whereChars.length) {
                            return i;
                        }
                    }
            }
            i++;
        }
        return -1;
    }

    /**
     * Hook to allow derived classes to override behavior associated with
     * extracting params from a GString.
     *
     * @param gstring a GString containing the SQL query with embedded params
     * @return extracts the parameters from the expression as a List
     * @see #expand(Object)
     */
    protected List<Object> getParameters(GString gstring) {
        return new ArrayList<Object>(Arrays.asList(gstring.getValues()));
    }

    /**
     * Hook to allow derived classes to override behavior associated with
     * setting params for a prepared statement. Default behavior is to
     * append the parameters to the given statement using <code>setObject</code>.
     *
     * @param params    the parameters to append
     * @param statement the statement
     * @throws SQLException if a database access error occurs
     */
    protected void setParameters(List<Object> params, PreparedStatement statement) throws SQLException {
        int i = 1;
        for (Object value : params) {
            setObject(statement, i++, value);
        }
    }

    /**
     * Strategy method allowing derived classes to handle types differently
     * such as for CLOBs etc.
     *
     * @param statement the statement of interest
     * @param i         the index of the object of interest
     * @param value     the new object value
     * @throws SQLException if a database access error occurs
     */
    protected void setObject(PreparedStatement statement, int i, Object value)
            throws SQLException {
        if (value instanceof InParameter || value instanceof OutParameter) {
            if (value instanceof InParameter) {
                InParameter in = (InParameter) value;
                Object val = in.getValue();
                if (null == val) {
                    statement.setNull(i, in.getType());
                } else {
                    statement.setObject(i, val, in.getType());
                }
            }
            if (value instanceof OutParameter) {
                try {
                    OutParameter out = (OutParameter) value;
                    ((CallableStatement) statement).registerOutParameter(i, out.getType());
                } catch (ClassCastException e) {
                    throw new SQLException("Cannot register out parameter.");
                }
            }
        } else {
            try {
                statement.setObject(i, value);
            } catch (SQLException e) {
                if (value == null) {
                    SQLException se = new SQLException("Your JDBC driver may not support null arguments for setObject. Consider using Groovy's InParameter feature." +
                            (e.getMessage() == null ? "" : " (CAUSE: " + e.getMessage() + ")"));
                    se.setNextException(e);
                    throw se;
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * An extension point allowing derived classes to change the behavior of
     * connection creation. The default behavior is to either use the
     * supplied connection or obtain it from the supplied datasource.
     *
     * @return the connection associated with this Sql
     * @throws java.sql.SQLException if a SQL error occurs
     */
    protected Connection createConnection() throws SQLException {
        if ((cacheStatements || cacheConnection) && useConnection != null) {
            return useConnection;
        }
        if (dataSource != null) {
            // Use a doPrivileged here as many different properties need to be
            // read, and the policy shouldn't have to list them all.
            Connection con;
            try {
                con = AccessController.doPrivileged(new PrivilegedExceptionAction<Connection>() {
                    public Connection run() throws SQLException {
                        return dataSource.getConnection();
                    }
                });
            }
            catch (PrivilegedActionException pae) {
                Exception e = pae.getException();
                if (e instanceof SQLException) {
                    throw (SQLException) e;
                } else {
                    throw (RuntimeException) e;
                }
            }
            if (cacheStatements || cacheConnection) {
                useConnection = con;
            }
            return con;
        }
        return useConnection;
    }

    /**
     * An extension point allowing derived classes to change the behavior
     * of resource closing.
     *
     * @param connection the connection to close
     * @param statement  the statement to close
     * @param results    the results to close
     */
    protected void closeResources(Connection connection, Statement statement, ResultSet results) {
        if (results != null) {
            try {
                results.close();
            }
            catch (SQLException e) {
                LOG.finest("Caught exception closing resultSet: " + e.getMessage() + " - continuing");
            }
        }
        closeResources(connection, statement);
    }

    /**
     * An extension point allowing the behavior of resource closing to be
     * overridden in derived classes.
     *
     * @param connection the connection to close
     * @param statement  the statement to close
     */
    protected void closeResources(Connection connection, Statement statement) {
        if (cacheStatements) return;
        if (statement != null) {
            try {
                statement.close();
            }
            catch (SQLException e) {
                LOG.finest("Caught exception closing statement: " + e.getMessage() + " - continuing");
            }
        }
        closeResources(connection);
    }

    private void closeResources(BatchingStatementWrapper statement) {
        if (cacheStatements) return;
        if (statement != null) {
            try {
                statement.close();
            }
            catch (SQLException e) {
                LOG.finest("Caught exception closing statement: " + e.getMessage() + " - continuing");
            }
        }
    }

    /**
     * An extension point allowing the behavior of resource closing to be
     * overridden in derived classes.
     *
     * @param connection the connection to close
     */
    protected void closeResources(Connection connection) {
        if (cacheConnection) return;
        if (connection != null && dataSource != null) {
            try {
                connection.close();
            }
            catch (SQLException e) {
                LOG.finest("Caught exception closing connection: " + e.getMessage() + " - continuing");
            }
        }
    }

    /**
     * Provides a hook for derived classes to be able to configure JDBC statements.
     * Default behavior is to call a previously saved closure, if any, using the
     * statement as a parameter.
     *
     * @param statement the statement to configure
     */
    protected void configure(Statement statement) {
        // for thread safety, grab local copy
        Closure configureStatement = this.configureStatement;
        if (configureStatement != null) {
            configureStatement.call(statement);
        }
    }

    // private implementation methods
    //-------------------------------------------------------------------------

    private List<List<Object>> calculateKeys(ResultSet keys) throws SQLException {
        // Prepare a list to contain the auto-generated column
        // values, and then fetch them from the statement.
        List<List<Object>> autoKeys = new ArrayList<List<Object>>();
        int count = keys.getMetaData().getColumnCount();

        // Copy the column values into a list of a list.
        while (keys.next()) {
            List<Object> rowKeys = new ArrayList<Object>(count);
            for (int i = 1; i <= count; i++) {
                rowKeys.add(keys.getObject(i));
            }

            autoKeys.add(rowKeys);
        }
        return autoKeys;
    }

    private Statement createStatement(Connection connection) throws SQLException {
        if (resultSetHoldability == -1) {
            return connection.createStatement(resultSetType, resultSetConcurrency);
        }
        return connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    private void handleError(Connection connection, Throwable t) throws SQLException {
        if (connection != null) {
            LOG.warning("Rolling back due to: " + t.getMessage());
            connection.rollback();
        }
    }

    private void callClosurePossiblyWithConnection(Closure closure, Connection connection) {
        if (closure.getMaximumNumberOfParameters() == 1) {
            closure.call(connection);
        } else {
            closure.call();
        }
    }

    private void clearStatementCache() {
        Statement statements[];
        synchronized (statementCache) {
            if (statementCache.isEmpty())
                return;
            // Arrange to call close() outside synchronized block, since
            // the close may involve server requests.
            statements = new Statement[statementCache.size()];
            statementCache.values().toArray(statements);
            statementCache.clear();
        }
        for (Statement s : statements) {
            try {
                s.close();
            } catch (Exception e) {
                // It's normally safe to ignore exceptions during cleanup but here if there is
                // a closed statement in the cache, the cache is possibly corrupted, hence log
                // at slightly elevated level than similar cases.
                LOG.info("Failed to close statement. Already closed? Exception message: " + e.getMessage());
            }
        }
    }

    private Statement getAbstractStatement(AbstractStatementCommand cmd, Connection connection, String sql) throws SQLException {
        Statement stmt;
        if (cacheStatements) {
            synchronized (statementCache) { // checking for existence without sync can cause leak if object needs close().
                stmt = statementCache.get(sql);
                if (stmt == null) {
                    stmt = cmd.execute(connection, sql);
                    statementCache.put(sql, stmt);
                }
            }
        } else {
            stmt = cmd.execute(connection, sql);
        }
        return stmt;
    }

    private Statement getStatement(Connection connection, String sql) throws SQLException {
        LOG.fine(sql);
        Statement stmt = getAbstractStatement(new CreateStatementCommand(), connection, sql);
        configure(stmt);
        return stmt;
    }

    private PreparedStatement getPreparedStatement(Connection connection, String sql, List<Object> params, int returnGeneratedKeys) throws SQLException {
        SqlWithParams updated = checkForNamedParams(sql, params);
        LOG.fine(updated.getSql() + " | " + updated.getParams());
        PreparedStatement statement = (PreparedStatement) getAbstractStatement(new CreatePreparedStatementCommand(returnGeneratedKeys), connection, updated.getSql());
        setParameters(updated.getParams(), statement);
        configure(statement);
        return statement;
    }

    public SqlWithParams checkForNamedParams(String sql, List<Object> params) {
        SqlWithParams preCheck = preCheckForNamedParams(sql);
        if (preCheck == null) {
            return new SqlWithParams(sql, params);
        }

        List<Tuple> indexPropList = new ArrayList<Tuple>();
        for (Object next : preCheck.getParams()) {
            indexPropList.add((Tuple) next);
        }
        return new SqlWithParams(preCheck.getSql(), getUpdatedParams(params, indexPropList));
    }

    public SqlWithParams preCheckForNamedParams(String sql) {
        // look for quick exit
        if (!enableNamedQueries || !NAMED_QUERY_PATTERN.matcher(sql).find()) {
            return null;
        }

        ExtractIndexAndSql extractIndexAndSql = new ExtractIndexAndSql(sql).invoke();
        String newSql = extractIndexAndSql.getNewSql();
        if (sql.equals(newSql)) {
            return null;
        }

        List<Object> indexPropList = new ArrayList<Object>(extractIndexAndSql.getIndexPropList());
        return new SqlWithParams(newSql, indexPropList);
    }

    public List<Object> getUpdatedParams(List<Object> params, List<Tuple> indexPropList) {
        List<Object> updatedParams = new ArrayList<Object>();
        for (Tuple tuple : indexPropList) {
            int index = (Integer) tuple.get(0);
            String prop = (String) tuple.get(1);
            if (index < 0 || index >= params.size())
                throw new IllegalArgumentException("Invalid index " + index + " should be in range 1.." + params.size());
            updatedParams.add(prop.equals("<this>") ? params.get(index) : InvokerHelper.getProperty(params.get(index), prop));
        }
        return updatedParams;
    }

    private PreparedStatement getPreparedStatement(Connection connection, String sql, List<Object> params) throws SQLException {
        return getPreparedStatement(connection, sql, params, 0);
    }

    /**
     * @return boolean    true if caching is enabled (the default is true)
     */
    public boolean isCacheNamedQueries() {
        return cacheNamedQueries;
    }

    /**
     * Enables named query caching.</br>
     * if <i>cacheNamedQueries</i> is true, cache is created and processed named queries will be cached.</br>
     * if <i>cacheNamedQueries</i> is false, no caching will occur saving memory at the cost of additional processing time.
     *
     * @param cacheNamedQueries the new value
     */
    public void setCacheNamedQueries(boolean cacheNamedQueries) {
        this.cacheNamedQueries = cacheNamedQueries;
    }

    /**
     * @return boolean    true if named query processing is enabled (the default is true)
     */
    public boolean isEnableNamedQueries() {
        return enableNamedQueries;
    }

    /**
     * Enables named query support.</br>
     * if <i>enableNamedQueries</i> is true, queries with ':propname' and '?1.propname' style placeholders will be processed.</br>
     * if <i>enableNamedQueries</i> is false, this feature will be turned off.
     *
     * @param enableNamedQueries the new value
     */
    public void setEnableNamedQueries(boolean enableNamedQueries) {
        this.enableNamedQueries = enableNamedQueries;
    }

    // command pattern implementation classes
    //-------------------------------------------------------------------------

    private abstract class AbstractStatementCommand {
        /**
         * Execute the command that's defined by the subclass following
         * the Command pattern.  Specialized parameters are held in the command instances.
         *
         * @param conn all commands accept a connection
         * @param sql  all commands accept an SQL statement
         * @return statement that can be cached, etc.
         * @throws SQLException if a database error occurs
         */
        protected abstract Statement execute(Connection conn, String sql) throws SQLException;
    }

    private class CreatePreparedStatementCommand extends AbstractStatementCommand {
        private final int returnGeneratedKeys;

        private CreatePreparedStatementCommand(int returnGeneratedKeys) {
            this.returnGeneratedKeys = returnGeneratedKeys;
        }

        protected PreparedStatement execute(Connection connection, String sql) throws SQLException {
            if (returnGeneratedKeys != 0)
                return connection.prepareStatement(sql, returnGeneratedKeys);
            if (appearsLikeStoredProc(sql))
                return connection.prepareCall(sql);
            return connection.prepareStatement(sql);
        }

        private boolean appearsLikeStoredProc(String sql) {
            return sql.matches("\\s*[{]?\\s*[?]?\\s*[=]?\\s*[cC][aA][lL][lL].*");
        }
    }

    private class CreateStatementCommand extends AbstractStatementCommand {
        @Override
        protected Statement execute(Connection conn, String sql) throws SQLException {
            return createStatement(conn);
        }
    }

    protected abstract class AbstractQueryCommand {
        protected final String sql;
        protected Statement statement;
        private Connection connection;

        protected AbstractQueryCommand(String sql) {
            // Don't create statement in subclass constructors to avoid throw in constructors
            this.sql = sql;
        }

        /**
         * Execute the command that's defined by the subclass following
         * the Command pattern.  Specialized parameters are held in the command instances.
         *
         * @return ResultSet from executing a query
         * @throws SQLException if a database error occurs
         */
        protected final ResultSet execute() throws SQLException {
            connection = createConnection();
            setInternalConnection(connection);
            statement = null;
            try {
                // The variation in the pattern is isolated
                ResultSet result = runQuery(connection);
                assert (null != statement);
                return result;
            } catch (SQLException e) {
                LOG.warning("Failed to execute: " + sql + " because: " + e.getMessage());
                closeResources();
                connection = null;
                statement = null;
                throw e;
            }
        }

        /**
         * After performing the execute operation and making use of its return, it's necessary
         * to free the resources allocated for the statement.
         */
        protected final void closeResources() {
            Sql.this.closeResources(connection, statement);
        }

        /**
         * After performing the execute operation and making use of its return, it's necessary
         * to free the resources allocated for the statement.
         *
         * @param rs allows the caller to conveniently close its resource as well
         */
        protected final void closeResources(ResultSet rs) {
            Sql.this.closeResources(connection, statement, rs);
        }

        /**
         * Perform the query. Must set statement field so that the main ({@link #execute()}) method can clean up.
         * This is the method that encloses the variant part of the code.
         *
         * @param connection the connection to use
         * @return ResultSet from an executeQuery method.
         * @throws SQLException if a database error occurs
         */
        protected abstract ResultSet runQuery(Connection connection) throws SQLException;
    }

    private final class PreparedQueryCommand extends AbstractQueryCommand {
        private List<Object> params;

        private PreparedQueryCommand(String sql, List<Object> queryParams) {
            super(sql);
            params = queryParams;
        }

        @Override
        protected ResultSet runQuery(Connection connection) throws SQLException {
            PreparedStatement s = getPreparedStatement(connection, sql, params);
            statement = s;
            return s.executeQuery();
        }
    }

    private final class QueryCommand extends AbstractQueryCommand {

        private QueryCommand(String sql) {
            super(sql);
        }

        @Override
        protected ResultSet runQuery(Connection connection) throws SQLException {
            statement = getStatement(connection, sql);
            return statement.executeQuery(sql);
        }
    }

    /**
     * Factory for the QueryCommand command pattern object allows subclasses to
     * supply implementations of the command class. The factory will be used in a pattern
     * similar to
     *  <pre>
     * AbstractQueryCommand q = createQueryCommand("update TABLE set count = 0) where count is null");
     * try {
     *     ResultSet rs = q.execute();
     *     return asList(rs);
     * } finally {
     *     q.closeResources();
     * }
     * </pre>
     * @param sql statement to be executed
     * @return a command - invoke its execute() and closeResource() methods
     */
    protected AbstractQueryCommand createQueryCommand(String sql) {
        return new QueryCommand(sql);
    }

    /**
     * Factory for the PreparedQueryCommand command pattern object allows subclass to supply implementations
     * of the command class.
     * @see #createQueryCommand(String)
     * @param sql statement to be executed, including optional parameter placeholders (?)
     * @param queryParams List of parameter values corresponding to parameter placeholders
     * @return a command - invoke its execute() and closeResource() methods
     */
    protected AbstractQueryCommand createPreparedQueryCommand(String sql, List<Object> queryParams) {
        return new PreparedQueryCommand(sql, queryParams);
    }

    /**
     * Stub needed for testing.  Called when a connection is opened by one of the command-pattern classes
     * so that a test case can monitor the state of the connection through its subclass.
     * @param conn the connection that is about to be used by a command
     */
    protected void setInternalConnection(Connection conn) {
    }

    private class ExtractIndexAndSql {
        private String sql;
        private List<Tuple> indexPropList;
        private String newSql;

        private ExtractIndexAndSql(String sql) {
            this.sql = sql;
        }

        private List<Tuple> getIndexPropList() {
            return indexPropList;
        }

        private String getNewSql() {
            return newSql;
        }

        private ExtractIndexAndSql invoke() {
            if (cacheNamedQueries && namedParamSqlCache.containsKey(sql)) {
                newSql = namedParamSqlCache.get(sql);
                indexPropList = namedParamIndexPropCache.get(sql);
            } else {
                indexPropList = new ArrayList<Tuple>();
                StringBuilder sb = new StringBuilder();
                StringBuilder currentChunk = new StringBuilder();
                char[] chars = sql.toCharArray();
                int i = 0;
                boolean inString = false; //TODO: Cater for comments?
                while (i < chars.length) {
                    switch (chars[i]) {
                        case '\'':
                            inString = !inString;
                            if (inString) {
                                sb.append(adaptForNamedParams(currentChunk.toString(), indexPropList));
                                currentChunk = new StringBuilder();
                                currentChunk.append(chars[i]);
                            } else {
                                currentChunk.append(chars[i]);
                                sb.append(currentChunk);
                                currentChunk = new StringBuilder();
                            }
                            break;
                        default:
                            currentChunk.append(chars[i]);
                    }
                    i++;
                }
                if (inString)
                    throw new IllegalStateException("Failed to process query. Unterminated ' character?");
                sb.append(adaptForNamedParams(currentChunk.toString(), indexPropList));
                newSql = sb.toString();
                namedParamSqlCache.put(sql, newSql);
                namedParamIndexPropCache.put(sql, indexPropList);
            }
            return this;
        }

        private String adaptForNamedParams(String sql, List<Tuple> indexPropList) {
            StringBuilder newSql = new StringBuilder();
            int txtIndex = 0;

            Matcher matcher = NAMED_QUERY_PATTERN.matcher(sql);
            while (matcher.find()) {
                newSql.append(sql.substring(txtIndex, matcher.start())).append('?');
                String indexStr = matcher.group(1);
                int index = (indexStr == null || indexStr.length() == 0) ? 0 : new Integer(indexStr) - 1;
                String prop = matcher.group(2);
                indexPropList.add(new Tuple(new Object[]{index, prop.length() == 0 ? "<this>" : prop}));
                txtIndex = matcher.end();
            }
            newSql.append(sql.substring(txtIndex)); // append ending SQL after last param.
            return newSql.toString();
        }
    }
}
