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
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.rmi.NotBoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import javax.net.SocketFactory;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.JmxReporter;

import easysettings.ConfigurationSettings;
import muxrmi.io.CommunicationChannel;
import muxrmi.io.ObjectStreamChannel;

/**
 * Common base class for remote tests.
 * @author ReneAndersen
 */
public class RemoteTestBase {
  private static final Logger logger = LoggerFactory.getLogger(RemoteTestBase.class);
  
  protected static final String PREFIX = "muxrmi.";
  protected static final int LISTEN_PORT = 0;

  protected static StatisticsProvider statistics;
  protected static JmxReporter jmxReporter;
  protected static RemoteServer server;
  protected static KeepAlive.Settings keepAliveSettings;
  protected static ServerSocket serverSocket;
  protected static RemoteServer.Service service;
  protected static ClassLoader classLoader = RemoteTestBase.class.getClassLoader();
  
  private static ConfigurationSettings.Reader reader =
      new ConfigurationSettings.WithPrefix(PREFIX, new ConfigurationSettings.FromSystemProperties());
  
  private static SocketSettings socketSettings = new SocketSettings(reader);

  @BeforeClass
  public static void beforeClass() throws Exception {
    logger.info("Running beforeClass()");
    statistics = new StatisticsProvider();
    jmxReporter = JmxReporter.forRegistry(statistics.getRegistry()).build();
    jmxReporter.start();
    server = new RemoteServer(new RemoteServer.Settings(reader, statistics));
    keepAliveSettings = server.getSettings().keepAliveSettings;
    service = server.start(createServerCommFactory());
  }

  @AfterClass
  public static void afterClass() {
    logger.info("Running afterClass()");
    server.close();
    jmxReporter.close();
  }

  @Before
  public void before() throws Exception {
    logger.info("Running before()");
    if (service.isClosed()) {
      service = server.start(createServerCommFactory());
    }
  }
  
  static CommunicationChannel.Factory createClientCommFactory(final ClassLoader classLoader) {
    final SocketAddress endpoint = serverSocket.getLocalSocketAddress();
    return new ObjectStreamChannel.ClientFactory(SocketFactory.getDefault(), socketSettings, endpoint, classLoader);
  }

  static RemoteClient createClient(final ClassLoader classLoader) {
    final CommunicationChannel.Factory commFactory = createClientCommFactory(classLoader);
    final RemoteClient.Settings clientSettings = new RemoteClient.Settings(reader, statistics);
    
    return new RemoteClient(commFactory, clientSettings);
  }

  static void connectNotBound(final Class<?> classType, final RemoteClient client) throws Exception {
    try {
      final Object obj = client.connect(classType);
      client.dispose(obj);
      Assert.fail("Class type was unexpectedly bound: " + classType);
    } catch (final NotBoundException e) {
       // expected
    }
  }

  static CommunicationChannel.Factory createServerCommFactory() throws IOException {
    serverSocket = createServerSocket();
    socketSettings.reload();
    return new ObjectStreamChannel.ServerFactory(serverSocket, socketSettings, classLoader);
  }

  static ServerSocket createServerSocket() throws IOException {
    return new ServerSocket(LISTEN_PORT);
  }
  
  static <T> List<T> runTasks(final int tasks, final int threads, final Callable<T> task) {
    final ThreadFactory threadFactory = new ThreadFactoryBuilder().factoryNamePrefix(RemoteTestBase.class.getCanonicalName()).build();
    final ExecutorService executor = Executors.newFixedThreadPool(threads, threadFactory);
    final List<Future<T>> futures = new ArrayList<>(tasks);
    for (int i = 0; i < tasks; ++i) {
      futures.add(executor.submit(task));
    }
    final List<Exception> exs = new ArrayList<>();
    final List<T> results = new ArrayList<>(futures.size());
    for (final Future<T> future : futures) {
      try {
        results.add(future.get());
      } catch (final Exception e) {
        exs.add(e);
      }
    }
    return results;
  }
}
