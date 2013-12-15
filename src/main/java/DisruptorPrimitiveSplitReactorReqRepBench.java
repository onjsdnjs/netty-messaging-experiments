import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.common.util.concurrent.AbstractService;

import com.lmax.disruptor.AlertException;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.WaitStrategy;

import org.jboss.netty.buffer.ChannelBuffer;

import java.util.List;
import java.util.concurrent.Semaphore;

import jsr166.concurrent.atomic.AtomicBoolean;

import static com.lmax.disruptor.RingBuffer.createSingleProducer;
import static java.lang.Math.min;
import static java.lang.System.out;

public class DisruptorPrimitiveSplitReactorReqRepBench {

  private static class ReplyEvent {

    public static final EventFactory<ReplyEvent> FACTORY = new Factory();

    long clientId;
    long id;

    // Used by the reactor
    EventQueue<ReplyEvent> queue;

    static class Factory implements EventFactory<ReplyEvent> {

      @Override
      public ReplyEvent newInstance() {
        return new ReplyEvent();
      }
    }
  }

  static class RequestEvent {

    public static final EventFactory<RequestEvent> FACTORY = new Factory();

    long clientId;
    long id;

    static class Factory implements EventFactory<RequestEvent> {

      @Override
      public RequestEvent newInstance() {
        return new RequestEvent();
      }
    }
  }

  static class Server extends AbstractExecutionThreadService {

    private final RingBuffer<RequestEvent> requests;
    private final RingBuffer<ReplyEvent> replies;
    private final SequenceBarrier barrier;
    private final Sequence sequence = new Sequence();
    private final int batchSize;

    Server(final RingBuffer<RequestEvent> requests,
           final RingBuffer<ReplyEvent> replies, final int batchSize) {
      this.requests = requests;
      this.replies = replies;
      this.batchSize = batchSize;
      this.barrier = requests.newBarrier();
      requests.addGatingSequences(sequence);
    }

    @SuppressWarnings({"InfiniteLoopStatement", "LoopStatementThatDoesntLoop"})
    @Override
    public void run() {
      while (true) {
        try {
          while (true) {
            process();
          }
        } catch (InterruptedException | AlertException e) {
          e.printStackTrace();
          return;
        } catch (TimeoutException ignore) {
        }
      }
    }

    private void process() throws InterruptedException, TimeoutException, AlertException {

      final long last = sequence.get();
      final long lo = last + 1;
      final long hi = barrier.waitFor(lo);

      final int count = (int) (hi - last);
      int remaining = count;
      long seq = lo;

      while (remaining > 0) {
        final int batch = Math.min(batchSize, remaining);
        final long batchLo = seq;
        final long batchHi = seq + batch - 1;
        process(batchLo, batchHi, batch);
        seq += batch;
        remaining -= batch;
      }
    }

    private void process(final long lo, final long hi, final int count) {
      final long replyLast = replies.getCursor();
      final long replyLo = replyLast + 1;
      final long replyHi = replies.next(count);

      for (int i = 0; i < count; i++) {
        final RequestEvent requestEvent = requests.get(lo + i);
        final long clientId = requestEvent.clientId;
        final ReplyEvent replyEvent = replies.get(replyLo + i);
        replyEvent.clientId = clientId;
        replyEvent.id = requestEvent.id;
      }

      sequence.set(hi);
      replies.publish(replyLo, replyHi);
    }
  }

  static class Client extends AbstractExecutionThreadService {

    private final long id;
    private final RingBuffer<RequestEvent> requests;
    private final RingBuffer<ReplyEvent> replies;

    public volatile long q1, q2, q3, q4, q5, q6, q7 = 7L;
    public volatile long p1, p2, p3, p4, p5, p6, p7 = 7L;
    private long requestIdCounter = 0;
    public volatile long r1, r2, r3, r4, r5, r6, r7 = 7L;
    public volatile long s1, s2, s3, s4, s5, s6, s7 = 7L;

