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

import java.io.Closeable;
import java.io.IOException;
import java.io.InvalidClassException;
import java.rmi.Remote;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import muxrmi.RemoteClient;


/**
 * Test the remote server registry
 * @author ReneAndersen
 */
public class ServerRegistryTest extends RemoteTestBase {
  private static final Logger logger = LoggerFactory.getLogger(ServerRegistryTest.class);

  public interface UnremoteInterface {}

  private class UnremoteObject extends UnremoteParent implements Closeable, UnremoteInterface {
    public void close() throws IOException {}
  }

  @Test
  public void testUnremoteClass() throws Exception {
    logger.info("Running testUnremoteClass()");
    final UnremoteObject unremoteObject = new UnremoteObject();
    try {
      server.register(unremoteObject);
      server.unregister(unremoteObject);
      Assert.fail("Unremote object was successfully registered");
    } catch (final InvalidClassException e) {
      // expected
    }
  }

  private class RemoteObject implements Remote {}

  @Test
  public void testAlreadyRegistered() throws Exception {
    logger.info("Running testAlreadyRegistered()");
    final RemoteObject obj = new RemoteObject();
    server.register(obj);

    try {
      final RemoteObject obj2 = new RemoteObject();
      server.register(obj2);
      Assert.fail("A second instance was successfully registered");
    } catch (final IllegalArgumentException e) {
      // expected
    }
  }

  @Test
  public void testUnregisterUnregistered() {
    logger.info("Running testUnregisterUnregistered()");
    final RemoteObject obj = new RemoteObject();
    final boolean result = server.unregister(obj);
    Assert.assertFalse("An unregistered object was successfully unregistered", result);
  }

  private class UnremoteParent implements UnremoteInterface, Runnable {
    public void run() {}
  }

  public interface RemoteInterface extends Remote {}

  private class PartiallyRemoteObject extends UnremoteObject implements UnremoteInterface, RemoteInterface {}

  private class PartiallyRemoteSubObject extends PartiallyRemoteObject implements UnremoteInterface {}

  @Test
  public void testRegisterPartiallyRemote() throws Exception {
    logger.info("Running testRegisterPartiallyRemote()");
    final RemoteInterface obj = new PartiallyRemoteSubObject();
    final List<Class<?>> registered = server.register(obj);
    try {
      Assert.assertEquals(registered.toString(), 1, registered.size());
      Assert.assertEquals(RemoteInterface.class, registered.get(0));

      try (final RemoteClient client = createClient(getClass().getClassLoader())) {
        final RemoteInterface remoteObj = client.connect(RemoteInterface.class);
        Assert.assertTrue(client.dispose(remoteObj));
  
        connectNotBound(UnremoteInterface.class, client);
      }
    } finally {
      server.unregister(obj);
    }
  }
}
