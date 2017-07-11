package easyrmi;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.net.Socket;
import java.net.SocketException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import easysettings.ConfigurationSettings;

/**
 * This class collects and applies socket settings to socket instances.
 */
class SocketSettings extends ConfigurationSettings.FromSystemProperties {
  private static final Logger logger = LoggerFactory.getLogger(SocketSettings.class);

  private static final String PREFIX = SocketSettings.class.getPackage().getName() + "."; //$NON-NLS-1$

  private final IntegerValue soTimeout = new IntegerValue("so-timeout", 0);
  private final IntegerValue soLinger = new IntegerValue("so-linger", 0);
  private final BooleanValue tcpNoDelay = new BooleanValue("tcp-no-delay", true);

  SocketSettings() {
    super(PREFIX);
    logger.info("SocketSettings: {}", this); //$NON-NLS-1$
  }

  void applyTo(final Socket socket) {
    if (logger.isDebugEnabled()) logger.debug("Applying socket settings to '{}': {}", socket, this); //$NON-NLS-1$
    try {
      socket.setSoTimeout((int)SECONDS.toMillis(soTimeout.get()));
      socket.setSoLinger(soLinger.get() >= 0, soLinger.get());
      socket.setTcpNoDelay(tcpNoDelay.get());
    } catch (final SocketException e) {
      logger.error("Failed to apply socket settings: " + this, e); //$NON-NLS-1$
    }
  }

  @Override
  public String toString() {
    return String.format("%s", print(", "));
  }
}
