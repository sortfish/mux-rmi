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
 * <p/>
 * A connection consist of a connected socket and lazily initialized object streams
 * based on the input/output streams of this socket. The class loader passed into
 * the connection class will be used to load all objects read from the object
 * input stream.
 * <p/>
 * The class is not public as it is only used by remote servers and clients.
 * 
 * @author Rene Andersen
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
      throw new IOException("Error getting object input stream: " + e.getMessage(), e);
    }
  }

  ObjectOutputStream out() throws IOException {
    try {
      return out.get();
    } catch (final IOException e) {
      throw e;
    } catch (final Exception e) {
      throw new IOException("Error getting object output stream" + e.getMessage(), e);
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
