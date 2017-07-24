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
package easyrmi;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.rmi.Remote;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import easyrmi.RemoteClient.Connection;
import easyrmi.Protocol.SharedState;

/**
 * @author ReneAndersen
 */
public class KeepAliveTest extends RemoteTestBase {
  private static final Logger logger = LoggerFactory.getLogger(KeepAliveTest.class);
  
  @BeforeClass
  public static void beforeClass() throws Exception {
    logger.info("Running beforeClass()");
    RemoteTestBase.beforeClass();
    final API api = new APIImpl();
    server.register(api);
  }

  // The keep-alive interval and margin to set while testing.
  private static final long KEEP_ALIVE_INTERVAL_SEC = 1;
  private static final long KEEP_ALIVE_MARGIN_SEC = 1;

  // The sleep duration to invoke on client or server while looking for keep-alive packages.
  private static final long KEEP_ALIVE_SLEEP_MILLIS = 3000;

  // The interval (in ms) to sample the maximum time that has passed without a command being exchanged on the remote connection.
  private static final long SAMPLE_INTERVAL_MILLIS = 250;

  @Test
  public void testKeepAliveDefault() throws Exception {
    logger.info("Running testKeepAliveDefault()");
    try (final KeepAliveTester keepAliveTester = new KeepAliveTester(null)) {
      try (final RemoteClient client = createClient(getClass().getClassLoader())) {
        final API api = client.connect(API.class);

        final KeepAliveTester.Task task = keepAliveTester.task(SAMPLE_INTERVAL_MILLIS, client);
        try (final AutoCloseable runner = task.start()) {
          api.sleepFor(KEEP_ALIVE_SLEEP_MILLIS);
        }
        final long maxCommandInterval = task.getMaxCommandInterval();
        Assert.assertTrue(Long.toString(maxCommandInterval), maxCommandInterval >= KEEP_ALIVE_SLEEP_MILLIS - SAMPLE_INTERVAL_MILLIS);
      }
    }
  }

  @Test
  public void testKeepAliveIdle() throws Exception {
    logger.info("Running testKeepAliveIdle()");
    try (final KeepAliveTester keepAliveTester = new KeepAliveTester(keepAliveSettings)) {
      try (final RemoteClient client = createClient(getClass().getClassLoader())) {
        final API api = client.connect(API.class);

        final KeepAliveTester.Task task = keepAliveTester.task(SAMPLE_INTERVAL_MILLIS, client);
        try (AutoCloseable runner = task.start()) {
          Thread.sleep(KEEP_ALIVE_SLEEP_MILLIS);
        }
        final long maxCommandInterval = task.getMaxCommandInterval();
        Assert.assertTrue(Long.toString(maxCommandInterval), maxCommandInterval <= SECONDS.toMillis(KEEP_ALIVE_INTERVAL_SEC) + SAMPLE_INTERVAL_MILLIS);

        final String s = "foo";
        // Check that the remote API is still functional
        Assert.assertEquals(s, api.echo(s));
      }
    }
  }

  @Test
  public void testKeepAliveActive() throws Exception {
    logger.info("Running testKeepAliveActive()");
    try (final KeepAliveTester keepAliveTester = new KeepAliveTester(keepAliveSettings)) {
      try (final RemoteClient client = createClient(getClass().getClassLoader())) {
        final API api = client.connect(API.class);

        final KeepAliveTester.Task task = keepAliveTester.task(SAMPLE_INTERVAL_MILLIS, client);
        try (AutoCloseable runner = task.start()) {
          api.sleepFor(KEEP_ALIVE_SLEEP_MILLIS);
        }
        final long maxCommandInterval = task.getMaxCommandInterval();
        final long limit = SECONDS.toMillis(KEEP_ALIVE_INTERVAL_SEC) + SAMPLE_INTERVAL_MILLIS;
        Assert.assertTrue(maxCommandInterval + ">" + limit, maxCommandInterval <= limit);

        final String s = "foo";
        // Check that the remote API is still functional
        Assert.assertEquals(s, api.echo(s));
      }
    }
  }

