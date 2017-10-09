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

import java.io.IOException;
import java.io.InvalidClassException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.rmi.Remote;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import muxrmi.Protocol.ClassRef;

/**
 * Implementation of a remote server.
 * <p/>
 * A remote server can start a remote {@link Service} on a {@link ServerSocket}. This service will serve incoming requests
 * on the server socket until it is closed by calling {@link Service#close()}.
 * <p/>
 * A remote server instance controls the lifetime of all remote services created by it, and will close all running
 * services when it is itself closed.
 *
 * @author ReneAndersen
 */
public class RemoteServer implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(RemoteServer.class);

  private final ThreadFactory threadFactory = new ThreadFactoryBuilder().factoryNamePrefix(getClass().getCanonicalName()).build();
  private final ExecutorService executor = Executors.newCachedThreadPool(threadFactory);
  private final Set<Service> services = new HashSet<>();
  private final Registry registry = new Registry();

  private final Settings settings;
  private final Statistics stats;
  private final KeepAlive keepAlive;
  private final ClassLoader classLoader;


  private List<ClassRef> findRemoteInterfaces(final Class<?> cls) throws InvalidClassException {
    final List<ClassRef> res = new ArrayList<>();

    if (cls != null) {
      // Is the class an interface that extends 'java.rmi.Remote'?
      if (cls.isInterface() && Remote.class.isAssignableFrom(cls)) {
        res.add(ClassRef.forClass(cls));
        return res;
      }

      // Otherwise, find remote inherited interfaces
      for (final Class<?> i : cls.getInterfaces()) {
        res.addAll(findRemoteInterfaces(i));
      }

      // Also include remote interfaces from super class.
      res.addAll(findRemoteInterfaces(cls.getSuperclass()));
    }

    return res;
  }

  /**
   * Configuration settings for a remote server.
   */
  public static class Settings {
    private final StatisticsProvider statistics;
    private final KeepAlive.Settings keepAliveSettings;
    private final SocketSettings socketSettings;

    /**
     * Create default configuration settings.
     * @return the default configuration settings.
     */
    public static Settings getDefault() {
      return new Settings(new StatisticsProvider(), new KeepAlive.Settings(), new SocketSettings());
    }

    /**
     * @param statistics the statistics provider.
     * @param keepAliveSettings the keep-alive settings.
     * @param socketSettings the socket settings.
     */
    public Settings(final StatisticsProvider statistics,
                    final KeepAlive.Settings keepAliveSettings,
                    final SocketSettings socketSettings) {
      this.statistics = statistics;
      this.keepAliveSettings = keepAliveSettings;
      this.socketSettings = socketSettings;
    }
    
    /** {@inhericDoc} */
    public String toString() {
      return String.format("Settings [keepAliveSettings=%s, socketSettings=%s]", keepAliveSettings, socketSettings);
    }
  }

  /**
   * Create a remote server.
   * @param classLoader a class loader for the objects read on the remote protocol.
   * @param settings the configuration settings for the remote server.
   */
  public RemoteServer(final ClassLoader classLoader, final Settings settings) {
    this.settings = settings;
    this.stats = new Statistics(settings.statistics);
    this.keepAlive = new KeepAlive(settings.keepAliveSettings, settings.statistics);
    this.classLoader = classLoader;
    
    logger.info("{}", this);
  }

  /**
   * Register the remote parts of an API for remote invocation.
   * @param api the object implementing the remote API.
   * @return a list with the {@link Class} types that were registered.
   * @throws InvalidClassException if the API does not implement the {@link Remote} interface.
   */
  public List<Class<?>> register(final Object api) throws InvalidClassException {
    final List<ClassRef> interfaces = findRemoteInterfaces(api.getClass());
    if (!interfaces.isEmpty()) {
      final List<Class<?>> classes = new ArrayList<>(interfaces.size());
      for (final ClassRef classRef : interfaces) {
        registry.registerReference(classRef, api);
        registry.registerMethods(classRef);
        classes.add(classRef.classType);
      }
      
      logger.info("Registered classes from {}: {}", api, classes);
      return classes;
    }
    else
      throw new InvalidClassException(api.getClass().getName(), "No interfaces extend '" + Remote.class.getName() +"'"); //$NON-NLS-1$ //$NON-NLS-2$
  }

  /**
   * Unregister a previously registered API.
   * @param api the object to unregister.
   * @return {@code true} if the object was found (and unregistered), {@code false} otherwise.
   */
  public boolean unregister(final Object api) {
    try {
      final List<ClassRef> interfaces = findRemoteInterfaces(api.getClass());
      boolean status = true;
      for (final ClassRef classRef : interfaces) {
        if (api != registry.findReference(classRef.id())) {
          status = false;
        } else {
          status = registry.unregisterReference(classRef)
                && registry.unregisterMethods(classRef)
                && status;
        }
      }
      return status;
    } catch (final InvalidClassException e) {
      return false;
    }
  }

  /**
   * Statistics for a remote service instance.
   */
  public class Statistics extends StatisticsProvider {
    Statistics(StatisticsProvider statistics) {
      super(statistics);
    }
    
    Value threadCount = new Value(RemoteServer.class, "thread-count") { //$NON-NLS-1$
      @Override
      protected int get() {
        if (executor instanceof ThreadPoolExecutor) {
          return ((ThreadPoolExecutor) executor).getPoolSize();
        }
        return 0;
      }
    };

    Counter connectionCount = new Counter(RemoteServer.class, "connection-count"); //$NON-NLS-1$
  }

  /**
   * @return the statistics for this remote server instance.
   */
  public Statistics getStatistics() {
    return stats;
  }

  /**
   * Interface representing a running remote service instance.
   */
  public interface Service extends AutoCloseable {
    /**
     * Stop this remote service.
     */
    @Override
    void close();

    /**
     * @return {@code true} iff this instance has been closed, {@code false} otherwise.
     */
    boolean isClosed();

    /**
     * @return the socket address of the remote service.
     */
    SocketAddress getSocketAddress();

    /**
     * @return the current number of connected remote instances.
     */
    int getInstanceCount();
  }

  /**
   * Start a remote {@link Service} listening on the specified server socket.
   * @param serverSocket the server socket.
   * @return An {@link Service} reference to the remote server instance.
   * @throws IOException
   */
  public synchronized Service start(final ServerSocket serverSocket) throws IOException {
    final Acceptor acceptor = new Acceptor(serverSocket);
    services.add(acceptor);
    executor.execute(acceptor);
    logger.info("Remote server started: {}", serverSocket);
    return acceptor;
  }

  /**
   * Close this remote server, including all running services started by it.
   */
  public synchronized void close() {
    try {
      for (final Service service : services) {
        service.close();
      }
    } finally {
      services.clear();
      executor.shutdownNow();
      keepAlive.close();
    }
  }
  
  /** {@inheritDoc} */
  public String toString() {
    return String.format("RemoteServer [settings=%s]", settings);
  }

  /**
   * @return the keep-alive handler of this remote client.
   */
  KeepAlive getKeepAlive() {
    return keepAlive;
  }

  private class Acceptor implements Runnable, Service {
    private final Map<Object, Connection> connections = new ConcurrentHashMap<>();
    private final ServerSocket serverSocket;

    Acceptor(final ServerSocket serverSocket) {
      this.serverSocket = serverSocket;
      stats.connectionCount.update();
    }

    void register(final Connection connection) {
      connections.put(connection.key(), connection);
      stats.threadCount.update();
      stats.connectionCount.inc();
      keepAlive.start(connection.protocol);
    }

    void unregister(final Connection connection) {
      stats.connectionCount.dec();
      connections.remove(connection);
      keepAlive.stop(connection.protocol);
    }

    public void run() {
      try {
        do {
          try {
            final Socket socket = serverSocket.accept();
            settings.socketSettings.applyTo(socket);

            final Connection connection = new Connection(Protocol.server(socket, registry, classLoader), this);
            executor.execute(connection);
          } catch (final Exception e) {
            if (serverSocket.isClosed()) {
              return;
            } else {
              logger.error("Error in remote server: " + e.getMessage(), e); //$NON-NLS-1$
            }
          }
        } while (true);
      } finally {
        try {
          serverSocket.close();
        } catch (final Exception e) {
          logger.error("Error closing server socket in remote server", e); //$NON-NLS-1$
        }
      }
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
      try {
        services.remove(this);
        serverSocket.close();
      } catch (final IOException e) {
        logger.error("Error closing server socket: " + serverSocket, e); //$NON-NLS-1$
      }

      for (final Connection connection : connections.values()) {
        connection.close();
      }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isClosed() {
      return serverSocket.isClosed();
    }

    /** {@inheritDoc} */
    @Override
    public SocketAddress getSocketAddress() {
      return serverSocket.getLocalSocketAddress();
    }

    /** {@inheritDoc} */
    @Override
    public int getInstanceCount() {
      return connections.size();
    }
  }

  private static class Connection implements Runnable, AutoCloseable {
    private final Protocol protocol;
    private final Acceptor acceptor;

    private Object key() {
      return this;
    }

    Connection(final Protocol protocol, final Acceptor acceptor) {
      this.protocol = protocol;
      this.acceptor = acceptor;
    }

    @Override
    public void run() {
      acceptor.register(this);
      try {
        protocol.run();
      } catch (final Exception e) {
        if (!protocol.isClosed()) {
          logger.error("{} Error in remote request: {}", this, logger.isDebugEnabled() ? e : e.toString()); //$NON-NLS-1$
        }
      } finally {
        close();
      }
    }

    @Override
    public synchronized void close() {
      acceptor.unregister(this);
      protocol.close();
    }

    @Override
    public String toString() {
      return protocol.toString();
    }
  }
}
