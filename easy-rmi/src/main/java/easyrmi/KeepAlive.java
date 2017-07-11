package easyrmi;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import easyrmi.Protocol.SharedState;
import easyrmi.Protocol.State;
import easyrmi.Statistics.Counter;
import easyrmi.Statistics.Value;
import easysettings.ConfigurationSettings;

/**
 * Implementation of a keep-alive handler for remote connections. All keep-alive tasks started by an instance of this class
 * will run on the same shared thread-pool, and can be stopped by calling {@code close()} on the keep-alive handler.
 * @author ReneAndersen
 */
class KeepAlive implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(KeepAlive.class);


  private final Map<Object, Task> tasks = new HashMap<>();
  private final ScheduledExecutorService scheduler;

  private final Statistics stats = new Statistics();

  static class Settings extends ConfigurationSettings.FromSystemProperties {
    static final String KEEP_ALIVE_PREFIX = KeepAlive.class.getPackage().getName() + ".keep-alive."; //$NON-NLS-1$

    final LongValue intervalSec;
    final LongValue marginSec;
    final LongValue pollIntervalSec;

    Settings() {
      super(KEEP_ALIVE_PREFIX);
      this.intervalSec = new LongValue("interval", 10); //$NON-NLS-1$
      this.marginSec = new LongValue("margin", 1); //$NON-NLS-1$
      this.pollIntervalSec = new LongValue("poll-interval", 1); //$NON-NLS-1$
    }

    @Override
    public String toString() {
      return String.format("%ss, %ss, %ss", intervalSec, marginSec, pollIntervalSec); //$NON-NLS-1$
    }
  }

  final Settings settings;



  /**
   * Create a new keep-alive handler instance.
   */
  KeepAlive(final Settings settings) {
    this.scheduler = Executors.newScheduledThreadPool(1);
    this.settings = settings;
  }

  /**
   * Statistics for a keep-alive handler.
   */
  final class Statistics {
    final Value threadCount = new Value(KeepAlive.class, "thread-count") { //$NON-NLS-1$
      @Override
      protected int get() {
        if (scheduler instanceof ThreadPoolExecutor) {
          return ((ThreadPoolExecutor) scheduler).getPoolSize();
        }
        return 0;
      }
    };

    final Counter taskCount = new Counter(KeepAlive.class, "task-count"); //$NON-NLS-1$
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

  @Override
  public String toString() {
    return "KeepAlive [" + settings + "]"; //$NON-NLS-1$ //$NON-NLS-2$
  }

  /** {@inheritDoc} */
  @Override
  protected void finalize() {
    close();
  }

  final class Task implements Runnable, Closeable {
    final Protocol protocol;

    boolean closed = false;

    private Task(final Protocol protocol) {
      this.protocol = protocol;

      if (logger.isDebugEnabled()) logger.debug(protocol + " Connection keep-alive created: {}", settings); //$NON-NLS-1$
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
      if (logger.isTraceEnabled()) logger.trace(protocol + " Connection keep-alive started"); //$NON-NLS-1$

      stats.threadCount.update();

      final SharedState sharedState = protocol.getSharedState();
      final State state = sharedState.state;

      boolean reschedule = true;
      long rescheduleMillis = SECONDS.toMillis(settings.pollIntervalSec.get());
      try {
        switch (state) {
          case ACCEPT:
            final long elapsedMillis = getElapsedMillis(sharedState.lastUpdateMillis);
            if (elapsedMillis > SECONDS.toMillis(settings.intervalSec.get() + settings.marginSec.get())) {
              if (logger.isErrorEnabled()) logger.error(protocol + " Connection keep-alive margin exceeded"); //$NON-NLS-1$
              protocol.disconnect();
              reschedule = false;
            }
            break;

          case INITIAL:
          case RUNNING:
            final long remainingMillis = getRemainingMillis(sharedState.lastUpdateMillis);
            if (remainingMillis <= SECONDS.toMillis(settings.marginSec.get())) {
              protocol.sendContinue();
            } else {
              rescheduleMillis = remainingMillis;
            }
            break;

          case CLOSED:
          default:
            if (logger.isDebugEnabled()) logger.debug(protocol + " Connection keep-alive stopped"); //$NON-NLS-1$
            return;
        }
      } catch (final Exception e) {
        if (logger.isDebugEnabled()) {
          logger.error(protocol + " Connection keep-alive failed", e); //$NON-NLS-1$
        } else {
          logger.error(protocol + " Connection keep-alive failed: " + e.getMessage()); //$NON-NLS-1$
        }
        reschedule = false;
      }
      if (reschedule && !scheduler.isShutdown()) {
        scheduler.schedule(this, rescheduleMillis, MILLISECONDS);
        if (logger.isTraceEnabled()) logger.trace(protocol + " Connection keep-alive rescheduled in {}ms ({})", rescheduleMillis, state); //$NON-NLS-1$
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
      if (logger.isDebugEnabled()) logger.debug(protocol + " Connection keep-alive closed"); //$NON-NLS-1$
    }

    @Override
    protected void finalize() {
      close();
    }
  }
}
