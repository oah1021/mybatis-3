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
package org.apache.ibatis.datasource.pooled;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * This is a simple, synchronous, thread-safe database connection pool.
 *
 * @author Clinton Begin
 */
public class PooledDataSource implements DataSource {

  private static final Log log = LogFactory.getLog(PooledDataSource.class);

  /**
   * 用来表示连接池的状态</br>
   * 这里将当前数据源实例作为参数赋予池状态 形成一个 不变的实例
   */
  private final PoolState state = new PoolState(this);

  private final UnpooledDataSource dataSource;

  // OPTIONAL CONFIGURATION FIELDS
  /**
   * 连接池最大活动连接数量
   */
  protected int poolMaximumActiveConnections = 10;
  /**
   * 连接池最大空闲连接数量
   */
  protected int poolMaximumIdleConnections = 5;
  /**
   * 连接池最大检出时间
   */
  protected int poolMaximumCheckoutTime = 20000;
  /**
   * 池等待时间
   * 当需要从池中获取一个连接时，如果空闲连接数量为0，而活动连接的数量也达到了最大值，那么就针对那个最早取出的连接进行检查验证
   * 如果验证成功（即在上面poolMaximumCheckoutTime限定的时间内验证通过），说明这个连接还处于使用状态，这时取出操作暂停，线程等待限定时间
   */
  protected int poolTimeToWait = 20000;
  /**
   * 这是一个关于坏连接容忍度的底层设置，作用于每一个尝试从缓存池获取连接的线程.
   * 如果这个线程获取到的是一个坏的连接，那么这个数据源允许这个线程尝试重新获取一个新的连接，
   * 但是这个重新尝试的次数不应该超过 poolMaximumIdleConnections 与 poolMaximumLocalBadConnectionTolerance 之和。
   */
  protected int poolMaximumLocalBadConnectionTolerance = 3;
  /**
   * 发送到数据库的侦测查询，用来检验连接是否正常工作并准备接受请求。
   */
  protected String poolPingQuery = "NO PING QUERY SET";
  /**
   * 是否打开侦测查询功能 默认 false
   */
  protected boolean poolPingEnabled;
  /**
   * 如果一个连接在限定的时间内一直未被使用，那么就要对该连接进行验证，以确定这个连接是否处于可用状态（即进行侦测查询）
   */
  protected int poolPingConnectionsNotUsedFor;
  /**
   * 连接的类型编码，这个类型编码在创建池型数据源实例的时候会被组装，
   * 他的组装需要从数据源中获取连接的url、username、password三个值，将其按顺序组合在一起，这个类型编码可用于区别连接种类。
   */
  private int expectedConnectionTypeCode;

  private final Lock lock = new ReentrantLock();
  private final Condition condition = lock.newCondition();

  public PooledDataSource() {
    dataSource = new UnpooledDataSource();
  }

  public PooledDataSource(UnpooledDataSource dataSource) {
    this.dataSource = dataSource;
  }