    private final SequenceBarrier barrier;
    private final Sequence sequence = new Sequence();
    private final int concurrency;

    Client(final long id,
           final RingBuffer<RequestEvent> requests,
           final RingBuffer<ReplyEvent> replies, final int concurrency) {
      this.id = id;
      this.requests = requests;
      this.replies = replies;
      this.concurrency = concurrency;
      this.barrier = replies.newBarrier();
      this.replies.addGatingSequences(sequence);
    }

    @Override
    protected void startUp() throws Exception {
      sendBatch();
    }

    private void sendBatch() {
      final long hi = requests.next(concurrency);
      final long lo = hi - (concurrency - 1);
      for (long seq = lo; seq <= hi; seq++) {
        send(seq);
      }
      requests.publish(lo, hi);
    }

    @Override
    protected void run() {
      try {
        while (true) {
          process();
        }
      } catch (InterruptedException | AlertException e) {
        e.printStackTrace();
        return;
      } catch (TimeoutException ignore) {
      }
    }

    private void process() throws InterruptedException, TimeoutException, AlertException {
      final long last = sequence.get();
      final long lo = last + 1;
      final long hi = barrier.waitFor(lo);
      final int count = (int) (hi - last);
      final long requestLo = requests.getCursor() + 1;
      final long requestHi = requests.next(count);
      for (long i = 0; i < count; i++) {
        replies.get(lo + i);
        send(requestLo + i);
      }
      sequence.set(hi);
      requests.publish(requestHi);
    }

    private void send(final long seq) {
      // TODO (dano): make it possible to interrupt sequence()
      final RequestEvent event = requests.get(seq);
      event.clientId = id;
      event.id = requestIdCounter++;
    }
  }

  private static final int BUFFER_SIZE = 1024 * 64;

  public static void main(final String... args) {

    final int batchSize;
    if (args.length > 0) {
      batchSize = Integer.parseInt(args[0]);
    } else {
      batchSize = 10;
    }

    final int concurrency;
    if (args.length > 1) {
      concurrency = Integer.parseInt(args[1]);
    } else {
      concurrency = 10000;
    }

    out.printf("batch size: %s%n", batchSize);
    out.printf("concurrency: %s%n", concurrency);

    final Reactor reactor = new Reactor();

    // Client
    final long clientId = 17;
    final Client client = new Client(clientId,
                                     reactor.clientRequestQueue(),
                                     reactor.clientReplyQueue(clientId, batchSize), concurrency);

    // Server
    final Server server = new Server(reactor.serverRequestQueue(batchSize),
                                     reactor.serverReplyQueue(), batchSize);

    // Start
    reactor.startAsync();
    client.startAsync();
    server.startAsync();

    final ProgressMeter meter = new ProgressMeter(new Supplier<ProgressMeter.Counters>() {
      @Override
      public ProgressMeter.Counters get() {
        return new ProgressMeter.Counters(client.requestIdCounter, 0);
      }
    });


  }

  static class EventQueue<E> {

    private final RingBuffer<E> buffer;
    private final long batchSize;

    private volatile long q1, q2, q3, q4, q5, q6, q7 = 7L;
    private volatile long p1, p2, p3, p4, p5, p6, p7 = 7L;
    private long prev = -1;
    private long sequence = -1;
    private volatile long r1, r2, r3, r4, r5, r6, r7 = 7L;
    private volatile long s1, s2, s3, s4, s5, s6, s7 = 7L;

    EventQueue(final RingBuffer<E> buffer, final long batchSize) {
      this.buffer = buffer;
      this.batchSize = batchSize;
    }

    public void publish() {
      if (sequence == prev) {
        return;
      }

      buffer.publish(prev + 1, sequence);
      prev = sequence;
    }

    public void maybePublish() {
      if (sequence - prev >= batchSize) {
        publish();
      }
    }
  }

  private static class Reactor extends AbstractService {

