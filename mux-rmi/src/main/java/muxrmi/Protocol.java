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

import static muxrmi.Protocol.State.ACCEPT;
import static muxrmi.Protocol.State.CLOSED;
import static muxrmi.Protocol.State.INITIAL;
import static muxrmi.Protocol.State.RUNNING;

import java.io.EOFException;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common client/server implementation of the remote connection protocol
 * @author ReneAndersen
 */
public final class Protocol implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(Protocol.class);

  private static final AtomicLong ID = new AtomicLong();
  
  // The current shared state of a protocol instance.
  enum State { INITIAL, ACCEPT, RUNNING, CLOSED }

  // Shared state between nested protocol instances.
  static class SharedState {
    volatile State state = INITIAL;
    volatile long lastUpdateMillis = System.currentTimeMillis();

    void update(final State newState) {
      state = newState;
      lastUpdateMillis = System.currentTimeMillis();
    }
  }

  SharedState getSharedState() {
    return ctx.state;
  }

  // The context of a protocol instances is a Registry, with the following additional elements:
  // - A Connection object.
  // - A SharedState object.
  private static class Context extends Registry {
    final Connection con;
    final SharedState state;
    final ClassLoader classLoader;

    Context(final Socket socket, final ClassLoader classLoader) {
      this.con = new Connection(socket, classLoader);
      this.state = new SharedState();
      this.classLoader = classLoader;
    }

    Context(final Context parent) {
      super(parent);
      this.con = parent.con;
      this.state = parent.state;
      this.classLoader = parent.classLoader;
    }
  }

  private static final class Identity {
    private final long id = ID.getAndIncrement();
    
    @Override
    public String toString() {
      return "Protocol#" + id;
    }
  }
  
  private final Set<Class<?>> remoteClasses = new HashSet<>();
  private final Identity identity = new Identity();
  private final Context ctx;
  private final boolean isServer;
  private final boolean topLevel;
  
  private volatile boolean isClosed = false;

  private Protocol(final Context ctx, final boolean isServer, final boolean topLevel) {
    this.ctx = ctx;
    this.isServer = isServer;
    this.topLevel = topLevel;

    logger.debug("{}", this); //$NON-NLS-1$
  }

  private Protocol(final Context parentContext) {
    this(new Context(parentContext), false, false);
    ctx.state.update(INITIAL);
  }

  /**
   * Create a top-level protocol instance which communicates on the specified socket connection.
   * @param isServer {@code true} iff the protocol instance should be in server mode, {@code false} otherwise.
   * @param socket an accepted socket connection to the remote client.
   * @param classLoader The class loader to use to load remote class references.
   */
  private Protocol(final boolean isServer, final Socket socket, final ClassLoader classLoader) {
    this(new Context(socket, classLoader), isServer, true);
  }
  
  /**
   * Create a top-level client-side protocol instance on the specified socket connection.
   * @param socket the socket.
   * @param classLoader a class loader for the objects read on the remote protocol.
   * @return the protocol instance.
   */
  static Protocol client(final Socket socket, final ClassLoader classLoader) {
    return new Protocol(false, socket, classLoader);
  }

  /**
   * Create a top-level server-side protocol instance on the specified socket connection.
   * @param socket the socket.
   * @param registry the initial {@link Registry}.
   * @param classLoader a class loader for the objects read on the remote protocol.
   * @return the protocol instance.
   */
  static Protocol server(final Socket socket, final Registry registry, final ClassLoader classLoader) {
    final Protocol protocol = new Protocol(true, socket, classLoader);
    protocol.ctx.init(registry);
    return protocol;
  }

  /**
   * Run the protocol command loop until a result is available.
   * <p/>
   * The result will be the return value from a method call, or the terminating {@link Command} value:
   * <ul>
   * <li>{@link Command#OK} if a method call completed without a return value</li>
   * <li>{@link Command#END} if the remote client terminated the connection.</li>
   * </ul>
   *
   * @return the result value.
   * @throws Exception if the command loop terminates with an error.
   */
  Object run() throws Exception {
    try {
      return runCommandLoop();
    } catch (final InvocationTargetException e) {
      // Exception thrown by a local method invocation.
      throw e;
    } catch (final IOException e) {
      logger.debug("{} I/O error: {}", this, e.toString()); //$NON-NLS-1$
      throw e;
    } catch (final NotBoundException e) {
      logger.debug("{} Not bound: {}", this, e.getMessage()); //$NON-NLS-1$
      throw e;
    } catch (final Throwable cause) {
      logger.error(this + " Unhandled exception", cause); //$NON-NLS-1$
      throw cause;
    }
  }

  /**
   * Check the the specified class is registered remotely.
   * @param classType the class type to check.
   * @throws NotBoundException if the class was not registed remotely.
   * @throws Exception any exception thrown during the remote call.
   */
  void bind(final Class<?> classType) throws NotBoundException, Exception {
    invokeRemote(Command.BIND, ClassRef.forClass(classType));
  }

  /**
   * Called to perform a remote method invocation.
   * @param classRef a {@link ClassRef} for the class of the invoked method.
   * @param method the {@link Method} to invoke.
   * @return the return value from the method invocation.
   * @throws InvocationTargetException wrapping any exception thrown by the method.
   * @throws Exception any other exception that occurred during the method invocation.
   */
  Object invokeRemote(final ClassRef classRef, final Method method, final Object[] args) throws InvocationTargetException, Exception {
    return invokeRemote(Command.CALL, outgoingCall(classRef, method, args));
  }

  /**
   * Send a continuation command to the remote party.
   * @throws IOException
   */
  void sendContinue() throws IOException {
    write(Command.CONTINUE);
  }

  /**
   * Create a proxy stub for the specified remote API. The class reference must be to an interface type.
   * @param apiClass the remote API interface class.
   * @return a proxy stub for the remote API.
   * @throws InvalidClassException if the specified class does not implement the {@link Remote} interface.
   */
  @SuppressWarnings("unchecked")
  <API> API createProxy(final Class<API> apiClass) throws InvalidClassException {
    return (API) createProxy(ClassRef.forClass(apiClass));
  }

  /**
   * Create a proxy stub for the specified class reference. The class reference must be to an interface type.
   * @param classRef the class reference.
   * @return a proxy stub for a remote class.
   */
  Object createProxy(final ClassRef classRef) {
    final Class<?> classType = classRef.classType;
    if (!classType.isInterface()) {
      throw new IllegalArgumentException(classRef + " is not an interface"); //$NON-NLS-1$
    }
    return Proxy.newProxyInstance(new RemoteProxyClassLoader(ctx.classLoader, classType.getClassLoader(), getClass().getClassLoader()),
                                  new Class[] {classType, ProxyClass.class},
                                  new ProxyInvocationHandler(classRef, ctx));
  }

  /**
   * @return A new instance of {@link ProxyObjectFactory} for creating local proxy objects for remote interfaces.
   */
  ProxyObjectFactory newProxyObjectFactory() {
    return new ProxyObjectFactory();
  }

  final class ProxyObjectFactory {
    private ClassLoader classLoader;
    private InvocationHandler invocationHandler;

    ProxyObjectFactory withClassLoader(final ClassLoader newClassLoader) {
      this.classLoader = newClassLoader;
      return this;
    }

    ProxyObjectFactory withInvocationHandler(final InvocationHandler newInvocationHandler) {
      this.invocationHandler = newInvocationHandler;
      return this;
    }

    <API> API create(final Class<API> classType) {
      if (invocationHandler == null) invocationHandler = new ProxyInvocationHandler(ClassRef.forClass(classType), ctx);
      if (classLoader == null) classLoader = new RemoteProxyClassLoader(ctx.classLoader, classType.getClassLoader(), getClass().getClassLoader());
      return createProxy(classLoader, invocationHandler, classType, ProxyClass.class);
    }
  }

  @SuppressWarnings("unchecked")
  static <API> API createProxy(final ClassLoader classLoader,
                               final InvocationHandler invocationHandler,
                               final Class<?>... interfaces) {
    for (final Class<?> classType : interfaces) {
      if (!classType.isInterface()) {
        throw new IllegalArgumentException(classType + " is not an interface"); //$NON-NLS-1$
      }
    }

    return (API) Proxy.newProxyInstance(classLoader, interfaces, invocationHandler);
  }

  /**
   * @return {@code true} iff we have a connected socket reference, {@code false} otherwise.
   */
  boolean isConnected() {
    return ctx.con.isConnected();
  }

  /**
   * Forcible disconnect this protocol instance.
   */
  void disconnect() {
    ctx.con.close();
    ctx.state.update(CLOSED);
  }

  /**
   * @return {@code true} iff {@link #close()} has been called, {@code false} otherwise. 
   */
  boolean isClosed() {
    return isClosed;
  }
  
  /**
   * Close this protocol instance and release all network resources - but only if we're a top-level instance.
   */
  @Override
  public synchronized void close() {
    if (isClosed) return;
    
    logger.debug("{} Closed", identity); //$NON-NLS-1$
    isClosed = true;
    if (topLevel) {
      try {
        if (isConnected()) {
          handleEnd(true);
        }
      } catch (final Exception e) {
        if (logger.isDebugEnabled()) logger.debug(this + " Error in close", e); //$NON-NLS-1$
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return identity + " ["
         + (isServer ? "server" : "client")
         + ", " + (topLevel ? "topLevel" : "nested")
         + ", connection=" + ctx.con + "]";
  }

  /** {@inheritDoc} */
  @Override
  protected void finalize() {
    if (topLevel) {
      disconnect();
    }
  }

  /**
   * Enumeration with the legal protocol commands.
   */
  enum Command {
    /** A continuation command which does not require a response */
    CONTINUE(0),
    /** A confirmation with no additional data, e.g. the result of calling a method returning 'void' */
    OK(1),
    /** An error response, followed by: {@link Exception} */
    ERROR(2),
    /** A service bind request, followed by: classRef:{@link ClassRef} */
    BIND(3),
    /** A method call command, followed by: methodRef:{@link MethodRef} */
    CALL(4),
    /** A result value for the last command: {@link Object}(result) */
    RESULT(5),
    /** The final command, used to terminate the connection. */
    END(6);

    private final int command;

    private Command(final int command) {
      this.command = command;
    }

    /**
     * @return the byte value (as an int) of this command.
     */
    public int get() {
      return command;
    }

    static final Command[] COMMANDS = Command.values();

    /**
     * @param command the byte value (as an int) of the command.
     * @return the {@link Command} instance corresponding to the byte value.
     * @throws EOFException if the command value is an EOF value (-1).
     */
    public static Command get(final int command) throws EOFException {
      if (command >= 0 && command < COMMANDS.length) {
        return COMMANDS[command];
      }
      if (command == -1) {
        throw new EOFException("EOF"); //$NON-NLS-1$
      }
      throw new IllegalArgumentException("Illegal command value: " + command); //$NON-NLS-1$
    }
  }

  /**
   * Signature interface for a proxied class reference.
   * Public
   */
  public interface ProxyClass {
    /**
     * @return the ClassRef of the remote class.
     */
    ClassRef getClassRef();
  }

  /**
   * Invocation handler on a local proxy stub for a remote class reference.
   */
  static class ProxyInvocationHandler extends RemoteInvocationHandler implements ProxyClass {
    private final ClassRef classRef;
    private final Context context;

    protected ProxyInvocationHandler(final ClassRef classRef, final Context context) {
      super(classRef.classType);
      this.classRef = classRef;
      this.context = context;
    }

    /** {@inheritDoc} */
    @Override
    public ClassRef getClassRef() {
      return classRef;
    }

    @Override
    protected Object invokeRemote(final Method method,
                                  final Object[] args) throws InvocationTargetException, Exception {
      final Object result;
      try (final Protocol protocol = new Protocol(context)) {
        result = protocol.invokeRemote(classRef, method, args);
      }
      return result;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + " [classRef=" + classRef + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }
  }

  /**
   * Placeholder stub for a remote class.
   * <p/>
   * This interface is {@code public} to allow reflective class-loader access.
   */
  static final class RemoteStub implements Serializable {
    private static final long serialVersionUID = 1L;

    final ClassRef remoteClass;

    RemoteStub(final ClassRef remoteClass) {
      this.remoteClass = remoteClass;
    }

    @Override
    public String toString() {
      return "RemoteStub:" + remoteClass; //$NON-NLS-1$
    }
  }

  /**
   * Object representing a reference to a remote class or class instance.
   */
  public static final class ClassRef implements Serializable {
    private static final long serialVersionUID = 1L;

    final Class<?> classType;
    final Integer instanceId;

    ClassRef(final Class<?> classType, final Integer instanceId) {
      this.classType = classType;
      this.instanceId = instanceId;

      if (!classType.isInterface())
        throw new IllegalArgumentException(classType + " is not an interface"); //$NON-NLS-1$
    }

    static ClassRef forClass(final Class<?> classType) {
      return new ClassRef(classType, null);
    }

    static ClassRef forInstance(final Class<?> classType, final Object instance) {
      return new ClassRef(getInterfaceType(classType, instance.getClass()), instance.hashCode());
    }

    static Class<?> getInterfaceType(final Class<?> classType, final Class<?> instanceType) {
      if (classType.isInterface()) {
        return classType;
      }
      final List<Class<?>> instanceInterfaces = Arrays.asList(instanceType.getInterfaces());
      for (final Class<?> i : classType.getInterfaces()) {
        if (Remote.class.isAssignableFrom(i)) {
          return i;
        }
      }
      for (final Class<?> i : instanceInterfaces) {
        if (Remote.class.isAssignableFrom(i)) {
          return i;
        }
      }
      throw new IllegalArgumentException("No remote interface found for object with class type '" + classType +  //$NON-NLS-1$
                                         "' and instance type '" + instanceType + "'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    String id() {
      return classType + (instanceId != null ? "@" + instanceId : "");  //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
      return "ClassRef [" + id() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }
  }

  /**
   * Object representing an invocation of a remote method.
   */
  static final class MethodRef implements Serializable {
    private static final long serialVersionUID = 1L;

    private final ClassRef classRef;
    private final String methodName;
    private final Object[] args;

    MethodRef(final ClassRef classRef, final Method method, final Object[] args) {
      this.classRef = classRef;
      this.methodName = method.getName();
      this.args = args;
    }

    static String id(final ClassRef classRef, final String methodName) {
      return classRef.id() + "#" + methodName; //$NON-NLS-1$
    }

    String id() {
      return id(classRef, methodName);
    }

    @Override
    public String toString() {
      return "MethodRef [" + id() + "(" + Arrays.toString(args) + ")]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
  }

  private Object runCommandLoop() throws Exception {
    do {
      final Command command = read();
      try {
        switch (command) {
          case CONTINUE:
            break;

          case OK:
            return command;

          case ERROR:
            final Exception e = handleError();
            if (e != null) throw e;
            break;

          case BIND:
            handleBind();
            break;

          case CALL:
            handleCall();
            break;

          case RESULT:
            final Object result = handleResult();
            if (isServer) {
              throw new UnsupportedOperationException("RESULT: " + result); //$NON-NLS-1$
            }
            return result;

          case END:
            handleEnd(false);
            return command;
        }
      } catch (final Exception e) {
        handleError(e, isServer);
      }
    } while (true);
  }

  /**
   * Read the next {@link Command} from the input stream.
   * @return the command.
   * @throws Exception if a command could not be read.
   */
  private final Protocol.Command read() throws Exception {
    ctx.state.update(ACCEPT);
    final int cmd = ctx.con.in().read();
    ctx.state.update(RUNNING);
    final Command command = Command.get(cmd);

    if (logger.isTraceEnabled()) logger.trace("{} <- {}", identity, command); //$NON-NLS-1$
    return command;
  }

  /**
   * Read the next {@link Object} from the input stream.
   * @return the object.
   * @throws Exception (IOException | ClassCastException) if an object could not be read
   */
  private final Object readObject() throws Exception {
    final Object res = ctx.con.in().readObject();

    if (logger.isTraceEnabled()) logger.trace("{} <- {}", identity, res); //$NON-NLS-1$
    return res;
  }

  /**
   * Write a {@link Command} and zero or more arguments to the output stream.
   * @param command the command.
   * @param args the arguments.
   * @throws Exception if the command could not be written.
   */
  private final synchronized void write(final Protocol.Command command, final Object... args) throws IOException {
    if (logger.isTraceEnabled()) logger.trace("{} -> {} {}", new Object[] {identity, command, Arrays.toString(args)}); //$NON-NLS-1$

    final ObjectOutputStream out = ctx.con.out();
    out.reset();
    out.write(command.get());
    for (final Object arg : args) {
      out.writeObject(arg);
    }
    out.flush();
    ctx.state.update(RUNNING);
  }

  /**
   * Read an {@link Exception} from the input stream.
   * @return the exception.
   * @throws Exception if an exception could not be read.
   */
  private Exception handleError() throws Exception {
    final Throwable t = (Throwable) readObject();
    if (t instanceof Exception) {
      return (Exception) t;
    }
    return new Exception(t);
  }

  /**
   * Called when an error occurs locally.
   * @param e the error.
   * @throws Exception the error if it should be re-thrown locally, or any other exception that occurred while handling the
   *                   exception.
   */
  private void handleError(final Exception e, final boolean handleRemotely) throws Exception {
    if (handleRemotely) {
      try {
        write(Command.ERROR, e);
      } catch (final Exception e2) {
        if (logger.isErrorEnabled()) {
          final String msg = this + " Error sending ERROR response: " + e;
          if (logger.isDebugEnabled())
            logger.debug(msg, e2);
          else
            logger.error("{}: {}", msg, e2.toString());
          throw e2;
        }
      }
    } else {
      throw e;
    }
  }

  /**
   * Read a {@link ClassRef} and verify that it corresponds to a registered class reference.
   * @throws NotBoundException if the received class reference was not registered.
   * @throws Exception if an error occurs while reading this request.
   */
  private void handleBind() throws NotBoundException, Exception {
    final ClassRef classRef = (ClassRef) readObject();
    ctx.getReference(classRef.id());
    write(Command.OK);
  }

  /**
   * Read and invoke a method call from the input stream.
   * @throws Exception if a method call could not be read.
   */
  private void handleCall() throws Exception {
    final MethodRef methodRef = (MethodRef) readObject();
    try {
      sendResult(invokeLocal(incomingCall(methodRef)));
    } catch (final Exception e) {
      handleError(e, true);
    }
  }

  /**
   * Read a result {@link Object} from the input stream and handle it.
   * @return the result.
   * @throws Exception if an object could not be read.
   */
  private Object handleResult() throws Exception {
    final Object result = readObject();
    return result;
  }

  /**
   * Handle the END command and disconnect this protocol instance.
   * @param isInitiator {@code true} if the END command was initiated locally, {@code false} if it was initiated remotely.
   * @throws Exception if thrown while disconnecting.
   */
  private void handleEnd(final boolean isInitiator) throws Exception {
    try {
      if (isServer) {
        write(Command.END);
      }
      else {
        if (isInitiator) {
          write(Command.END);
          Object result;
          do {
            result = run();
          } while (result != Command.END);
        }
      }
    } finally {
      disconnect();
    }
  }

  private Object invokeLocal(final MethodRef methodRef) throws NoSuchMethodError, InvocationTargetException, Exception {
    final Object api = ctx.getReference(methodRef.classRef.id());
    final Method method = ctx.getMethod(methodRef.id());
    final Object result = method.invoke(api, methodRef.args);
    return outgoing(result, method.getReturnType());
  }

  private Object invokeRemote(final Command command, final Object obj) throws InvocationTargetException, Exception {
    if (obj != null) {
      write(command, obj);
    } else {
      write(command);
    }
    final Object result = run();
    if (result instanceof Command) {
      switch ((Command) result) {
        case OK:
          return null;
        case END:
          throw new IOException("Remote session ended"); //$NON-NLS-1$
        default:
          throw new IOException("Unexpected result value: " + result); //$NON-NLS-1$
      }
    }
    return incoming(result);
  }

  private void sendResult(final Object result) throws Exception {
    if (result != null) {
      write(Command.RESULT, result);
    } else {
      write(Command.OK);
    }
  }

  /**
   * Create an incoming method call representation. Any argument that matches the remote stub placeholder will be replace by
   * a proxy stub for the remote object.
   * @param methodName the method name.
   * @param args the argument array.
   * @return the incoming method call representation.
   */
  private MethodRef incomingCall(final MethodRef methodRef) {
    if (methodRef.args != null) {
      int i = 0;
      for (final Object arg : methodRef.args) {
        methodRef.args[i] = incoming(arg);
        ++i;
      }
    }
    return methodRef;
  }

  /**
   * Create an outgoing method call representation. Any argument that matches a registered remote object type will be replaced by
   * a remote stub placeholder object.
   * @param methodName the method name.
   * @param args the argument array.
   * @return the outgoing method call representation.
   */
  private MethodRef outgoingCall(final ClassRef classRef, final Method method, final Object[] args) {
    if (args != null) {
      final Class<?>[] parameterTypes = method.getParameterTypes();
      int i = 0;
      try {
        for (final Object arg : args) {
          args[i] = outgoing(arg, parameterTypes[i]);
          ++i;
        }
      } catch (final Exception e) {
        throw new IllegalArgumentException("[" + (i+1) + "] " + args[i], e); //$NON-NLS-1$ //$NON-NLS-2$
      }
    }
    return new MethodRef(classRef, method, args);
  }

  private Object incoming(final Object arg) {
    if (arg instanceof RemoteStub) {
      final RemoteStub remoteStub = (RemoteStub) arg;
      final Object localInstance = ctx.findReference(remoteStub.remoteClass.id());
      if (localInstance != null) {
        return localInstance;
      } else {
        return createProxy(remoteStub.remoteClass);
      }
    }
    return arg;
  }

  private Object outgoing(final Object arg, final Class<?> classType) {
    if (arg instanceof ProxyClass) {
      final ClassRef remoteClassRef = ((ProxyClass) arg).getClassRef();
      return new RemoteStub(remoteClassRef);
    }
    if (arg != null && isRemote(arg.getClass())) {
      final ClassRef remoteClassRef = ClassRef.forInstance(classType, arg);
      ctx.registerReference(remoteClassRef, arg);
      ctx.registerMethods(remoteClassRef);
      return new RemoteStub(remoteClassRef);
    }
    return arg;
  }

  /**
   * Check whether the specified {@link Class} type or one of its super-types is registered as a remote class.
   * <p/>
   * If the class type is not registered but one of its super-types are, then the class type will itself be registered
   * to speed up future look-ups.
   * @param localClass the class type to check.
   * @return {@code true} iff the class or one of its super-types is registered as a remote class, {@code false} otherwise.
   */
  private boolean isRemote(final Class<?> localClass) {
    if (remoteClasses.contains(localClass)) {
      return true;
    }
    if (Remote.class.isAssignableFrom(localClass)) {
      setRemote(localClass);
      return true;
    }
    for (final Class<?> remoteClass : remoteClasses) {
      if (remoteClass.isAssignableFrom(localClass)) {
        setRemote(localClass);
        return true;
      }
    }
    return false;
  }

  /**
   * Register the specified {@link Class} type as a remote class.
   * @param remoteClass the remote class type.
   */
  private void setRemote(final Class<?> remoteClass) {
    remoteClasses.add(remoteClass);
    if (logger.isTraceEnabled()) logger.trace("Remotes: {} -> {}", remoteClass, remoteClasses);
  }

}

