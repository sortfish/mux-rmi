/*
 * MIT License
 *
 * Copyright (c) 2017 Rene Andersen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package muxrmi;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.rmi.NotBoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import easysettings.ConfigurationSettings;
import muxrmi.Protocol.ClassRef;
import muxrmi.io.CommunicationChannel;
import muxrmi.io.CommunicationChannel.Factory;


/**
 * Implementation of a Mux-RMI remote client. A remote client can create and dispose proxy stubs 
 * for remote APIs on a specific socket endpoint.
 * <p/>
 * A remote client instance controls the lifetime of all proxy stub instances created by it,
 * and will close any outstanding instances when it is itself closed.
 * <ul>
 * <li>Proxy stubs are created by calling the {@link #connect(Class)} method.</li>
 * <li>Proxy stubs are disposed by calling the {@link #dispose(Object)} method.</li>
 * </ul>
 * @author Rene Andersen
 */
public class RemoteClient implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(RemoteClient.class);

  private final ProxyObjects proxyObjects = new ProxyObjects();

  private final Connections connections = new Connections();
  private final Factory commFactory;
  private final Settings settings;
  private final KeepAlive keepAlive;

  /**
   * Configuration settings for a remote client.
   */
  public static class Settings extends ConfigurationSettings {
    final StatisticsProvider statistics; 
    
    /** The keep-alive settings */
    public final KeepAlive.Settings keepAliveSettings;
    
    /** The lifetime of idle connections in the connection pool, in seconds */
    public final IntegerValue idleLifetime = new IntegerValue("idle-lifetime", 60); // seconds

    private Settings(final Reader reader, final KeepAlive.Settings keepAliveSettings, final StatisticsProvider statistics) {
      super(reader);
      reload();
      
      this.keepAliveSettings = keepAliveSettings;
      this.statistics = statistics;
    }
    
    /**
     * Use default settings and the specified keep-alive settings.
     * @param keepAliveSettings the keep-alive settings.
     * @param statistics the statistics to use.
     */
    public Settings(final KeepAlive.Settings keepAliveSettings, final StatisticsProvider statistics) {
      this(ConfigurationSettings.DEFAULTS, keepAliveSettings, statistics);
    }
    
    /**
     * Read settings from the specified configuration settings reader.
     * @param reader the reader to read configuration settings from.
     * @param statistics the statistics to use.
     */
    public Settings(final Reader reader, final StatisticsProvider statistics) {
      this(reader, new KeepAlive.Settings(reader), statistics);
    }
    
    /** {@inheritDoc} */
    @Override
    public String toString() {
      return String.format("Settings [keepAliveSettings=%s]", keepAliveSettings);
    }
  }

  /**
   * Create a new remote client instance with the specified communication channel factory,
   * class loader and configuration settings.
   * @param commFactory the communication channel factory.
   * @param settings the configuration settings for the remote client.
   */
  public RemoteClient(final CommunicationChannel.Factory commFactory, final Settings settings) {
    this.commFactory = commFactory;
    this.settings = settings;
    this.keepAlive = new KeepAlive(settings.keepAliveSettings, settings.statistics);
  }

  /**
   * Create a proxy stub for a remote API class which can be used to invoke methods on the remote API.
   * @param apiClass The class type of the remote API. This must be an interface type.
   * @return a proxy stub for the remote API.
   * @throws NotBoundException if the remote API is not registered on the server.
   * @throws IllegalArgumentException if the API class type is not an interface.
   * @throws Exception if an exception is thrown while performing this request.
   */
  public <API> API connect(final Class<API> apiClass)
      throws NotBoundException, IllegalArgumentException, Exception {
    final Connection connection = connections.getConnection();
    try {
      return connection.connect(apiClass);
    } finally {
      connections.putConnection(connection);
    }
  }

  /**
   * Close this remote client instance are release all resources held by it, including all network connections.
   * Proxy stubs create by this remote client will no longer be able to invoke the remote API. 
   */
  @Override
  public void close() {
    keepAlive.close();
    connections.close();
  }

  /**
   * Dispose a proxy stub for the remote API.
   * @param api the proxy stub.
   * @return {@code true} iff the specified API was a remote proxy stub created by this remote client, {@code false} otherwise.
   */
  public boolean dispose(final Object api) {
    return proxyObjects.remove(api);
  }

  @Override
  public String toString() {
    return String.format("RemoteClient [settings=%s]", settings);
  }
  
  /**
   * @return the keep-alive handler of this remote client.
   */
  KeepAlive getKeepAlive() {
    return keepAlive;
  }

  /**
   * Return the collection of pooled {@link Connection} objects for this remote client.
   * <p/>
   * The returned collection is unmodifiable and backed by the internal collection of 
   * connections and will change dynamically as new connections are added and idle 
   * connections are removed. The underlying collection is thread-safe but does not
   * guarantee that changes applied after iterator creation is reflected. Therefore 
   * it is possible that the collection will contain connections that are already 
   * closed and has been removed from the underlying collection.
   * @return an unmodifiable collection of {@link Connection} objects.
   */
  Collection<Connection> getConnections() {
    return Collections.unmodifiableCollection(connections.getAll());
  }

  /**
   * Get a collection with the current set of remote proxy stub objects, that is: 
   * object returned by {@link #connect(Class)} which have not been disposed by 
   * {@link #dispose(Object)} and which have not been collected by the GC.
   * <p/>
   * The returned collection is unmodifiable but <em>not</em> backed by the internal 
   * set of remote proxy stubs, since that set only contains weak references in order 
   * to not interfere with the reachability of the objects. The internal set might
   * therefore also contain disposed references, whereas the returned collection will 
   * only contain live objects (although they might in the future be rendered invalid 
   * if {@link #dispose(Object)} is called on them).
   * @return an unmodifiable collection of remote proxy stub objects.
   */
  Collection<?> getProxies() {
    final Collection<Object> res = new ArrayList<>();
    for (final ShallowRef<?> ref : proxyObjects) {
      final Object api = ref.get();
      if (api != null) {
        res.add(api);
      }
    }
    return Collections.unmodifiableCollection(res);
  }

  /**
   * Container class for shallow references to the current set of proxy stubs to remote objects. 
   */
  static final class ProxyObjects implements Iterable<ShallowRef<?>> {
    private final Set<ShallowRef<?>> refSet = new HashSet<>();

    /** {@inheritDoc} */
    @Override
    public Iterator<ShallowRef<?>> iterator() {
      return refSet.iterator();
    }

    synchronized void add(final Object api) {
      removeDisposed();
      refSet.add(new ShallowRef<>(api));
    }

    synchronized boolean remove(final Object api) {
      return refSet.remove(new ShallowRef<>(api));
    }

    synchronized void removeDisposed() {
      // Done via Iterator#remove() as equality of disposed references is indeterminable.
      final Iterator<ShallowRef<?>> it = refSet.iterator();
      while (it.hasNext()) {
        if (it.next().ref.get() == null) {
          it.remove();
        }
      }
    }
  }

  /**
   * A {@link WeakReference} with shallow implementations of hashCode and equals implemented
   * via the identity of the referenced object, unlike {@link WeakReference} itself which uses
   * the identity of itself (via the default implementations inherited from Object).
   * @param <T> the type of the shallow reference
   */
  static final class ShallowRef<T> {
    private final WeakReference<T> ref;
    private final int identityHashCode;

    ShallowRef(final T referent) {
      ref = new WeakReference<>(referent);

      // The identity hash code is persisted as it must be immutable, even though the referent is disposed.
      this.identityHashCode = System.identityHashCode(referent);
    }

    final T get() {
      return ref.get();
    }

    @Override
    public String toString() {
      return "Ref [" + ref.get() + "]";
    }

    @Override
    public int hashCode() {
      return identityHashCode;
    }

    @Override
    public boolean equals(final Object other) {
      if (!other.getClass().equals(ShallowRef.class)) {
        return false;
      }
      final Object otherRef = ((ShallowRef<?>) other).ref.get();
      final T thisRef = ref.get();
      if (otherRef == null || thisRef == null)
        return false;
      return otherRef == thisRef;
    }
  }

  /**
   * A set of open, idle connections to the remote server which can be used to perform
   * remote method invocations. If there are no idle connections available when a
   * connection is requested a new connection is created and returned.
   */
  private final class Connections implements Closeable {
    private final Set<Connection> connections = Collections.synchronizedSet(new HashSet<Connection>());
    private final NavigableMap<Long, Connection> connectionPool = new ConcurrentSkipListMap<>();

    private final ThreadFactory threadFactory = new ThreadFactoryBuilder().factoryNamePrefix(getClass().getCanonicalName()).build();
    private final ScheduledExecutorService connectionPoolExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);

    private volatile boolean closed = false;
    
    /**
     * Clean-up task for the pool of idle connections. This task will run periodically
     * and close connections which have been idle for longer than {@link #connectionPoolLifetime}
     * seconds.
     */
    private class ConnectionPoolCleanup implements Runnable, Closeable {
      private static final int CLEANUP_INTERVAL = 1; // 1 second
      
      private final int connectionPoolLifetime = settings.idleLifetime.get();
      private final ScheduledFuture<?> scheduleFuture;

      ConnectionPoolCleanup() {
        this.scheduleFuture = connectionPoolExecutor.scheduleAtFixedRate(this, CLEANUP_INTERVAL, CLEANUP_INTERVAL, SECONDS);
        logger.debug("ConnectionPoolCleanup: scheduled to run every {}s with a pool lifetime of {}s", CLEANUP_INTERVAL, connectionPoolLifetime);
      }

      @Override
      public void run() {
        final long now = System.currentTimeMillis();
        final long deleteOlderThan = now - SECONDS.toMillis(connectionPoolLifetime);
        synchronized (connectionPool) {
          final Map<Long, Connection> connectionsToRemove = connectionPool.headMap(deleteOlderThan, true);
          for (final Map.Entry<Long, Connection> entry : connectionsToRemove.entrySet()) {
            if (logger.isDebugEnabled()) {
              logger.debug("ConnectionPoolCleanup: removing connection at {}: {} (last used at {})",
                           now, entry.getValue(), entry.getKey());
            }
            closeConnection(entry.getValue());
          }
          connectionsToRemove.clear();
          logger.trace("ConnectionPoolCleanup: active connections ({}): {}", connectionPool.size(), connectionPool);
        }
      }

      /** {@inheritDoc} */
      @Override
      public void close() {
        this.scheduleFuture.cancel(false);
      }
    };
    private ConnectionPoolCleanup connectionPoolCleanup = null;

    Collection<Connection> getAll() {
      return connections;
    }

    Connection createConnection() throws IOException {
      final CommunicationChannel comm = commFactory.create();

      final Connection connection = new Connection(new Protocol.Client(comm));
      connections.add(connection);
      return connection;
    }

    void closeConnection(final Connection connection) {
      if (connection != null) {
        connections.remove(connection);
        connection.close();
      }
    }

    Connection getConnection() throws IOException {
      if (closed)
        throw new IOException("Closed");
        
      final Entry<Long, Connection> entry = connectionPool.pollFirstEntry();
      if (entry != null) {
        return entry.getValue();
      }
      return createConnection();
    }

    void putConnection(final Connection connection) {
      if (closed)
        closeConnection(connection);
      
      long now = System.currentTimeMillis();
      synchronized (connectionPool) {
        while (connectionPool.containsKey(now)) {
          ++now;
        }
        connectionPool.put(now, connection);

        if (connectionPoolCleanup == null) {
          connectionPoolCleanup = new ConnectionPoolCleanup();
        }
      }
    }

    /**
     * Close all connections held in the connection pool. A subsequent attempt to obtain a
     * connection from the pool will fail.
     */
    @Override
    public void close() {
      closed = true;
      synchronized (connectionPool) {
        try {
          for (final Connection connection : connectionPool.values()) {
            closeConnection(connection);
          }
        } finally {
          connectionPool.clear();
          if (connectionPoolCleanup != null) {
            connectionPoolCleanup.close();
            connectionPoolCleanup = null;
          }
        }
      }
    }
  }

  /**
   * This object represents the client-side of an open remote connection, as represented by
   * an instance of {@link Protocol}. This class handles the client-specific management and
   * associated with a remote connection, such as starting and stopping the keep-alive task
   * that monitors the connection.
   */
  final class Connection implements AutoCloseable {
    final Protocol protocol;

    Connection(final Protocol protocol) {
      this.protocol = protocol;
      keepAlive.start(protocol);

      if (logger.isDebugEnabled()) logger.debug("Connection created: " + this);
    }

    /**
     * Connect to a remote API by creating a local proxy object for the remote API class.
     * @param apiClass the remote class to connect to.
     * @return the new proxy object for the remote API.
     * @throws NotBoundException if the class was not registered remotely.
     * @throws Exception any exception thrown during the remote call.
     */
    <API> API connect(final Class<API> apiClass) throws NotBoundException, Exception {
      protocol.bind(apiClass);
      final API api = protocol.createProxy(apiClass, new ClientInvocationHandler(apiClass));
      proxyObjects.add(api);
      return api;
    }

    /**
     * Close this connection by stopping its keep-alive task and closing its protocol instance.
     */
    @Override
    public void close() {
      keepAlive.stop(protocol);
      protocol.close();
    }

    @Override
    protected void finalize() {
      if (protocol.isConnected()) {
        close();
      }
    }

    @Override
    public String toString() {
      return "RemoteClient.Connection [" + protocol + "]";
    }
  }

  /**
   * A client-side remote invocation handler. When a remote method is invoked a
   * {@link Connection} is obtained from the set of open connections, and the
   * method invocation is performed on the client protocol object in that
   * connection object.
   */
  class ClientInvocationHandler extends RemoteInvocationHandler {
    /**
     * Create a new client-side remote invocation handler for the specified 
     * remote class type.
     * @param classType the remote interface class-type.
     */
    protected ClientInvocationHandler(final Class<?> classType) {
      super(classType);
    }
  
    @Override
    protected Object invokeRemote(final Method method,
                                  final Object[] args) throws InvocationTargetException, Exception {
      final Connection connection = connections.getConnection();
      try {
        return connection.protocol.invokeRemote(ClassRef.forClass(getClassType()), method, args);
      } finally {
        connections.putConnection(connection);
      }
    }
  }
}
