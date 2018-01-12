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
package easysettings;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for objects containing configuration settings. With this class it is possible to handle configuration settings with
 * externally defined values in a standardized and decentralized way.
 * <p/>
 * The suggested way to use this class is to extend it to a local settings class, and then declare each configuration value as a 
 * public member of type {@link Value} (or one of its descendants):
 * <pre>
 *   class MySettings extends McConfigurationSettings {
 *     MySettings() {
 *       super("my-settings-prefix.");
 *     }
 *
 *     public StringValue  strValue  = new StringValue("str-setting");
 *     public LongValue    longValue = new LongValue("long-setting", 0L);
 *     public BooleanValue boolValue = new BooleanValue("boolean-setting", false);
 *     // etc...
 *   }
 * </pre>
 * <p/>
 * The configuration values will obtain their value from a shared instance of {@link Reader}. Default implementations exist for 
 * reading values from {@link Properties} objects, and specifically from the global system properties. It is also possible to
 * implement your own reader and pass it to the configuration settings constructor.
 * <p/>
 * It's possible to subscribe to change events on the individual configuration values so you get notified when that value changes,
 * e.g.:
 * <pre>
 *   MySettings mySettings = new MySettings();
 *
 *   mySettings.strValue.addListener(new McConfigurationSetting.Listener() {
 *     void changed(Value newValue) {
 *       ...
 *     }
 *   }
 * </pre>
 *
 * @author ReneAndersen
 */
public abstract class ConfigurationSettings {
  private static final Logger logger = LoggerFactory.getLogger(ConfigurationSettings.class);

  private static <T> void notifyListeners(final Value<T> value, final Iterable<Listener<T>> listeners) {
    for (final Listener<T> listener : listeners) {
      try {
        listener.changed(value);
      } catch (final Throwable cause) {
        if (logger.isErrorEnabled()) logger.error("Exception in listener on value '" + value + "': " + listener, cause); //$NON-NLS-1$ //$NON-NLS-2$
      }
    }
  }

  private final Reader reader; 
  private final Map<String, Value<?>> values = new HashMap<>();

  private <T> void register(final Value<T> value) {
    if (values.put(value.name(), value) != null) {
      if (logger.isErrorEnabled()) logger.error("Duplicate configuration value: " + value); //$NON-NLS-1$
    }
  }

  /**
   * Create and reload configuration settings read from system properties with the specified prefix.
   */
  public ConfigurationSettings(final String prefix) {
    this(new FromSystemProperties(prefix));
  }

  /**
   * Create and reload the configuration settings.
   */
  public ConfigurationSettings(final Reader reader) {
    this.reader = reader;
    reload();
  }

  public interface Reader {
    /**
     * Read the configured value for the specified setting name.
     * @param name the setting name.
     * @return the configuration value, or {@code null} if the value hasn't been set.
     */
    String readSetting(final String name);
  }

  /**
   * Reload all configuration values.
   */
  public void reload() {
    for (final Value<?> value : values.values()) {
      try {
        value.reload();
      } catch (final Throwable cause) {
        if (logger.isErrorEnabled()) logger.error("Error reading configuration setting value: " + value, cause); //$NON-NLS-1$
      }
    }
  }

  /**
   * Print all settings and values.
   * @param separator the separator to put between each setting/value pair.
   */
  public String print(final String separator) {
    final StringBuilder sb = new StringBuilder(); 
    String sep = "";
    for (final Value<?> value : values.values()) {
      sb.append(sep);
      sb.append(value.toString());
      sep = separator;
    }
    return sb.toString();
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("ConfigurationSettings [").append(reader).append("]:\n\t");
    sb.append(print("\n\t"));
    return sb.toString();
  }

  /**
   * Configuration settings read from a {@link Properties} object.
   */
  public static class FromProperties implements Reader {
    private final String prefix;
    private final Properties properties;

    /**
     * @param prefix the prefix to prepend to the setting names to form property keys.
     * @param properties the properties object.
     */
    public FromProperties(final String prefix, final Properties properties) {
      this.prefix = prefix;
      this.properties = properties;
    }
    
    /**
     * @return the prefix being prepended to the setting names to form property keys.
     */
    public String getPrefix() {
      return prefix;
    }

    /** {@inheritDoc} */
    @Override
    public String readSetting(final String name) {
      return properties.getProperty(prefix + name);
    }
    
    /** {@inheritDoc} */
    @Override
    public String toString() {
      return String.format("FromProperties [prefix=%s]", prefix);
    }
  }

  /**
   * Configuration settings read from the global system properties.
   */
  public static class FromSystemProperties extends FromProperties {
    /**
     * @param prefix the prefix to prepend to the setting names to form a properties key.
     */
    public FromSystemProperties(final String prefix) {
      super(prefix, System.getProperties());
    }
    
    /** {@inheritDoc} */
    @Override
    public String toString() {
      return String.format("FromSystemProperties [prefix=%s]", getPrefix());
    }
  }

  /**
   * Interface for a configuration setting value listener.
   * @param <T>
   */
  public interface Listener<T> {
    /**
     * Called when the value changes.
     * @param value the value.
     */
    void changed(Value<T> value);
  }

  /**
   * Interface for a configuration settings value.
   * @param <T>
   */
  public interface Value<T> {
    /**
     * @return the name of this value.
     */
    String name();

    /**
     * Set a new value and notify all listeners.
     * @param newValue the new value.
     * @return the previous value.
     */
    T set(T newValue);

    /**
     * @return the current value.
     */
    T get();

    /**
     * Reload the value from the underlying configuration settings. If the value is different from the current value it is set as
     * the new value and all listeners are notified of the change.
     * @return the new value, or {@code null} if the current value was left unchanged.
     */
    T reload();

    /**
     * Add a new listener.
     * @param listener the listener to add.
     * @return {@code true} if the listener was added, {@code false} if the listener was already added.
     */
    boolean addListener(Listener<T> listener);

    /**
     * Remove a listener.
     * @param listener the listener to remove.
     * @return {@code true} if the listener was found (and removed), {@code false} otherwise.
     */
    boolean removeListener(Listener<T> listener);
  }

  /**
   * Abstract base class for a configuration settings value.
   * @param <T>
   */
  public abstract class BaseValue<T> implements Value<T> {
    private final String name;
    private final T defaultValue;
    private final AtomicReference<T> value = new AtomicReference<>();
    private final AtomicReference<String> image = new AtomicReference<>();
    private final Set<Listener<T>> listeners = new HashSet<>();

    /**
     * @param name the name of the configuration setting value.
     * @param defaultValue the default value of the configuration setting.
     */
    protected BaseValue(final String name, final T defaultValue) {
      this.name = name;
      this.defaultValue = defaultValue;
      set(defaultValue);
      register(this);
    }

    /**
     * Notify all listeners of a change to this value.
     */
    protected final void changed() {
      notifyListeners(this, listeners);
    }

    /**
     * Read a value from a string representation.
     * @param stringValue the string representation.
     * @return the value read, or {@code null} if a value could not be read.
     */
    protected abstract T valueOf(final String stringValue);

    /** {@inheritDoc} */
    @Override
    public String name() {
      return name;
    }

    /** {@inheritDoc} */
    @Override
    public final T set(final T newValue) {
      try {
        return value.getAndSet(newValue);
      } finally {
        changed();
      }
    }

    /** {@inheritDoc} */
    @Override
    public T get() {
      return value.get();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized T reload() {
      final String stringValue = reader.readSetting(name);
      if(stringValue != null) {
        if (image.get() != null && !image.get().equals(stringValue)) {
          final T newValue = valueOf(stringValue);
          if (newValue != null) {
            image.set(stringValue);
            set(newValue);
            if (logger.isTraceEnabled()) logger.trace("Read new value for setting '{}': '{}'", name, newValue);
            return value.get();
          } else {
            if (logger.isTraceEnabled()) logger.trace("Failed to read new value for setting '{}': '{}'", name, stringValue);
          }
        } else {
          if (logger.isTraceEnabled()) logger.trace("Keeping existing value for setting '{}': '{}'", name, image.get());
        }
      } else {
        image.set(null);
        set(defaultValue);
        if (logger.isTraceEnabled()) logger.trace("Reverting to default value for setting '{}': '{}'", name, defaultValue);
      }
      return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean addListener(final Listener<T> listener) {
      return listeners.add(listener);
    }

    /** {@inheritDoc} */
    @Override
    public boolean removeListener(final Listener<T> listener) {
      return listeners.remove(listener);
    }


    /** {@inheritDoc} */
    @Override
    public String toString() {
      return name + "=" + get(); //$NON-NLS-1$
    }
  }

  /**
   * A value of type {@link String}.
   */
  public class StringValue extends BaseValue<String> {
    /**
     * @param name the name of this configuration settings value.
     */
    public StringValue(final String name) {
      super(name, ""); //$NON-NLS-1$
    }

    /** {@inheritDoc} */
    @Override
    protected String valueOf(final String stringValue) {
      return stringValue;
    }
  }

  /**
   * Java 7 stand-in for 'java.util.Function'.
   * @param <Arg>
   * @param <Res>
   */
  public interface Function<Arg, Res> {
    Res apply(Arg arg);
  }
  
  /**
   * A parsable value of type {@code V}.
   * @param V the type of the value
   */
  public class ParsableValue<V> extends BaseValue<V> {
    private Function<String, V> valueOf;

    /**
     * @param name the name of this configuration settings value.
     * @param defaultValue the default value.
     * @param valueOf a function for reading a numeric value from a string.
     */
    public ParsableValue(final String name, final V defaultValue, Function<String, V> valueOf) {
      super(name, defaultValue);
      this.valueOf = valueOf;
    }

    /** {@inheritDoc} */
    @Override
    protected final V valueOf(final String stringValue) {
      try {
        return valueOf.apply(stringValue);
      } catch (final Exception e) {
        if (logger.isErrorEnabled()) logger.error("Error reading value '{}' of setting '{}': {}", //$NON-NLS-1$
                                                  new Object[] {stringValue, this, e.getMessage()});
        return null;
      }
    }
  }
  
  /**
   * A value of type {@link Integer}.
   */
  public class IntegerValue extends ParsableValue<Integer> {
    /**
     * @param name the name of this configuration settings value.
     * @param defaultValue the default value.
     */
    public IntegerValue(final String name, final int defaultValue) {
      super(name, defaultValue, new Function<String, Integer>() {
        @Override
        public Integer apply(String arg) {
          return Integer.valueOf(arg);
        }
      });
    }
  }

  
  /**
   * A value of type {@link Long}.
   */
  public class LongValue extends ParsableValue<Long> {
    /**
     * @param name the name of this configuration settings value.
     * @param defaultValue the default value.
     */
    public LongValue(final String name, final long defaultValue) {
      super(name, defaultValue, new Function<String, Long>() {
        @Override
        public Long apply(String arg) {
          return Long.valueOf(arg);
        }
      });
    }
  }

  /**
   * A value of type {@link Boolean}.
   */
  public class BooleanValue extends ParsableValue<Boolean> {
    /**
     * @param name the name of this configuration settings value.
     * @param defaultValue the default value.
     */
    public BooleanValue(final String name, final boolean defaultValue) {
      super(name, defaultValue, new Function<String, Boolean>() {
        @Override
        public Boolean apply(String arg) {
          return Boolean.parseBoolean(arg);
        }
      });
    }
  }
}
