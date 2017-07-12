package easyrmi;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A generic thread factory builder that makes it possible to create a thread factory with control of pretty much every aspect of thread creation.
 * <p/>
 * A class like this should have been part of the standard libraries as it is pure boiler plate code.
 * <p/>
 * The thread factories created by this builder will produce threads with names formatted as:
 * <p/>
 * {@code <factory-name-prefix>-<factory#>-<thread-name-prefix>-<thread#>}
 * <p/>
 * where {@code factory#} and {@code thread#} are running sequence numbers.
 * @author ReneAndersen
 */
final class ThreadFactoryBuilder {
  private final AtomicInteger factoryNumber;

  private String factoryNamePrefix;
  private String threadNamePrefix;
  private boolean isDaemon = true;
  private ThreadGroup threadGroup;
  private int threadPriority = Thread.NORM_PRIORITY;

  ThreadFactoryBuilder() {
    this.factoryNumber = new AtomicInteger(0);

    final SecurityManager s = System.getSecurityManager();
    this.threadGroup = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
    this.factoryNamePrefix = "thread-factory";
    this.threadNamePrefix = "thread";
    this.isDaemon = true;
  }

  /**
   * @param name the new factory name prefix.
   * @return {@code this} builder.
   */
  public ThreadFactoryBuilder factoryNamePrefix(final String name) {
    this.factoryNamePrefix = name;
    return this;
  }

  /**
   * @param name the ew thread name prefix.
   * @return {@code this} builder.
   */
  public ThreadFactoryBuilder threadNamePrefix(final String name) {
    threadNamePrefix = name;
    return this;
  }

  /**
   * @param daemon <code>true</code> if the created threads should be marked as daemon threads (default), <code>false</code> otherwise.
   * @return {@code this} builder.
   */
  public ThreadFactoryBuilder isDaemon(final boolean daemon) {
    this.isDaemon = daemon;
    return this;
  }

  /**
   * @param group the {@link ThreadGroup} that the created threads should belong to. (Default: {@code Thread.currentThread().getThreadGroup()}).
   * @return {@code this} builder.
   */
  public ThreadFactoryBuilder threadGroup(final ThreadGroup group) {
    this.threadGroup = group;
    return this;
  }

  /**
   * @param priority the priority of the created threads. (Default: {@code Thread.NORM_PRIORITY}).
   * @return {@code this} builder.
   */
  public ThreadFactoryBuilder threadPriority(final int priority) {
    this.threadPriority = priority;
    return this;
  }

  /**
   * @return A new instance of {@link ThreadFactory} configured with the current values of this builder.
   */
  public ThreadFactory build() {
    return new ThreadFactoryImpl(this);
  }

  private static final class ThreadFactoryImpl implements ThreadFactory {
    private static final String SEPARATOR = "-";

    private final AtomicInteger threadNumber;
    private final String factoryName;
    private final String threadNamePrefix;
    private final boolean isDaemon;
    private final ThreadGroup threadGroup;
    private final int threadPriority;

    private ThreadFactoryImpl(final ThreadFactoryBuilder builder) {
      this.threadNumber = new AtomicInteger(0);
      this.factoryName = name(builder.factoryNamePrefix, builder.factoryNumber);
      this.threadNamePrefix = builder.threadNamePrefix;
      this.isDaemon = builder.isDaemon;
      this.threadGroup = builder.threadGroup;
      this.threadPriority = builder.threadPriority;
    }

    /** {@inheritDoc} */
    @Override
    public Thread newThread(final Runnable r) {
      final Thread t = new Thread(threadGroup, r, name(factoryName, name(threadNamePrefix, threadNumber)), 0);
      t.setDaemon(isDaemon);
      t.setPriority(threadPriority);
      return t;
    }

    private static String name(final String prefix, final AtomicInteger sequence) {
      final StringBuilder name = new StringBuilder(prefix);
      if (!prefix.endsWith(SEPARATOR)) {
        name.append(SEPARATOR);
      }
      name.append(sequence.incrementAndGet());
      return name.toString();
    }

    private static String name(final String... strings) {
      final StringBuilder name = new StringBuilder();

      String sep = "";
      for (final String string : strings) {
        name.append(sep).append(string);
        if (!string.endsWith(SEPARATOR)) {
          sep = SEPARATOR;
        }
      }
      return name.toString();
    }
  }
}
