import com.google.common.collect.Lists;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.execution.ChannelEventRunnable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import jsr166.concurrent.AbstractExecutorService;
import jsr166.concurrent.ForkJoinPool;
import jsr166.concurrent.ForkJoinWorkerThread;

import static java.lang.Math.abs;

public class ChannelShardedForkJoinPool extends AbstractExecutorService {

  private final ExecutorService[] executors;
  private boolean shutdown;
  private final List<WorkerThread> workerThreads = Lists.newCopyOnWriteArrayList();

  public ChannelShardedForkJoinPool(final int corePoolSize) {
    executors = new ForkJoinPool[corePoolSize];
    for (int i = 0; i < corePoolSize; i++) {
      executors[i] = forkJoinPool(1);
    }
  }

  @Override
  public void shutdown() {
    shutdown = true;
    for (final ExecutorService executorService : executors) {
      executorService.shutdown();
    }
  }

  @Override
  public List<Runnable> shutdownNow() {
    final List<Runnable> allRunnables = Lists.newArrayList();
    for (final ExecutorService executorService : executors) {
      final List<Runnable> runnables = executorService.shutdownNow();
      allRunnables.addAll(runnables);
    }
    return allRunnables;
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    for (final ExecutorService executorService : executors) {
      if (!executorService.isTerminated()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean awaitTermination(final long timeout, final TimeUnit unit)
      throws InterruptedException {
    for (final ExecutorService executorService : executors) {
      final boolean terminated = executorService.awaitTermination(timeout, unit);
      if (!terminated) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void execute(final Runnable task) {
    if (!(task instanceof ChannelEventRunnable)) {
      throw new IllegalArgumentException();
    }
    final ChannelEventRunnable r = (ChannelEventRunnable) task;
    executor(r.getEvent().getChannel()).execute(task);
  }

  private ExecutorService executor(final Channel channel) {
    final int key = abs(channel.hashCode());
    final int index = key % executors.length;
    return executors[index];
  }

  private ForkJoinPool forkJoinPool(final int threads) {
    return new ForkJoinPool(threads,
                            new ForkJoinPool.ForkJoinWorkerThreadFactory() {
                              @Override
                              public ForkJoinWorkerThread newThread(
                                  final ForkJoinPool pool) {
                                WorkerThread workerThread = new WorkerThread(pool);
                                workerThreads.add(workerThread);
                                return workerThread;
                              }
                            },
                            new Thread.UncaughtExceptionHandler() {
                              @Override
                              public void uncaughtException(final Thread t,
                                                            final Throwable e) {
                                e.printStackTrace();
                              }
                            }, true
    );
  }

  public List<WorkerThread> getWorkers() {
    return Lists.newArrayList(workerThreads);
  }
}
