import java.net.InetSocketAddress;
import java.util.concurrent.ForkJoinPool;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.lang.System.out;
import static java.net.InetAddress.getLoopbackAddress;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.jboss.netty.buffer.ChannelBuffers.EMPTY_BUFFER;

public class Bench {

  public static void main(final String... args) throws InterruptedException {
    final InetSocketAddress address =
        new InetSocketAddress(getLoopbackAddress(), 4711);

    final ProgressMeter meter = new ProgressMeter();

    final int threads;

    if (args.length > 0) {
      threads = Integer.parseInt(args[0]);
    } else {
      threads = Runtime.getRuntime().availableProcessors();
    }

    out.printf("address: %s%n", address);
    out.printf("threads: %s%n", threads);

    final ForkJoinPool executor = forkJoinPool(threads);

    final Server server = new Server(address, new RequestHandler() {
      @Override
      public void handleRequest(final Request request, final RequestContext context) {
        context.reply(request.makeReply(418));
      }
    }, executor);

    final Client client = new Client(address, executor);

    final int n = 1000;

    for (int i = 0; i < n; i++) {
      send(client, meter);
    }

    sleepUninterruptibly(1, DAYS);
  }

  private static ForkJoinPool forkJoinPool(final int threads) {
    return new ForkJoinPool(threads,
                                                   ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                                                   new Thread.UncaughtExceptionHandler() {
                                                     @Override
                                                     public void uncaughtException(final Thread t,
                                                                                   final Throwable e) {
                                                       e.printStackTrace();
                                                     }
                                                   }, true);
  }

  private static void send(final Client client, final ProgressMeter meter) {
    client.send(new Request(EMPTY_BUFFER), new ReplyHandler() {
      @Override
      public void handleReply(final Reply reply) {
        meter.inc(1, 0);
        send(client, meter);
      }
    });
  }
}
