package org.jctools.util;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class TestUtil {
    public static final int CONCURRENT_TEST_DURATION = Integer.getInteger("org.jctools.concTestDurationMs", 500);
    public static final int CONCURRENT_TEST_THREADS = Integer.getInteger("org.jctools.concTestThreads", Math.min(4, Runtime.getRuntime().availableProcessors()));
    public static final int TEST_TIMEOUT = 30000;
    private static final AtomicInteger threadIndex = new AtomicInteger();
    public static void sleepQuietly(long timeMs) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(timeMs));
    }

    public static void startWaitJoin(AtomicBoolean stop, List<Thread> threads) throws InterruptedException
    {
        startWaitJoin(stop, threads, 1);
    }

    public static void startWaitJoin(
        AtomicBoolean stop,
        List<Thread> threads,
        int waitDivisor) throws InterruptedException
    {
        int waitMs = CONCURRENT_TEST_DURATION / waitDivisor;

        for (Thread t : threads) t.start();
        Thread.sleep(waitMs);
        stop.set(true);
        for (Thread t : threads)  t.join();
        stop.set(false);
    }

    public static void threads(Runnable runnable, int count, List<Thread> threads)
    {
        if (count <= 0)
            count = CONCURRENT_TEST_THREADS - 1;

        for (int i = 0; i < count; i++)
        {
            Thread thread = new Thread(runnable);
            thread.setName("JCTools test thread-" + threadIndex.getAndIncrement());
            threads.add(thread);
        }
    }
    public static final class Val
    {
        public int value;
    }
}
