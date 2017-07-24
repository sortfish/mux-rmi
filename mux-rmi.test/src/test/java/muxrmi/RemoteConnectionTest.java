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

import java.net.ConnectException;
import java.net.SocketException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import muxrmi.RemoteClient;

/**
 * @author ReneAndersen
 */
public class RemoteConnectionTest extends RemoteTestBase {
  private static final Logger logger = LoggerFactory.getLogger(RemoteConnectionTest.class);

  enum Method { ECHO, EXCEPTION, ECHO_CALLBACK_IN_CALLBACK, EXCEPTION_CALLBACK_IN_CALLBACK, ECHO_ON_RETURNED_CALLBACK };

  public interface API extends Remote {
    <T> T echo(T arg);

    <E extends Exception> void throwException(E e) throws E;

    Object callback(Method method, API callbackApi, Object arg) throws Exception;
  }

  private static class APIImpl implements API {
    @Override
    public <T> T echo(final T arg) {
      return arg;
    }

    @Override
    public <E extends Exception> void throwException(final E e) throws E {
      throw e;
    }

    @Override
    public Object callback(final Method method, final API callbackApi, final Object arg) throws Exception {
      switch (method) {
        case ECHO:
          return callbackApi.echo(arg);

        case EXCEPTION:
          callbackApi.throwException((Exception) arg);
          return null;

        case ECHO_CALLBACK_IN_CALLBACK:
          return callbackApi.callback(Method.ECHO, callbackApi, arg);

        case EXCEPTION_CALLBACK_IN_CALLBACK:
          return callbackApi.callback(Method.EXCEPTION, callbackApi, arg);

        case ECHO_ON_RETURNED_CALLBACK:
          final API cbapi = (API)callbackApi.callback(Method.ECHO, callbackApi, callbackApi);
          return cbapi.echo(arg);

        default:
          throw new IllegalArgumentException("Invalid callback method: " + method);
      }
    }
  }

  private static RemoteClient client;
  private static API api;

  @BeforeClass
  public static void beforeClass() throws Exception {
    logger.info("Running beforeClass()");
    RemoteTestBase.beforeClass();
    final API api = new APIImpl();
    Assert.assertEquals(API.class, server.register(api).get(0));
  }

  @Override
  @Before
  public void before() throws Exception {
    logger.info("Running before()");
    super.before();
    client = createClient(getClass().getClassLoader());
    api = client.connect(API.class);
  }

  @After
  public void after() throws Exception {
    logger.info("Running after()");
    client.dispose(api);
    client.close();
  }

  private interface UnboundInterface extends Remote {
    void foo();
  }

  @Test
  public void testUnboundInterface() throws Exception {
    logger.info("Running testUnboundInterface(");
    connectNotBound(UnboundInterface.class, client);
  }

  @Test
  public void testClientConnectFailure() throws Exception {
    logger.info("Running testClientConnectFailure()");
    service.close();
    try {
      final RemoteClient unconnectedClient = createClient(getClass().getClassLoader());
      final API unexpected = unconnectedClient.connect(API.class);
      client.dispose(unexpected);
      Assert.fail("Proxy was created for unconnected client");
    } catch (final ConnectException e) {
      logger.info("Expected error: {}", e.toString());
    }
  }

  @Test
  public void testClientDisconnectFailure() throws Exception {
    logger.info("Running testClientDisconnectFailure()");
    service.close();
    try {
      final Object unexpected = api.echo(42);
      Assert.fail("Unexpected response from disconnected client: " + unexpected);
    } catch (final Exception e) {
      Assert.assertTrue(e.toString(), e.getCause() instanceof SocketException);
    }
  }

  @Test
  public void testEcho() throws Exception {
    logger.info("Running testEcho()");
    final Object[] args = new Object[] {42, 3.1415, "Hello world!"};
    for (final Object arg : args) {
      final Object result = api.echo(arg);
      Assert.assertEquals(arg, result);
    }
  }

  @Test
  public void testException() throws Exception {
    logger.info("Running testException()");
    final Exception[] excs = new Exception[] { new RuntimeException("ouch!"), new RemoteException("bad call!"), new NullPointerException() };
    for (final Exception ex : excs) {
      try {
        api.throwException(ex);
      } catch (final Exception e) {
        Assert.assertEquals(e.toString(), ex.getClass(), e.getClass());
        Assert.assertEquals(ex.getMessage(), e.getMessage());
      }
    }
  }

  @Test
  public void testEchoCallback() throws Exception {
    logger.info("Running testEchoCallback()");
    final API cbapi = new APIImpl();
    final Object arg = Integer.valueOf(42);
    final Object result = api.callback(Method.ECHO, cbapi, 42);
    Assert.assertEquals(arg, result);
  }

  @Test
  public void testExceptionCallback() throws Exception {
    logger.info("Running testExceptionCallback()");
    final API cbapi = new APIImpl();
    final Exception ex = new RuntimeException("ouch!");
    try {
      api.callback(Method.EXCEPTION, cbapi, ex);
    } catch (final Exception e) {
      Assert.assertEquals(e.toString(), ex.getClass(), e.getClass());
      Assert.assertEquals(ex.getMessage(), e.getMessage());
    }
  }

  @Test
  public void testEchoCallbackInCallback() throws Exception {
    logger.info("Running testEchoCallbackInCallback()");
    final API cbapi = new APIImpl();
    final Object arg = Integer.valueOf(42);
    final Object result = api.callback(Method.ECHO_CALLBACK_IN_CALLBACK, cbapi, arg);
    Assert.assertEquals(arg, result);
  }

  @Test
  public void testExceptionCallbackInCallback() throws Exception {
    logger.info("Running testExceptionCallbackInCallback()");
    final API cbapi = new APIImpl();
    final Exception ex = new RuntimeException("ouch!");
    try {
      api.callback(Method.EXCEPTION_CALLBACK_IN_CALLBACK, cbapi, ex);
    } catch (final Exception e) {
      logger.debug("Caught exception: {}", e.toString());
      Assert.assertEquals(e.toString(), ex.getClass(), e.getClass());
      Assert.assertEquals(ex.getMessage(), e.getMessage());
    }
  }

  @Test
  public void testEchoOnReturnedCallback() throws Exception {
    logger.info("Running testEchoOnReturnedCallback()");
    final API cbapi = new APIImpl();
    final Object arg = Integer.valueOf(42);
    final Object result = api.callback(Method.ECHO_ON_RETURNED_CALLBACK, cbapi, arg);
    Assert.assertEquals(arg, result);
  }
}
