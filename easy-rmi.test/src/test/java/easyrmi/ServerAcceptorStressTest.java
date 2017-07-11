package easyrmi;

import java.rmi.Remote;
import java.util.concurrent.Callable;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Stress test of the remote server acceptor.
 * @author ReneAndersen
 */
public class ServerAcceptorStressTest extends RemoteBase {
  @BeforeClass
  public static void beforeClass() throws Exception {
    RemoteBase.beforeClass();
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
    final int sz = 100;
    runTasks(sz, 1, new ProxyTask());
  }


  @Test
  public void testAcceptorThroughput250() throws Exception {
    final int sz = 250;
    runTasks(sz, 1, new ProxyTask());
  }


  @Test
  public void testAcceptorThroughput500() throws Exception {
    final int sz = 500;
    runTasks(sz, 1, new ProxyTask());
  }


  @Test
  public void testAcceptorParallelism100() throws Exception {
    final int sz = 100;
    runTasks(sz, sz, new ProxyTask());
  }


  @Test
  public void testAcceptorParallelism250() throws Exception {
    final int sz = 250;
    runTasks(sz, sz, new ProxyTask());
  }


  @Test
  public void testAcceptorParallelism500() throws Exception {
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