  @Test
  public void testKeepAliveFailureDisconnect() throws Exception {
    logger.info("Running testKeepAliveFailureDisconnect()");
    final RemoteClient client = createClient(getClass().getClassLoader());
    try (KeepAliveTester keepAliveTester = new KeepAliveTester(keepAliveSettings)) {
      final API api = client.connect(API.class);
      final Collection<Connection> connections = client.getConnections();
      Assert.assertEquals(connections.toString(), 1, connections.size());

      final Connection connection = connections.iterator().next();
      final KeepAlive keepAlive = client.getKeepAlive();
      final KeepAlive.Task task = keepAlive.findTask(connection.protocol);
      Assert.assertNotNull("Did not find keep-alive task for connection: " + connection, task);
      Assert.assertTrue("Stopping keep-alive for connection failed: " + connection, keepAlive.stop(connection.protocol));
      Assert.assertTrue("Task was not closed: " + task, task.closed);

      final long secondsToSleep = KEEP_ALIVE_INTERVAL_SEC + KEEP_ALIVE_MARGIN_SEC + 1;
      Thread.sleep(TimeUnit.SECONDS.toMillis(secondsToSleep));

      try {
        api.echo(42);
        Assert.fail("Method invocation on remote connection did not fail as expected: " + connection.protocol);
      } catch (final Exception e) {
        logger.info("Expected socket error from disconnected remote connection: " + e);
      }
    } finally {
      client.close();
    }
  }

  @Test
  public void testKeepAlive100SporadicTasks() throws Exception {
    logger.info("Running testKeepAlive100SporadicTasks()");
    final List<Long> maxCommandIntervals;
    try (final KeepAliveTester keepAliveTester = new KeepAliveTester(keepAliveSettings)) {
      try (final RemoteClient client = createClient(getClass().getClassLoader())) {
        maxCommandIntervals = runTasks(100, 100, new SporadicTask(10, (int) SECONDS.toMillis(2*KEEP_ALIVE_INTERVAL_SEC), keepAliveTester));
      }
    }

    // Test that all command intervals stayed within the configured keep-alive interval (+a sampling margin)
    final List<Long> failures = new ArrayList<>();
    long globalMaxCommandInterval = 0;
    double averageMaxCommandInterval = 0.0;
    for (final Long maxCommandInterval : maxCommandIntervals) {
      globalMaxCommandInterval = Math.max(globalMaxCommandInterval, maxCommandInterval);
      averageMaxCommandInterval += (double)maxCommandInterval / maxCommandIntervals.size();

      if (maxCommandInterval > SECONDS.toMillis(KEEP_ALIVE_INTERVAL_SEC) + SAMPLE_INTERVAL_MILLIS) {
        failures.add(maxCommandInterval);
      }
    }
    logger.debug("Global MaxCommandInterval={}", globalMaxCommandInterval);
    logger.debug("Average MaxCommandInterval={}", averageMaxCommandInterval);
    Assert.assertTrue("Ping-interval(s) exceeded: " + failures.toString(), failures.isEmpty());
  }

  public interface API extends Remote {
    <T> T echo(T arg);
    void sleepFor(long millis) throws InterruptedException;
  }

  private static final class APIImpl implements API {
    @Override
    public <T> T echo(final T arg) {
      return arg;
    }

    @Override
    public void sleepFor(final long millis) throws InterruptedException {
      Thread.sleep(millis);
    }
  }

  private static class KeepAliveTester implements AutoCloseable {
    final ThreadFactory threadFactory = new ThreadFactoryBuilder().factoryNamePrefix(getClass().getCanonicalName()).build();
    final ExecutorService executor = Executors.newCachedThreadPool(threadFactory);

    private final KeepAlive.Settings settings;
    private long originalKeepAliveInterval = 0L;
    private long originalKeepAliveMargin = 0L;

    KeepAliveTester(final KeepAlive.Settings settings) {
      this.settings = settings;
      if (settings != null) {
        this.originalKeepAliveInterval = settings.intervalSec.set(KEEP_ALIVE_INTERVAL_SEC);
        this.originalKeepAliveMargin = settings.marginSec.set(KEEP_ALIVE_MARGIN_SEC);
      }
    }

    final class Task implements Callable<Long> {
      private Future<Long> future = null;
      private volatile boolean done = false;
      private final RemoteClient client;
      private final long sampleIntervalMillis;

      Task(final long sampleIntervalMillis, final RemoteClient client) {
        this.client = client;
        this.sampleIntervalMillis = sampleIntervalMillis;
      }

