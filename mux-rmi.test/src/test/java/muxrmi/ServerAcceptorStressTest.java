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

import java.rmi.Remote;
import java.util.concurrent.Callable;

import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import muxrmi.RemoteClient;

/**
 * Stress test of the remote server acceptor.
 * @author ReneAndersen
 */
public class ServerAcceptorStressTest extends RemoteTestBase {
  private static final Logger logger = LoggerFactory.getLogger(ServerAcceptorStressTest.class);
  
  @BeforeClass
  public static void beforeClass() throws Exception {
    RemoteTestBase.beforeClass();
    final API api = new APIImpl();
    server.register(api);
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("MtTestAcceptor: ")
    .append(super.toString());
    return sb.toString();
  }


  @Test
  public void testAcceptorThroughput100() throws Exception {
    logger.info("Running testAcceptorThroughput100()");
    final int sz = 100;
    runTasks(sz, 1, new ProxyTask());
  }


  @Test
  public void testAcceptorThroughput250() throws Exception {
    logger.info("Running testAcceptorThroughput250()");
    final int sz = 250;
    runTasks(sz, 1, new ProxyTask());
  }


  @Test
  public void testAcceptorThroughput500() throws Exception {
    logger.info("Running testAcceptorThroughput500()");
    final int sz = 500;
    runTasks(sz, 1, new ProxyTask());
  }


  @Test
  public void testAcceptorParallelism100() throws Exception {
    logger.info("Running testAcceptorParallelism100()");
    final int sz = 100;
    runTasks(sz, sz, new ProxyTask());
  }


  @Test
  public void testAcceptorParallelism250() throws Exception {
    logger.info("Running testAcceptorParallelism250()");
    final int sz = 250;
    runTasks(sz, sz, new ProxyTask());
  }


  @Test
  public void testAcceptorParallelism500() throws Exception {
    logger.info("Running testAcceptorParallelism500()");
    final int sz = 500;
    runTasks(sz, sz, new ProxyTask());
  }


  private interface API extends Remote {
    <T> T echo(T arg);

    void sleepFor(long millis) throws InterruptedException;
  }

  private static final class APIImpl implements API {
    @Override
    public <T> T echo(final T arg) {
      return arg;
    }

    @Override
    public void sleepFor(final long millis) throws InterruptedException {
      Thread.sleep(millis);
    }
  }

  private static class ProxyTask implements Callable<Void> {
    private static final int CALLS = 100;
    private static final Object[] ARG = { 42, 3.14, "Hello world!" };

    @Override
    public Void call() throws Exception {
      try (final RemoteClient client = createClient(getClass().getClassLoader())) {
        final API api = client.connect(API.class);
        for (int i = 0; i < CALLS; ++i) {
          api.echo(ARG);
        }
        client.dispose(api);
        return null;
      }
    }
  }
}
