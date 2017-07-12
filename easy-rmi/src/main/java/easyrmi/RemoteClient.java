package easyrmi;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketAddress;
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

import javax.net.SocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import easyrmi.Protocol.ClassRef;


/**
 * Implementation of a remote client. A remote client can create and dispose proxy stubs for remote APIs on a specific socket
 * endpoint.
 * <p/>
 * A remote client instance controls the lifetime of all proxy stub instances created by it, and will close any outstanding
 * instances when it is itself closed.
 * <ul>
 * <li>Proxy stubs are created by calling the {@link #connect(Class)} method.</li>
 * <li>Proxy stubs are disposed by calling the {@link #dispose(Object)} method.</li>
 * </ul>
 * @author ReneAndersen
 */
public class RemoteClient implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(RemoteClient.class);

  private final ProxyObjects proxyObjects = new ProxyObjects();

  private final Connections connections = new Connections();
  private final ClassLoader classLoader;
  private final Settings settings;
  private final KeepAlive keepAlive;

  /**
   * Configuration settings for a remote client.
   */
  public static class Settings {
    final SocketFactory socketFactory;
    final SocketAddress endpoint;
    final KeepAlive.Settings keepAlive;

    /**
     * Remote client settings with default keep-alive settings.
     * @param socketFactory a socket factory for connecting to the remote server.
     * @param endpoint the socket address endpoint to connect to.
     */
    public Settings(final SocketFactory socketFactory, final SocketAddress endpoint) {
      this(socketFactory, endpoint, new KeepAlive.Settings());
    }

    /**
     * @param socketFactory a socket factory for connecting to the remote server.
     * @param endpoint the socket address endpoint to connect to.
     * @param keepAliveSettings the keep-alive settings.
     */
    public Settings(final SocketFactory socketFactory, final SocketAddress endpoint, final KeepAlive.Settings keepAliveSettings) {
      this.socketFactory = socketFactory;
      this.endpoint = endpoint;
      this.keepAlive = keepAliveSettings;
    }
  }

  /**
   * @param classLoader a class loader for the objects read on the remote protocol.
   * @param settings the configuration settings for the remote client.
   */
  public RemoteClient(final ClassLoader classLoader, final Settings settings) {
    this.classLoader = classLoader;
    this.settings = settings;
    this.keepAlive = new KeepAlive(settings.keepAlive);
  }

  /**
   * Create a proxy stub for a remote API class.
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

  /** {@inheritDoc} */
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

  /**
   * @return the keep-alive handler of this remote client.
   */
  KeepAlive getKeepAlive() {
    return keepAlive;
  }

  /**
   * Return the collection of pooled {@link Connection} objects for this remote client.
   * <p/>
   * The returned collection is unmodifiable and backed by the internal collection of connections and will change dynamically
   * as new connections are added and idle connections are removed. The underlying collection is thread-safe but does not
   * guarantee that changes applied after iterator creation is reflected. Therefore it is possible that the collection will
   * contain connections that are already closed and has been removed from the underlying collection.
   * @return an unmodifiable collection of {@link Connection} objects.
   */
  Collection<Connection> getConnections() {
    return Collections.unmodifiableCollection(connections.getAll());
  }

  /**
   * Get an  collection with the current set of remote proxy stub objects, that is: object returned by {@link #connect(Class)}
   * which have not been disposed by {@link #dispose(Object)} and which have not been collected by the GC.
   * <p/>
   * The returned collection is unmodifiable but <em>not</em> backed by the internal set of remote proxy stubs, since that set
   * only contains weak references in order to not interfere with the reachability of the objects. The internal set might
   * therefore also contain disposed references, whereas the returned collection will only contain live objects (although they
   * might in the future be rendered invalid if {@link #dispose(Object)} is called on them).
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
      // Done via Iterator#remove() as equality of disposed references is undeterminable.
      final Iterator<ShallowRef<?>> it = refSet.iterator();
      while (it.hasNext()) {
        if (it.next().ref.get() == null) {
          it.remove();
        }
      }
    }
  }

  // A WeakReference with shallow implementations of hashCode and equals implemented via the identity of the references object,
  // unlike WeakReference itself which uses the identity of itself (via the default implementations inherited from Object).
  static final class ShallowRef<T> {
    private final WeakReference<T> ref;
    private final int identityHashCode;

    ShallowRef(final T referent) {
      ref = new WeakReference<>(referent);

      // The identity hashcode is persisted as it must be immutable, even though the referent is disposed.
      this.identityHashCode = System.identityHashCode(referent);
    }

    final T get() {
      return ref.get();
    }

    @Override
    public String toString() {
      return "Ref [" + ref.get() + "]";  //$NON-NLS-1$ //$NON-NLS-2$
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

  class ClientInvocationHandler extends RemoteInvocationHandler {
    /**
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

  private final class Connections implements Closeable {
    private final Set<Connection> connections = Collections.synchronizedSet(new HashSet<Connection>());
    private final NavigableMap<Long, Connection> connectionPool = new ConcurrentSkipListMap<>();

    private final ThreadFactory threadFactory = new ThreadFactoryBuilder().factoryNamePrefix(getClass().getCanonicalName()).build();
    private final ScheduledExecutorService connectionPoolExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);

    private class ConnectionPoolCleanup implements Runnable, Closeable {
      static final long CONNECTION_POOL_LIFETIME = 60;
      private final ScheduledFuture<?> scheduleFuture;

      ConnectionPoolCleanup() {
        this.scheduleFuture = connectionPoolExecutor.scheduleAtFixedRate(this, CONNECTION_POOL_LIFETIME, CONNECTION_POOL_LIFETIME, SECONDS);
        logger.debug("ConnectionPoolCleanup: scheduled to run every {}s", CONNECTION_POOL_LIFETIME); //$NON-NLS-1$
      }

      @Override
      public void run() {
        final long now = System.currentTimeMillis();
        final long deleteOlderThan = now - SECONDS.toMillis(CONNECTION_POOL_LIFETIME);
        synchronized (connectionPool) {
          final Map<Long, Connection> connectionsToRemove = connectionPool.headMap(deleteOlderThan, true);
          for (final Map.Entry<Long, Connection> entry : connectionsToRemove.entrySet()) {
            if (logger.isDebugEnabled()) {
              logger.debug("ConnectionPoolCleanup: removing connection at {}: {} (last used at {})", //$NON-NLS-1$
                           new Object[] {now, entry.getValue(), entry.getKey()});
            }
            closeConnection(entry.getValue());
          }
          connectionsToRemove.clear();
          logger.debug("ConnectionPoolCleanup: active connections: {}", connectionPool); //$NON-NLS-1$
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
      final Socket socket = settings.socketFactory.createSocket();
      socket.connect(settings.endpoint);

      final Connection connection = new Connection(Protocol.client(socket, classLoader));
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
      final Entry<Long, Connection> entry = connectionPool.pollFirstEntry();
      if (entry != null) {
        return entry.getValue();
      }
      return createConnection();
    }

    void putConnection(final Connection connection) {
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

    @Override
    public void close() {
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

  final class Connection implements AutoCloseable {
    final Protocol protocol;

    Connection(final Protocol protocol) {
      this.protocol = protocol;
      keepAlive.start(protocol);

      if (logger.isDebugEnabled()) logger.debug("Connection created: " + this); //$NON-NLS-1$
    }

    <API> API connect(final Class<API> apiClass) throws Exception {
      protocol.bind(apiClass);
      final API api = protocol.newProxyObjectFactory().withInvocationHandler(new ClientInvocationHandler(apiClass)).create(apiClass);
      proxyObjects.add(api);
      return api;
    }

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
      return "RemoteClient.Connection [" + protocol + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }
  }
}
