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

import static java.util.concurrent.TimeUnit.SECONDS;

import java.net.Socket;
import java.net.SocketException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import easysettings.ConfigurationSettings;

/**
 * This class collects and applies socket settings to socket instances.
 * @author Rene Andersen
 */
public class SocketSettings extends ConfigurationSettings {
  private static final Logger logger = LoggerFactory.getLogger(SocketSettings.class);

  /** Socket read timeout (SO_TIMEOUT) , in seconds. */
  public final IntegerValue soTimeout = new IntegerValue("so-timeout", 0);
  
  /** The "linger" time (SO_LINGER) for a closed socket, in seconds. */
  public final IntegerValue soLinger = new IntegerValue("so-linger", 0);
  
  /** Whether TCP no-delay (TCP_NODELAY) is enabled. */
  public final BooleanValue tcpNoDelay = new BooleanValue("tcp-no-delay", true);

  /**
   * Read socket settings from the specified reader.
   * @param reader the reader
   */
  public SocketSettings(ConfigurationSettings.Reader reader) {
    super(reader);
    reload();
  }
  
  /**
   * Apply the socket settings to the specified socket.
   * @param socket the socket.
   */
  public void applyTo(final Socket socket) {
    if (logger.isDebugEnabled()) logger.debug("Applying socket settings to '{}': {}", socket, this);
    try {
      socket.setSoTimeout((int)SECONDS.toMillis(soTimeout.get()));
      socket.setSoLinger(soLinger.get() >= 0, soLinger.get());
      socket.setTcpNoDelay(tcpNoDelay.get());
    } catch (final SocketException e) {
      logger.error("Failed to apply socket settings: " + this, e);
    }
  }

  @Override
  public String toString() {
    return String.format("%s", print(", "));
  }
}
