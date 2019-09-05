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
 * Implementation of a keep-alive handler for remote connections.
 * <p/>
 * Instances of {@link Protocol} can be passed to a keep-alive handler by calling
 * {@link #start(Protocol)}. This will create a keep-alive task that monitors
 * that the connection doesn't sit idle for longer than the time specified by the
 * instance's {@link Settings}.
 * If the idle duration is exceeded the protocol instance will be instructed to 
 * send a continuation command to the remote party.
 * <p/>
 * All keep-alive tasks started by an instance of this class will run in the same 
 * shared thread-pool. Individual keep-alive tasks can be stopped by calling 
 * {@link #stop(Protocol)}. The entire keep-alive handler can be stopped by 
 * calling {@code close()}.
 * 
 * @author Rene Andersen
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
    /** The interval (in seconds) to send keep-alive packages. */
    public final LongValue intervalSec = new LongValue("interval", 5);
    
    /** The precision margin (in seconds) for keep-alive packages. A keep-alive package can be sent when there's less than this time left of an interval. */
    public final LongValue sendMarginSec = new LongValue("send-margin", 1);
    
    /** The precision margin (in seconds) for keep-alive packages. The connection is considered lost if the keep-alive interval is exceeded by this time. */
    public final LongValue recvMarginSec = new LongValue("recv-margin", 5);
    
    /** The interval (in seconds) with which to run each keep-alive task. */
    public final LongValue pollIntervalSec = new LongValue("poll-interval", 1);

    /**
     * Create a new settings object that read settings from the specified reader.
     * @param reader
     */
    public Settings(final ConfigurationSettings.Reader reader) {
      super(new WithPrefix("keep-alive.", reader));
      reload();
    }
    
    /**
     * Create a new default settings object.
     */
    public Settings() {
      this(ConfigurationSettings.DEFAULTS);
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
    
    final Value threadCount = new CountedGauge(Statistics.class, "thread-count") {
      @Override
      protected long get() {
        if (scheduler instanceof ThreadPoolExecutor) {
          return ((ThreadPoolExecutor) scheduler).getPoolSize();
        }
        return 0;
      }
    };

    final CountedValue taskCount = new CountedValue(Statistics.class, "task-count");
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
    scheduler.schedule(task, settings.pollIntervalSec.get(), SECONDS);
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

  /**
   * A runnable keep-alive task with the responsibility to keep one remote connection alive.
   */
  final class Task implements Runnable, Closeable {
    final Protocol protocol;

    volatile boolean closed = false;

    /**
     * Create a keep-alive task for the specified {@link Protocol} instance.
     * @param protocol the protocol instance.
     */
    private Task(final Protocol protocol) {
      this.protocol = protocol;

      if (logger.isDebugEnabled()) logger.debug(protocol.id() + " Connection keep-alive created: {}", settings);
    }

    private long getElapsedMillis(final long lastUpdateMillis) {
      return System.currentTimeMillis() - lastUpdateMillis;
    }

    private long getRemainingMillis(final long lastUpdateMillis) {
      return SECONDS.toMillis(settings.intervalSec.get()) - getElapsedMillis(lastUpdateMillis);
    }

    /**
     * Run the keep alive task once. The behavior depends on the current state of the
     * {@link Protocol} instance being monitored:
     * <ul>
     * <li>ACCEPT: Check how long it's been since the last command was received, and
     *             close the connection if the duration exceeds the keep-alive package 
     *             interval (+ a receive margin).</li>
     * <li>INITIAL/RUNNING: Check how long it's been since the last keep-alive package
     *             was sent, and send a new keep-alive package if the duration exceeds
     *             the keep-alive package interval (- a send margin).</li>
     * <li>CLOSED: Terminate.
     * </ul>
     * Unless the current state is CLOSED the task will reschedule itself to run again
     * after the configured poll interval has passed.
     */
    @Override
    public void run() {
      if (closed) {
        return;
      }
      if (logger.isTraceEnabled()) logger.trace(protocol.id() + " Connection keep-alive started");

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
              if (logger.isWarnEnabled()) logger.warn(protocol.id() + " Connection keep-alive margin exceeded: {}ms > {}ms",
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
              rescheduleMillis = Math.min(rescheduleMillis, remainingMillis);
            }
            break;

          case CLOSED:
          default:
            if (logger.isDebugEnabled()) logger.debug(protocol.id() + " Connection keep-alive stopped");
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
        if (logger.isTraceEnabled()) logger.trace(protocol.id() + " Connection keep-alive rescheduled in {}ms ({})", rescheduleMillis, state);
      } else {
        close();
      }
    }

    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        stats.taskCount.dec();
        if (logger.isDebugEnabled()) logger.debug(protocol.id() + " Connection keep-alive closed");
      }
    }

    @Override
    protected void finalize() {
      close();
    }
  }
}
