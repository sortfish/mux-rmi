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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import easysettings.ConfigurationSettings;
import muxrmi.Protocol.SharedState;
import muxrmi.Protocol.State;

/**
 * Implementation of a keep-alive handler for remote connections. All keep-alive tasks started by an instance of this class
 * will run on the same shared thread-pool, and can be stopped by calling {@code close()} on the keep-alive handler.
 * @author ReneAndersen
 */
public class KeepAlive implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(KeepAlive.class);

  private final Settings settings;
  private final Statistics stats;
  private final Map<Object, Task> tasks = new HashMap<>();
  private final ThreadFactory threadFactory;
  private final ScheduledExecutorService scheduler;

  /**
   * Configuration settings for the keep-alive handler.
   */
  public static class Settings extends ConfigurationSettings {
    /** The default prefix to use when reading settings from a {@link Properties} object. */
    public static final String KEEP_ALIVE_PREFIX = "muxrmi.keep-alive.";

    /** The interval (in seconds) to send keep-alive packages. */
    public final LongValue intervalSec = new LongValue("interval", 5);
    
    /** The precision margin (in seconds) for keep-alive packages. A keep-alive package can be sent when there's less than this time left of an interval. */
    public final LongValue sendMarginSec = new LongValue("send-margin", 1);
    
    /** The precision margin (in seconds) for keep-alive packages. The connection is considered lost if the keep-alive interval is exceeded by this time. */
    public final LongValue recvMarginSec = new LongValue("recv-margin", 5);
    
    /** The interval (in seconds) with which to run each keep-alive task. */
    public final LongValue pollIntervalSec = new LongValue("poll-interval", 1);

    /**
     * Create a new settings object that reads settings from system properties with the prefix {@link #KEEP_ALIVE_PREFIX}.
     */
    public Settings() {
      super(KEEP_ALIVE_PREFIX);
    }
    
    /**
     * Create a new settings object that read settings from the specified reader.
     * @param reader
     */
    public Settings(final ConfigurationSettings.Reader reader) {
      super(reader);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
      return String.format("%ss, %ss, %ss", intervalSec, sendMarginSec, pollIntervalSec);
    }
  }

  /**
   * Create a new keep-alive handler instance.
   */
  KeepAlive(final Settings settings, final muxrmi.StatisticsProvider statistics) {
    this.settings = settings;
    this.stats = new Statistics(statistics);
    this.threadFactory = new ThreadFactoryBuilder().factoryNamePrefix(getClass().getCanonicalName()).build();
    this.scheduler = Executors.newScheduledThreadPool(1, threadFactory);
  }

  /**
   * Statistics for a keep-alive handler.
   */
  final class Statistics extends StatisticsProvider {
    Statistics(final StatisticsProvider statistics) {
      super(statistics);
    }
    
    final Value threadCount = new Value(KeepAlive.class, "thread-count") {
      @Override
      protected int get() {
        if (scheduler instanceof ThreadPoolExecutor) {
          return ((ThreadPoolExecutor) scheduler).getPoolSize();
        }
        return 0;
      }
    };

    final Counter taskCount = new Counter(KeepAlive.class, "task-count");
  }

  /**
   * @return the statistics for this keep-alive handler.
   */
  Statistics getStatistics() {
    return stats;
  }

  /**
   * Start a new keep-alive task for the specified remote protocol instance.
   * @param protocol the protocol instance to keep alive.
   * @throws RejectedExecutionException if the task cannot be scheduled for execution.
   */
  synchronized void start(final Protocol protocol) throws RejectedExecutionException {
    final Task task = new Task(protocol);
    scheduler.schedule(task, 0, SECONDS);
    tasks.put(protocol, task);
    stats.taskCount.inc();
  }

  /**
   * Stop the keep-alive task for the specified remote protocol instance.
   * @param protocol the protocol instance.
   * @return {@code true} iff a keep-alive task was found (and stopped), {@code false} otherwise.
   */
  synchronized boolean stop(final Protocol protocol) {
    final Task task = tasks.remove(protocol);
    if (task != null) {
      task.close();
    }
    return task != null;
  }

  /**
   * Find the keep-alive task for the specified remote protocol instance.
   * @param protocol the remote protocol instance.
   * @return the keep-alive task, or {@code null} if not found.
   */
  Task findTask(final Protocol protocol) {
    return tasks.get(protocol);
  }

  /** {@inheritDoc} */
  @Override
  public synchronized void close() {
    try {
      for (final Task task : tasks.values()) {
        task.close();
      }
    } finally {
      tasks.clear();
      if (!scheduler.isShutdown()) {
        scheduler.shutdownNow();
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return "KeepAlive [" + settings + "]";
  }

  /** {@inheritDoc} */
  @Override
  protected void finalize() {
    close();
  }

  final class Task implements Runnable, Closeable {
    final Protocol protocol;

    volatile boolean closed = false;

    private Task(final Protocol protocol) {
      this.protocol = protocol;

      if (logger.isDebugEnabled()) logger.debug(protocol + " Connection keep-alive created: {}", settings);
    }

    private long getElapsedMillis(final long lastUpdateMillis) {
      return System.currentTimeMillis() - lastUpdateMillis;
    }

    private long getRemainingMillis(final long lastUpdateMillis) {
      return SECONDS.toMillis(settings.intervalSec.get()) - getElapsedMillis(lastUpdateMillis);
    }

    @Override
    public void run() {
      if (closed) {
        return;
      }
      if (logger.isTraceEnabled()) logger.trace(protocol + " Connection keep-alive started");

      stats.threadCount.update();

      final SharedState sharedState = protocol.getSharedState();
      final State state = sharedState.state;

      boolean reschedule = true;
      long rescheduleMillis = SECONDS.toMillis(settings.pollIntervalSec.get());
      try {
        switch (state) {
          case ACCEPT:
            final long elapsedMillis = getElapsedMillis(sharedState.lastUpdateMillis);
            final long intervalWithMarginMillis = SECONDS.toMillis(settings.intervalSec.get() + settings.recvMarginSec.get());
          if (elapsedMillis > intervalWithMarginMillis) {
              if (logger.isWarnEnabled()) logger.warn(protocol + " Connection keep-alive margin exceeded: {}ms > {}ms",
                  elapsedMillis, intervalWithMarginMillis);
              protocol.close();
              reschedule = false;
            }
            break;

          case INITIAL:
          case RUNNING:
            final long remainingMillis = getRemainingMillis(sharedState.lastUpdateMillis);
            if (remainingMillis <= SECONDS.toMillis(settings.sendMarginSec.get())) {
              protocol.sendContinue();
            } else {
              rescheduleMillis = remainingMillis;
            }
            break;

          case CLOSED:
          default:
            if (logger.isDebugEnabled()) logger.debug(protocol + " Connection keep-alive stopped");
            return;
        }
      } catch (final Exception e) {
        if (!protocol.isClosed()) {
          logger.error(protocol + " Connection keep-alive failed: {}", logger.isDebugEnabled() ? e : e.toString());
        }
        reschedule = false;
      }
      if (reschedule && !scheduler.isShutdown()) {
        scheduler.schedule(this, rescheduleMillis, MILLISECONDS);
        if (logger.isTraceEnabled()) logger.trace(protocol + " Connection keep-alive rescheduled in {}ms ({})", rescheduleMillis, state);
      } else {
        close();
      }
    }

    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        stats.taskCount.dec();
      }
      if (logger.isDebugEnabled()) logger.debug(protocol + " Connection keep-alive closed");
    }

    @Override
    protected void finalize() {
      close();
    }
  }
}
