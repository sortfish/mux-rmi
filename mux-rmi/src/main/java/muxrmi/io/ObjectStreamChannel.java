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
package muxrmi.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.Callable;

import javax.net.SocketFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import muxrmi.Identity;
import muxrmi.SocketSettings;

/**
 * Implementation of {@link CommunicationChannel} that uses {@link ObjectInputStream}
 * and {@link ObjectOutputStream} (aka. standard Java serialization) to 
 * read/write {@link Command}s and {@link Object}s. 
 * @author Rene Andersen
 */
public class ObjectStreamChannel implements CommunicationChannel {
  private static final Logger logger = LoggerFactory.getLogger(CommunicationChannel.class);

  private final Identity identity;
  private final Socket socket;
  private final ClassLoader classLoader;
  private final LazyReference<ObjectInputStream> in;
  private final LazyReference<ObjectOutputStream> out;

  /**
   * Create a new object-stream communication channel for the specified socket and class loader.
   * @param socket the socket
   * @param classLoader the class loader
   */
  public ObjectStreamChannel(final Socket socket, final ClassLoader classLoader) {
    this.identity = new Identity();
    this.socket = socket;
    this.classLoader = classLoader;
    this.in = new LazyReference<>(new Callable<ObjectInputStream>() {
      @Override
      public ObjectInputStream call() throws Exception {
        final InputStream i = socket.getInputStream();
        if (i instanceof ObjectInputStream) {
          return (ObjectInputStream) i;
        }
        return new ResolvingObjectInputStream(i, classLoader);
      }
    });
    this.out = new LazyReference<>(new Callable<ObjectOutputStream>() {
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
  
  /**
   * A server-side factory for object-stream communication channels.
   */
  public static final class ServerFactory implements CommunicationChannel.Factory {
    private final ServerSocket serverSocket;
    private final SocketSettings socketSettings;
    private final ClassLoader classLoader;
    
    /**
     * @param serverSocket the server socket to listen on.
     * @param socketSettings the socket settings that should be applied to the socket connections.
     * @param classLoader the class loader.
     */
    public ServerFactory(final ServerSocket serverSocket, final SocketSettings socketSettings, final ClassLoader classLoader) {
      this.serverSocket = serverSocket;
      this.socketSettings = socketSettings;
      this.classLoader = classLoader;
    }
    
    @Override
    public CommunicationChannel create() throws IOException {
      final Socket socket = serverSocket.accept();
      socketSettings.applyTo(socket);
      return new ObjectStreamChannel(socket, classLoader);
    }
    
    @Override
    public void close() throws IOException {
      serverSocket.close();
    }

    @Override
    public boolean isClosed() {
      return serverSocket.isClosed();
    }

    @Override
    public String toString() {
      return String.format("ObjectStreamConnection.ServerFactory [serverSocket=%s, socketSettings=%s, classLoader=%s]",
                           serverSocket, socketSettings, classLoader);
    }
  }
  
  /**
   * A client-side factory for object-stream communication channels. 
   */
  public static final class ClientFactory implements CommunicationChannel.Factory {
    private final SocketFactory socketFactory;
    private final SocketSettings socketSettings;
    private final SocketAddress endpoint;
    private final ClassLoader classLoader;
    private boolean isClosed = false;
    
    /**
     * @param socketFactory the socket factory for creating new outbound socket connections.
     * @param socketSettings the socket settings that should be applied to the socket connections.
     * @param endpoint the socket address endpoint to connect to.
     * @param classLoader the class loader.
     */
    public ClientFactory(final SocketFactory socketFactory,
                         final SocketSettings socketSettings,
                         final SocketAddress endpoint,
                         final ClassLoader classLoader) {
      this.socketFactory = socketFactory;
      this.socketSettings = socketSettings;
      this.endpoint = endpoint;
      this.classLoader = classLoader;
    }
    
    @Override
    public CommunicationChannel create() throws IOException {
      if (isClosed)
        throw new IOException("Closed: " + this);
      final Socket socket = socketFactory.createSocket();
      socketSettings.applyTo(socket);
      socket.connect(endpoint);
      return new ObjectStreamChannel(socket, classLoader);
    }
    
    @Override
    public void close() throws IOException {
      isClosed = true;
    }

    @Override
    public boolean isClosed() {
      return isClosed;
    }

    @Override
    public String toString() {
      return String.format("ObjectStreamConnection.ClientFactory [socketFactory=%s, socketSettings=%s, endpoint=%s, classLoader=%s]",
                           socketFactory, socketSettings, endpoint, classLoader);
    }
  }

  @Override
  public Identity id() {
    return identity;
  }

  @Override
  public ClassLoader getClassLoader() {
    return classLoader;
  }

  @Override
  public boolean isConnected() {
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
  public Command readCommand() throws IOException {
    return Command.get(in().read());
  }

  @Override
  public Object readObject() throws IOException, ClassNotFoundException {
    final Object res = in().readObject();

    if (logger.isTraceEnabled()) logger.trace("{} <- {}", identity, res); //$NON-NLS-1$
    return res;
  }

  @Override
  public void writeCommand(Command command) throws IOException {
    final ObjectOutputStream out = out();
    out.reset();
    out.write(command.get());
  }

  @Override
  public void writeObject(final Object obj) throws IOException {
    out().writeObject(obj);
  }
  
  @Override
  public void flush() throws IOException {
    out().flush();
  }

  @Override
  public String toString() {
    return "ObjectStreamConnection:" + socket; //$NON-NLS-1$
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