    public static final WaitStrategy WAIT_STRATEGY = new LiteBlockingWaitStrategy();
    private final List<AbstractEventHandler> handlers = Lists.newArrayList();
    private final RequestDispatcher requestDispatcher = new RequestDispatcher();
    private final ReplyDispatcher replyDispatcher = new ReplyDispatcher();

    @Override
    protected void doStart() {
      requestDispatcher.startAsync();
      replyDispatcher.startAsync();
    }

    @Override
    protected void doStop() {
      requestDispatcher.stopAsync();
      replyDispatcher.stopAsync();
    }

    static class RequestDispatcher extends AbstractExecutionThreadService {

      public volatile long q1, q2, q3, q4, q5, q6, q7 = 7L;
      public volatile long p1, p2, p3, p4, p5, p6, p7 = 7L;
      private int serverIndex = 0;
      public volatile long r1, r2, r3, r4, r5, r6, r7 = 7L;
      public volatile long s1, s2, s3, s4, s5, s6, s7 = 7L;

      private final Semaphore semaphore = new Semaphore(0);
      private final List<RequestEventHandler> handlers = Lists.newArrayList();

      private final List<EventQueue<RequestEvent>> queues = Lists.newArrayList();

      @SuppressWarnings({"ForLoopReplaceableByForEach", "InfiniteLoopStatement"})
      @Override
      protected void run() throws Exception {
        while (true) {
          semaphore.acquire();
          semaphore.drainPermits();
          for (int i = 0; i < handlers.size(); i++) {
            final RequestEventHandler handler = handlers.get(i);
            handler.process();
          }
          for (int i = 0; i < queues.size(); i++) {
            queues.get(i).publish();
          }
        }
      }

      public RingBuffer<RequestEvent> inQueue() {
        final RequestEventHandler handler = new RequestEventHandler();
        handlers.add(handler);
        return handler.getQueue();
      }

      public RingBuffer<RequestEvent> outQueue(final long batchSize) {
        final RingBuffer<RequestEvent> queue = createSingleProducer(
            RequestEvent.FACTORY, BUFFER_SIZE, WAIT_STRATEGY);
        queues.add(new EventQueue<>(queue, batchSize));
        return queue;
      }

      private class RequestEventHandler extends AbstractEventHandler<RequestEvent> {

        private RequestEventHandler() {
          super(RequestEvent.FACTORY, semaphore);
        }

        @Override
        protected void handle(final RingBuffer<RequestEvent> in, final long lo, final long hi) {
          final int count = (int) (hi - lo + 1);
          final int batch = count / queues.size();
          for (int i = 0; i < count; ) {
            final int n = min(batch, count);
            final EventQueue<RequestEvent> out = nextQueue();
            final long serverHi = out.buffer.next(n);
            final long serverLo = serverHi - n + 1;
            for (int j = 0; j < n; j++, i++) {
              final long serverSeq = serverLo + j;
              final long seq = lo + i;
              handle(out, serverSeq, in.get(seq));
            }
            out.sequence = serverHi;
            out.maybePublish();
          }
        }

        private EventQueue<RequestEvent> nextQueue() {
          serverIndex++;
          if (serverIndex >= queues.size()) {
            serverIndex = 0;
          }
          return queues.get(serverIndex);
        }

        private void handle(final EventQueue<RequestEvent> queue,
                            final long seq,
                            final RequestEvent event) {
          final RequestEvent serverEvent = queue.buffer.get(seq);
          serverEvent.clientId = event.clientId;
          serverEvent.id = event.id;
        }
      }
    }

    static class ReplyDispatcher extends AbstractExecutionThreadService {

      private final Semaphore semaphore = new Semaphore(0);
      private final List<AbstractEventHandler> handlers = Lists.newArrayList();

      private final List<EventQueue<ReplyEvent>> queues = Lists.newArrayList();
      private final List<EventQueue<ReplyEvent>> map = Lists.newArrayList();