  public PooledDataSource(String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(),
        dataSource.getPassword());
  }

  public PooledDataSource(String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(),
        dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(),
        dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, driverProperties);
    // 计算 expectedConnectionTypeCode 值
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(),
        dataSource.getPassword());
  }

  @Override
  public Connection getConnection() throws SQLException {
    // 返回代理的 Connection 对象。这样，每次对数据库的操作，才能被 PooledConnection 的 invoke 方法拦截
    return popConnection(dataSource.getUsername(), dataSource.getPassword()).getProxyConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return popConnection(username, password).getProxyConnection();
  }

  @Override
  public void setLoginTimeout(int loginTimeout) {
    DriverManager.setLoginTimeout(loginTimeout);
  }

  @Override
  public int getLoginTimeout() {
    return DriverManager.getLoginTimeout();
  }

  @Override
  public void setLogWriter(PrintWriter logWriter) {
    DriverManager.setLogWriter(logWriter);
  }

  @Override
  public PrintWriter getLogWriter() {
    return DriverManager.getLogWriter();
  }

  public void setDriver(String driver) {
    dataSource.setDriver(driver);
    forceCloseAll();
  }

  public void setUrl(String url) {
    dataSource.setUrl(url);
    forceCloseAll();
  }

  public void setUsername(String username) {
    dataSource.setUsername(username);
    forceCloseAll();
  }

  public void setPassword(String password) {
    dataSource.setPassword(password);
    forceCloseAll();
  }

  public void setDefaultAutoCommit(boolean defaultAutoCommit) {
    dataSource.setAutoCommit(defaultAutoCommit);
    forceCloseAll();
  }

  public void setDefaultTransactionIsolationLevel(Integer defaultTransactionIsolationLevel) {
    dataSource.setDefaultTransactionIsolationLevel(defaultTransactionIsolationLevel);
    forceCloseAll();
  }

  public void setDriverProperties(Properties driverProps) {
    dataSource.setDriverProperties(driverProps);
    forceCloseAll();
  }

  /**
   * Sets the default network timeout value to wait for the database operation to complete. See
   * {@link Connection#setNetworkTimeout(java.util.concurrent.Executor, int)}
   *
   * @param milliseconds
   *          The time in milliseconds to wait for the database operation to complete.
   *
   * @since 3.5.2
   */
  public void setDefaultNetworkTimeout(Integer milliseconds) {
    dataSource.setDefaultNetworkTimeout(milliseconds);
    forceCloseAll();
  }

  /**
   * The maximum number of active connections.
   *
   * @param poolMaximumActiveConnections
   *          The maximum number of active connections
   */
  public void setPoolMaximumActiveConnections(int poolMaximumActiveConnections) {
    this.poolMaximumActiveConnections = poolMaximumActiveConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of idle connections.
   *
   * @param poolMaximumIdleConnections
   *          The maximum number of idle connections
   */
  public void setPoolMaximumIdleConnections(int poolMaximumIdleConnections) {
    this.poolMaximumIdleConnections = poolMaximumIdleConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of tolerance for bad connection happens in one thread which are applying for new
   * {@link PooledConnection}.
   *
   * @param poolMaximumLocalBadConnectionTolerance
   *          max tolerance for bad connection happens in one thread
   *
   * @since 3.4.5
   */
  public void setPoolMaximumLocalBadConnectionTolerance(int poolMaximumLocalBadConnectionTolerance) {
    this.poolMaximumLocalBadConnectionTolerance = poolMaximumLocalBadConnectionTolerance;
  }

  /**
   * The maximum time a connection can be used before it *may* be given away again.
   *
   * @param poolMaximumCheckoutTime
   *          The maximum time
   */
  public void setPoolMaximumCheckoutTime(int poolMaximumCheckoutTime) {
    this.poolMaximumCheckoutTime = poolMaximumCheckoutTime;
    forceCloseAll();
  }

  /**
   * The time to wait before retrying to get a connection.
   *
   * @param poolTimeToWait
   *          The time to wait
   */
  public void setPoolTimeToWait(int poolTimeToWait) {
    this.poolTimeToWait = poolTimeToWait;
    forceCloseAll();
  }

  /**
   * The query to be used to check a connection.
   *
   * @param poolPingQuery
   *          The query
   */
  public void setPoolPingQuery(String poolPingQuery) {
    this.poolPingQuery = poolPingQuery;
    forceCloseAll();
  }

  /**
   * Determines if the ping query should be used.
   *
   * @param poolPingEnabled
   *          True if we need to check a connection before using it
   */
  public void setPoolPingEnabled(boolean poolPingEnabled) {
    this.poolPingEnabled = poolPingEnabled;
    forceCloseAll();
  }

  /**
   * If a connection has not been used in this many milliseconds, ping the database to make sure the connection is still
   * good.
   *
   * @param milliseconds
   *          the number of milliseconds of inactivity that will trigger a ping
   */
  public void setPoolPingConnectionsNotUsedFor(int milliseconds) {
    this.poolPingConnectionsNotUsedFor = milliseconds;
    forceCloseAll();
  }

  public String getDriver() {
    return dataSource.getDriver();
  }

  public String getUrl() {
    return dataSource.getUrl();
  }

  public String getUsername() {
    return dataSource.getUsername();
  }

  public String getPassword() {
    return dataSource.getPassword();
  }

  public boolean isAutoCommit() {
    return dataSource.isAutoCommit();
  }

  public Integer getDefaultTransactionIsolationLevel() {
    return dataSource.getDefaultTransactionIsolationLevel();
  }

  public Properties getDriverProperties() {
    return dataSource.getDriverProperties();
  }

  /**
   * Gets the default network timeout.
   *
   * @return the default network timeout
   *
   * @since 3.5.2
   */
  public Integer getDefaultNetworkTimeout() {
    return dataSource.getDefaultNetworkTimeout();
  }

  public int getPoolMaximumActiveConnections() {
    return poolMaximumActiveConnections;
  }

  public int getPoolMaximumIdleConnections() {
    return poolMaximumIdleConnections;
  }

  public int getPoolMaximumLocalBadConnectionTolerance() {
    return poolMaximumLocalBadConnectionTolerance;
  }

  public int getPoolMaximumCheckoutTime() {
    return poolMaximumCheckoutTime;
  }

  public int getPoolTimeToWait() {
    return poolTimeToWait;
  }

  public String getPoolPingQuery() {
    return poolPingQuery;
  }

  public boolean isPoolPingEnabled() {
    return poolPingEnabled;
  }

  public int getPoolPingConnectionsNotUsedFor() {
    return poolPingConnectionsNotUsedFor;
  }

  /**
   * 关闭池中的所有活动和空闲连接。
   */
  public void forceCloseAll() {
    lock.lock();
    try {
      // 计算 expectedConnectionTypeCode
      expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(),
          dataSource.getPassword());
      // 遍历活动连接
      for (int i = state.activeConnections.size(); i > 0; i--) {
        try {
          // 移除获取到的连接
          PooledConnection conn = state.activeConnections.remove(i - 1);
          // 设置连接为无效
          conn.invalidate();
          // 获取真实连接
          Connection realConn = conn.getRealConnection();
          // 非自动提交，回滚
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          // 关闭真实连接
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
      // 遍历空闲连接
      for (int i = state.idleConnections.size(); i > 0; i--) {
        try {
          // 移除获取到的连接
          PooledConnection conn = state.idleConnections.remove(i - 1);
          // 设置连接为无效
          conn.invalidate();
          // 获取真实连接
          Connection realConn = conn.getRealConnection();
          // 非自动提交，回滚
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          // 关闭真实连接
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
    } finally {
      // 释放线程锁
      lock.unlock();
    }
    if (log.isDebugEnabled()) {
      log.debug("PooledDataSource forcefully closed/removed all connections.");
    }
  }

  public PoolState getPoolState() {
    return state;
  }

  private int assembleConnectionTypeCode(String url, String username, String password) {
    return ("" + url + username + password).hashCode();
  }

  /**
   * 将使用完的连接，添加回连接池中
   * @param conn
   * @throws SQLException
   */
  protected void pushConnection(PooledConnection conn) throws SQLException {
    // 加线程锁
    lock.lock();
    try {
      // 从活动连接中删除 指定连接
      state.activeConnections.remove(conn);
      // 验证连接的可用性
      if (conn.isValid()) { // 有效
        // 如果空闲连接 < 连接池最大空闲连接数量 and 和当前连接池的标识匹配
        if (state.idleConnections.size() < poolMaximumIdleConnections
            && conn.getConnectionTypeCode() == expectedConnectionTypeCode) {
          // 统计连接使用时长
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          // 回滚事务，避免使用方未提交或者回滚事务
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          // 创建新的 Connection
          PooledConnection newConn = new PooledConnection(conn.getRealConnection(), this);
          // 添加到空闲连接集合
          state.idleConnections.add(newConn);
          newConn.setCreatedTimestamp(conn.getCreatedTimestamp());
          newConn.setLastUsedTimestamp(conn.getLastUsedTimestamp());
          // 设置原连接失效  避免使用方还在使用 conn，通过将它设置为失效，再次调用会抛出异常
          conn.invalidate();
          if (log.isDebugEnabled()) {
            log.debug("Returned connection " + newConn.getRealHashCode() + " to pool.");
          }
          // 唤醒正在等待连接的线程
          condition.signal();
        } else {
          // 统计连接使用时长
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          // 关闭真正的数据库连接
          conn.getRealConnection().close();
          if (log.isDebugEnabled()) {
            log.debug("Closed connection " + conn.getRealHashCode() + ".");
          }
          // 设置原连接为失效
          conn.invalidate();
        }
      } else { // 失效
        if (log.isDebugEnabled()) {
          log.debug("A bad connection (" + conn.getRealHashCode()
              + ") attempted to return to the pool, discarding connection.");
        }
        // 统计获取到坏的连接的次数
        state.badConnectionCount++;
      }
    } finally {
      // 释放线程锁
      lock.unlock();
    }
  }

  private PooledConnection popConnection(String username, String password) throws SQLException {
    // 这是一个标记 获取连接时是否进行了等待
    boolean countedWait = false;
    // 最终获取到的连接对象
    PooledConnection conn = null;
    // 记录当前时间
    long t = System.currentTimeMillis();
    // 记录当前方法获取到坏连接的次数
    int localBadConnectionCount = 0;
    // 循环获取可用的 Connection 连接
    while (conn == null) {
      // 加线程锁
      lock.lock();
      try {
        // 空闲连接非空
        if (!state.idleConnections.isEmpty()) { // 😊 非空
          // Pool has available connection
          // 通过移除的方式获得首个空闲的连接
          conn = state.idleConnections.remove(0);
          // 如果开启了debug级别的日志开关，打印一条日志
          if (log.isDebugEnabled()) {
            log.debug("Checked out connection " + conn.getRealHashCode() + " from pool.");
          }
        } else if (state.activeConnections.size() < poolMaximumActiveConnections) { // 😊 如果没有空闲连接，判断活动连接是否小于 连接池最大活动连接数量
          // Pool does not have available connection and can create a new connection
          // 创建一个新的连接
          conn = new PooledConnection(dataSource.getConnection(), this);
          if (log.isDebugEnabled()) {
            log.debug("Created connection " + conn.getRealHashCode() + ".");
          }
        } else { // 😊 到了这里，说明活动连接满了，无法创建新的连接
          // Cannot create new connection
          // 获取第一个活动连接
          PooledConnection oldestActiveConnection = state.activeConnections.get(0);
          // 获取签出此连接的时间，用来判断该连接是否超时
          long longestCheckoutTime = oldestActiveConnection.getCheckoutTime();
          if (longestCheckoutTime > poolMaximumCheckoutTime) { // 🤷‍♂️ 检查到超时
            // Can claim overdue connection
            // 过期的连接数量加 1
            state.claimedOverdueConnectionCount++;
            // 连接超时的累计时间
            state.accumulatedCheckoutTimeOfOverdueConnections += longestCheckoutTime;
            state.accumulatedCheckoutTime += longestCheckoutTime;
            // 从活动连接中删除这个 连接
            state.activeConnections.remove(oldestActiveConnection);
            // 如果此连接 不是自动提交
            if (!oldestActiveConnection.getRealConnection().getAutoCommit()) {
              try {
                // 事物进行回滚
                oldestActiveConnection.getRealConnection().rollback();
              } catch (SQLException e) {
                /*
                 * Just log a message for debug and continue to execute the following statement like nothing happened.
                 * Wrap the bad connection with a new PooledConnection, this will help to not interrupt current
                 * executing thread and give current thread a chance to join the next competition for another valid/good
                 * database connection. At the end of this loop, bad {@link @conn} will be set as null.
                 */
                log.debug("Bad connection. Could not roll back");
              }
            }
            // 创建新的 Connection
            conn = new PooledConnection(oldestActiveConnection.getRealConnection(), this);
            conn.setCreatedTimestamp(oldestActiveConnection.getCreatedTimestamp());
            conn.setLastUsedTimestamp(oldestActiveConnection.getLastUsedTimestamp());
            // 使 oldestActiveConnection 无效
            oldestActiveConnection.invalidate();
            if (log.isDebugEnabled()) {
              log.debug("Claimed overdue connection " + conn.getRealHashCode() + ".");
            }
          } else { // 🤷‍♂️ 没有超时
            // Must wait
            try {
              if (!countedWait) {
                // 对等待连接进行统计 通过countedWait 进行标识，以达到在这个循环中，只记录一次
                state.hadToWaitCount++;
                countedWait = true;
              }
              if (log.isDebugEnabled()) {
                log.debug("Waiting as long as " + poolTimeToWait + " milliseconds for connection.");
              }
              // 记录当前时间
              long wt = System.currentTimeMillis();
              // 等待，直到超时，或 pingConnection 方法中归还连接时的唤醒
              condition.await(poolTimeToWait, TimeUnit.MILLISECONDS);
              // 计算累计等待时间
              state.accumulatedWaitTime += System.currentTimeMillis() - wt;
            } catch (InterruptedException e) {
              // set interrupt flag
              // 如果抛出异常，则对此线程打上中断标记
              Thread.currentThread().interrupt();
              break;
            }
          }
        }
        if (conn != null) {
          // ping to server and check the connection is valid or not
          // 查看连接是否可用
          if (conn.isValid()) {
            // 如果不是自动提交
            if (!conn.getRealConnection().getAutoCommit()) {
              // 回滚所做的更改
              conn.getRealConnection().rollback();
            }
            // 设置获取到的连接 的属性
            conn.setConnectionTypeCode(assembleConnectionTypeCode(dataSource.getUrl(), username, password));
            conn.setCheckoutTimestamp(System.currentTimeMillis());
            conn.setLastUsedTimestamp(System.currentTimeMillis());
            // 将获取到的连接 添加到活动连接
            state.activeConnections.add(conn);
            // 对获取成功连接进行计数
            state.requestCount++;
            state.accumulatedRequestTime += System.currentTimeMillis() - t;
          } else { // 如果连接不可用
            if (log.isDebugEnabled()) {
              log.debug("A bad connection (" + conn.getRealHashCode()
                  + ") was returned from the pool, getting another connection.");
            }
            // 统计获取到坏的连接 的次数
            state.badConnectionCount++;
            // 统计获取到坏的连接 的次数 【本方法】
            localBadConnectionCount++;
            // 将 conn 置空，那么可以继续获取
            conn = null;
            // 如果超出最大次，抛出 SQLException 异常
            if (localBadConnectionCount > poolMaximumIdleConnections + poolMaximumLocalBadConnectionTolerance) {
              if (log.isDebugEnabled()) {
                log.debug("PooledDataSource: Could not get a good connection to the database.");
              }
              throw new SQLException("PooledDataSource: Could not get a good connection to the database.");
            }
          }
        }
      } finally {
        // 释放线程锁
        lock.unlock();
      }

    }
    // 获取不到连接，抛出异常
    if (conn == null) {
      if (log.isDebugEnabled()) {
        log.debug("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
      }
      throw new SQLException(
          "PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
    }

    return conn;
  }

  /**
   * Method to check to see if a connection is still usable
   * 检查连接是否可用
   * @param conn
   *          - the connection to check
   *
   * @return True if the connection is still usable
   */
  protected boolean pingConnection(PooledConnection conn) {
    // 记录是否ping成功
    boolean result = true;

    try {
      // 判断真实的连接是否已经关闭，如果已经关闭，就意味着 ping 一定是失败的
      result = !conn.getRealConnection().isClosed();
    } catch (SQLException e) {
      if (log.isDebugEnabled()) {
        log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
      }
      result = false;
    }
    // 判断是否长时间未使用，若是，才需要ping
    if (result && poolPingEnabled && poolPingConnectionsNotUsedFor >= 0
        && conn.getTimeElapsedSinceLastUse() > poolPingConnectionsNotUsedFor) {
      try {
        if (log.isDebugEnabled()) {
          log.debug("Testing connection " + conn.getRealHashCode() + " ...");
        }
        // 获取真实的连接
        Connection realConn = conn.getRealConnection();
        // 通过执行 poolPingQuery 语句来发起 ping
        try (Statement statement = realConn.createStatement()) {
          statement.executeQuery(poolPingQuery).close();
        }
        if (!realConn.getAutoCommit()) {
          realConn.rollback();
        }
        // 标记为成功
        result = true;
        if (log.isDebugEnabled()) {
          log.debug("Connection " + conn.getRealHashCode() + " is GOOD!");
        }
      } catch (Exception e) {
        log.warn("Execution of ping query '" + poolPingQuery + "' failed: " + e.getMessage());
        try {
          // 关闭数据库真实的连接
          conn.getRealConnection().close();
        } catch (Exception e2) {
          // ignore
        }
        // 标记为 失败
        result = false;
        if (log.isDebugEnabled()) {
          log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
        }
      }
    }
    return result;
  }

  /**
   * Unwraps a pooled connection to get to the 'real' connection
   * 获取真实连接
   * @param conn
   *          - the pooled connection to unwrap
   *
   * @return The 'real' connection
   */
  public static Connection unwrapConnection(Connection conn) {
    // 如果传入的是被代理的连接
    if (Proxy.isProxyClass(conn.getClass())) {
      InvocationHandler handler = Proxy.getInvocationHandler(conn);
      if (handler instanceof PooledConnection) {
        // 获取真实连接
        return ((PooledConnection) handler).getRealConnection();
      }
    }
    return conn;
  }

  @Override
  protected void finalize() throws Throwable {
    // 关闭所有连接
    forceCloseAll();
    // 执行对象销毁
    super.finalize();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLException(getClass().getName() + " is not a wrapper.");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
  }

}
