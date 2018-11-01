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

import easysettings.ConfigurationSettings.Reader;
import muxrmi.Protocol.ClassRef;
import muxrmi.io.CommunicationChannel;

/**
 * Implementation of a Mux-RMI remote server.
 * <p/>
 * A remote server can start a remote {@link Service} on a {@link ServerSocket}. This service will serve incoming requests
 * on the server socket until it is closed by calling {@link Service#close()}. These requests will be executed as tasks
 * by the build-in thread pool.
 * <p/>
 * A remote server instance controls the lifetime of all remote services created by it, and will close all running
 * services when it is itself closed.
 *
 * @author Rene Andersen
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
    /** The statistics provider */
    public final StatisticsProvider statistics;
    
    /** The keep-alive settings */
    public final KeepAlive.Settings keepAliveSettings;

    /**
     * @param keepAliveSettings the keep-alive settings.
     * @param statistics the statistics provider.
     */
    public Settings(final KeepAlive.Settings keepAliveSettings, final StatisticsProvider statistics) {
      this.statistics = statistics;
      this.keepAliveSettings = keepAliveSettings;
    }
    
    /**
     * @param reader the reader to read configuration settings from.
     * @param statistics the statistics provider.
     */
    public Settings(final Reader reader, final StatisticsProvider statistics) {
      this(new KeepAlive.Settings(reader), statistics);
    }
    
    public String toString() {
      return String.format("Settings [keepAliveSettings=%s]", keepAliveSettings);
    }
  }

  /**
   * Create a new remote server with the specified class loader and configuration settings.
   * @param settings the configuration settings for the remote server.
   */
  public RemoteServer(final Settings settings) {
    this.settings = settings;
    this.stats = new Statistics(settings.statistics);
    this.keepAlive = new KeepAlive(settings.keepAliveSettings, settings.statistics);
    
    logger.info("{}", this);
  }
  
  /**
   * @return the configuration settings for this remote server.
   */
  public Settings getSettings() {
    return settings;
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
      throw new InvalidClassException(api.getClass().getName(), "No interfaces extend '" + Remote.class.getName() +"'");
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
    
    Value threadCount = new CountedGauge(Statistics.class, "thread-count") {
      @Override
      protected long get() {
        if (executor instanceof ThreadPoolExecutor) {
          return ((ThreadPoolExecutor) executor).getPoolSize();
        }
        return 0;
      }
    };

    CountedValue connectionCount = new CountedValue(Statistics.class, "connection-count");
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
     * @return the current number of connected remote instances.
     */
    int getInstanceCount();
  }

  /**
   * Start a remote {@link Service} on the specified communication channel factory.
   * @param commFactory the communication channel factory.
   * @return An {@link Service} reference to the remote server instance.
   */
  public synchronized Service start(final CommunicationChannel.Factory commFactory) {
    final Acceptor acceptor = new Acceptor(commFactory);
    services.add(acceptor);
    executor.execute(acceptor);
    logger.info("Remote server started: {}", commFactory);
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

  /**
   * An acceptor is a runnable task which accepts remote socket connections 
   * from a listening server socket. Each time a connection has been accepted
   * a new {@link Connection} object is created to handle the new connection.
   * </p>
   * The {@link #run()} method will loop until the server socket is closed,
   * or until an non-{@link Exception} error is thrown.
   */
  private class Acceptor implements Runnable, Service {
    private final Map<Object, Connection> connections = new ConcurrentHashMap<>();
    private final CommunicationChannel.Factory commFactory;

    Acceptor(final CommunicationChannel.Factory commFactory) {
      this.commFactory = commFactory;
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
            final CommunicationChannel comm = commFactory.create();
            final Runnable command = new Connection(new Protocol.Server(comm, registry), this);
            executor.execute(command);
          } catch (final Exception e) {
            if (commFactory.isClosed()) {
              return;
            } else {
              logger.error("Error in remote server: " + e.getMessage(), e);
            }
          }
        } while (true);
      } finally {
        try {
          commFactory.close();
        } catch (final Exception e) {
          logger.error("Error closing server socket in remote server", e);
        }
      }
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
      try {
        services.remove(this);
        commFactory.close();
      } catch (final IOException e) {
        logger.error("Error closing server socket: " + commFactory, e);
      }

      for (final Connection connection : connections.values()) {
        connection.close();
      }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isClosed() {
      return commFactory.isClosed();
    }

    /** {@inheritDoc} */
    @Override
    public int getInstanceCount() {
      return connections.size();
    }
  }

  /**
   * An instance of the connection class is a runnable task that handles communication
   * with a remote client. It does this by invoking {@link Protocol#run()} on its server-side 
   * protocol instance.
   */
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
          logger.error("{} Error in remote request: {}", this, logger.isDebugEnabled() ? e : e.toString());
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