      @SuppressWarnings({"ForLoopReplaceableByForEach", "InfiniteLoopStatement"})
      @Override
      protected void run() throws Exception {
        while (true) {
          semaphore.acquire();
          semaphore.drainPermits();
          for (AbstractEventHandler handler : handlers) {
            handler.process();
          }
          for (int i = 0; i < queues.size(); i++) {
            final EventQueue<?> queue = queues.get(i);
            if (queue != null) {
              queue.publish();
            }
          }
        }
      }

      public RingBuffer<ReplyEvent> outQueue(final long clientId, final long batchSize) {
        final RingBuffer<ReplyEvent> buffer = createSingleProducer(
            ReplyEvent.FACTORY, BUFFER_SIZE, WAIT_STRATEGY);
        final EventQueue<ReplyEvent> queue = new EventQueue<>(buffer, batchSize);
        while (map.size() <= clientId) {
          map.add(null);
        }
        queues.add(queue);
        map.set((int) clientId, queue);
        return buffer;
      }

      public RingBuffer<ReplyEvent> inQueue() {
        final ReplyEventHandler handler = new ReplyEventHandler();
        handlers.add(handler);
        return handler.getQueue();
      }


      private class ReplyEventHandler extends AbstractEventHandler<ReplyEvent> {

        private ReplyEventHandler() {
          super(ReplyEvent.FACTORY, semaphore);
        }

        @Override
        protected void handle(final RingBuffer<ReplyEvent> queue, final long lo,
                              final long hi) {
          for (long i = lo; i <= hi; i++) {
            final ReplyEvent event = queue.get(i);
            final EventQueue<ReplyEvent> clientReplyQueue = map.get(((int) event.clientId));
            clientReplyQueue.sequence = clientReplyQueue.buffer.next();
            handle(clientReplyQueue, event);
            clientReplyQueue.maybePublish();
          }
        }

        protected void handle(final EventQueue<ReplyEvent> queue, final ReplyEvent event) {
          final long seq = queue.sequence;
          final ReplyEvent clientReplyEvent = queue.buffer.get(seq);
          clientReplyEvent.clientId = event.clientId;
          clientReplyEvent.id = event.id;
        }
      }
    }

    public RingBuffer<RequestEvent> clientRequestQueue() {
      return requestDispatcher.inQueue();
    }

    public RingBuffer<RequestEvent> serverRequestQueue(final long batchSize) {
      return requestDispatcher.outQueue(batchSize);
    }

    public RingBuffer<ReplyEvent> serverReplyQueue() {
      return replyDispatcher.inQueue();
    }

    public RingBuffer<ReplyEvent> clientReplyQueue(final long clientId, final long batchSize) {
      return replyDispatcher.outQueue(clientId, batchSize);
    }


    private static abstract class AbstractEventHandler<E> implements WaitStrategy {

      private final RingBuffer<E> queue;
      private final Sequence sequence = new Sequence();
      private final AtomicBoolean notified = new AtomicBoolean();
      private final Semaphore semaphore;

      protected AbstractEventHandler(final EventFactory<E> eventFactory,
                                     final Semaphore semaphore) {
        this.semaphore = semaphore;
        this.queue = createSingleProducer(eventFactory, BUFFER_SIZE, this);
        queue.addGatingSequences(sequence);
      }

      public RingBuffer<E> getQueue() {
        return queue;
      }

      @Override
      public long waitFor(final long sequence, final Sequence cursor,
                          final Sequence dependentSequence,
                          final SequenceBarrier barrier)
          throws AlertException, InterruptedException, TimeoutException {
        throw new UnsupportedOperationException();
      }

      @Override
      public void signalAllWhenBlocking() {
        if (notified.compareAndSet(false, true)) {
          semaphore.release();
        }
      }

      void process() throws InterruptedException, TimeoutException, AlertException {
        if (!notified.get()) {
          return;
        }

        notified.set(false);

        final long last = sequence.get();
        final long lo = last + 1;
        final long hi = queue.getCursor();
        if (hi == last) {
          return;
        }
        handle(queue, lo, hi);
        sequence.set(hi);
      }

      protected abstract void handle(final RingBuffer<E> queue, final long lo, final long hi);
    }


  }
}
