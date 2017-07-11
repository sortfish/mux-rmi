package easyrmi;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class represents an active connection to a remote endpoint.
 */
final class Connection implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(Connection.class);

  private final Socket socket;
  private final Connection.LazyReference<ObjectInputStream> in;
  private final Connection.LazyReference<ObjectOutputStream> out;

  Connection(final Socket socket, final ClassLoader classLoader) {
    this.socket = socket;
    this.in = new Connection.LazyReference<>(new Callable<ObjectInputStream>() {
      @Override
      public ObjectInputStream call() throws Exception {
        final InputStream i = socket.getInputStream();
        if (i instanceof ObjectInputStream) {
          return (ObjectInputStream) i;
        }
        return new ResolvingObjectInputStream(i, classLoader);
      }
    });
    this.out = new Connection.LazyReference<>(new Callable<ObjectOutputStream>() {
      @Override
      public ObjectOutputStream call() throws Exception {
        final OutputStream o = socket.getOutputStream();
        if (o instanceof ObjectOutputStream) {
          return (ObjectOutputStream) o;
        }
        return new ObjectOutputStream(o);
      }
    });
  }

  SocketAddress endpoint() {
    return socket.getLocalSocketAddress();
  }

  ObjectInputStream in() throws IOException {
    try {
      return in.get();
    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  ObjectOutputStream out() throws IOException {
    try {
      return out.get();
    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  boolean isConnected() {
    return socket.isConnected() && !socket.isClosed();
  }

  @Override
  public void close() {
     if (!socket.isClosed()) {
       try {
        socket.close();
      } catch (final Exception e) {
        logger.error("Error closing socket: " + socket, e); //$NON-NLS-1$
      }
     }
  }

  @Override
  public String toString() {
    return "Connection:" + socket; //$NON-NLS-1$
  }

  private static final class LazyReference<T> {
    private final Callable<T> initializer;
    private T reference;

    LazyReference(final Callable<T> initializer) {
      this.initializer = initializer;
    }

    T get() throws Exception {
      if (reference == null) {
        synchronized (this) {
          if (reference == null) {
            reference = initializer.call();
          }
        }
      }
      return reference;
    }
  }

  private final class ResolvingObjectInputStream extends ObjectInputStream {
    private final ClassLoader classLoader;

    public ResolvingObjectInputStream(final InputStream in, final ClassLoader classLoader) throws IOException {
      super(in);
      this.classLoader = classLoader;
    }

    @Override
    protected Class<?> resolveClass(final ObjectStreamClass desc) throws IOException, ClassNotFoundException {
      try {
        return classLoader.loadClass(desc.getName());
      } catch (final Exception e) {
        return super.resolveClass(desc);
      }
    }
  }
}