      @Override
      public final Long call() throws Exception {
        final Collection<Connection> connections = client.getConnections();
        logger.debug("Running KeepAliveTester.Task on: {}", connections);
        Assert.assertTrue(connections.toString(), connections.size() == 1);

        final SharedState sharedState = connections.iterator().next().protocol.getSharedState();
        final List<Long> commandIntervals = new ArrayList<>();
        long maxCommandInterval = 0L;
        do {
          final long lastCommandMillis = sharedState.lastUpdateMillis;
          final long now = System.currentTimeMillis();
          final long lastCommandInterval = now - lastCommandMillis;

          final int index = commandIntervals.size() - 1;
          if (index >= 0 && commandIntervals.get(index) < lastCommandInterval)
            commandIntervals.set(index, lastCommandInterval);
          else
            commandIntervals.add(lastCommandInterval);
          maxCommandInterval = Math.max(maxCommandInterval, lastCommandInterval);
          Thread.sleep(sampleIntervalMillis);
        } while (!done);
        logger.debug("MaxCommandInterval={}, CommandIntervals={}", maxCommandInterval, commandIntervals);
        if (logger.isTraceEnabled()) {
          final RemoteServer.Statistics serverStats = server.getStatistics();
          logger.trace("Server connectionCount={}, threadCount={}", serverStats.connectionCount, serverStats.threadCount);
          final KeepAlive.Statistics keepAliveStats = server.getKeepAlive().getStatistics();
          logger.trace("KeepAlive taskCount={}, threadCount={}", keepAliveStats.taskCount, keepAliveStats.threadCount);
        }
        return maxCommandInterval;
      }

      public AutoCloseable start() {
        future = executor.submit(this);
        return new AutoCloseable() {
          @Override
          public void close() {
            done = true;
          }
        };
      }

      final long getMaxCommandInterval() throws ExecutionException, InterruptedException {
        if (future != null) {
          return future.get();
        } else {
          Assert.fail("Missing future in keep-alive task");
          return 0;
        }
      }
    }

    Task task(final long sampleIntervalMillis, final RemoteClient client) {
      return new Task(sampleIntervalMillis, client);
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws Exception {
      if (settings != null) {
        settings.intervalSec.set(originalKeepAliveInterval);
        settings.marginSec.set(originalKeepAliveMargin);
      }
    }
  }

  private static final class SporadicTask implements Callable<Long> {
    private static final AtomicInteger ID = new AtomicInteger();

    private final int iterations;
    private final int meanInterval;
    private final KeepAliveTester keepAliveTester;


    SporadicTask(final int iterations, final int meanIntervalMillis, final KeepAliveTester keepAliveTester) {
      this.iterations = iterations;
      this.meanInterval = meanIntervalMillis;
      this.keepAliveTester = keepAliveTester;
    }

    @Override
    public Long call() throws Exception {
      final int id = ID.incrementAndGet();
      final List<Long> sleepTimes = new ArrayList<Long>(iterations);

      try (final RemoteClient client = createClient(getClass().getClassLoader())) {
        final API api = client.connect(API.class);
        final KeepAliveTester.Task task = keepAliveTester.task(SAMPLE_INTERVAL_MILLIS, client);
        try (AutoCloseable runner = task.start()) {
          final Random random = new Random();
          logger.debug("SporadicTask({}) running", id);
          for (long i = 0; i < iterations; ++i) {
            final long sleepTimeMillis = random.nextInt(2*meanInterval);
            sleepTimes.add(sleepTimeMillis);
            Thread.sleep(sleepTimeMillis);
            api.echo(42);
          }
        }
        final long maxCommandInterval = task.getMaxCommandInterval();
        logger.debug("SporadicTask({}) finished. MaxCommandInterval={}, SleepTimes={}", id, maxCommandInterval, sleepTimes);
        if (logger.isTraceEnabled()) {
          final RemoteServer.Statistics serverStats = server.getStatistics();
          logger.trace("Server connectionCount={}, threadCount={}", serverStats.connectionCount, serverStats.threadCount);
          final KeepAlive.Statistics keepAliveStats = server.getKeepAlive().getStatistics();
          System.out.println("KeepAlive taskCount=" + keepAliveStats.taskCount + " threadCount=" + keepAliveStats.threadCount);
        }
        return maxCommandInterval;
      }
    }
  }
}
