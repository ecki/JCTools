/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.queues.unpadded;

import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MessagePassingQueueUtil;
import org.jctools.util.RangeUtil;

import java.util.AbstractQueue;
import java.util.Iterator;

import static org.jctools.util.PortableJvmInfo.CPUs;
import static org.jctools.util.Pow2.isPowerOfTwo;
import static org.jctools.util.Pow2.roundToPowerOfTwo;

/**
 * Use a set number of parallel MPSC queues to diffuse the contention on tail.
 */
abstract class MpscUnpaddedCompoundQueueL0Pad<E> extends AbstractQueue<E> implements MessagePassingQueue<E>
{

}

abstract class MpscUnpaddedCompoundQueueColdFields<E> extends MpscUnpaddedCompoundQueueL0Pad<E>
{
    // must be power of 2
    protected final int parallelQueues;
    protected final int parallelQueuesMask;
    protected final MpscUnpaddedArrayQueue<E>[] queues;

    @SuppressWarnings("unchecked")
    MpscUnpaddedCompoundQueueColdFields(int capacity, int queueParallelism)
    {
        parallelQueues = isPowerOfTwo(queueParallelism) ? queueParallelism
            : roundToPowerOfTwo(queueParallelism) / 2;
        parallelQueuesMask = parallelQueues - 1;
        queues = new MpscUnpaddedArrayQueue[parallelQueues];
        int fullCapacity = roundToPowerOfTwo(capacity);
        RangeUtil.checkGreaterThanOrEqual(fullCapacity, parallelQueues, "fullCapacity");
        for (int i = 0; i < parallelQueues; i++)
        {
            queues[i] = new MpscUnpaddedArrayQueue<E>(fullCapacity / parallelQueues);
        }
    }
}

abstract class MpscUnpaddedCompoundQueueMidPad<E> extends MpscUnpaddedCompoundQueueColdFields<E>
{


    public MpscUnpaddedCompoundQueueMidPad(int capacity, int queueParallelism)
    {
        super(capacity, queueParallelism);
    }
}

abstract class MpscUnpaddedCompoundQueueConsumerQueueIndex<E> extends MpscUnpaddedCompoundQueueMidPad<E>
{
    int consumerQueueIndex;

    MpscUnpaddedCompoundQueueConsumerQueueIndex(int capacity, int queueParallelism)
    {
        super(capacity, queueParallelism);
    }
}

public class MpscUnpaddedCompoundQueue<E> extends MpscUnpaddedCompoundQueueConsumerQueueIndex<E>
{


    public MpscUnpaddedCompoundQueue(int capacity)
    {
        this(capacity, CPUs);
    }

    public MpscUnpaddedCompoundQueue(int capacity, int queueParallelism)
    {
        super(capacity, queueParallelism);
    }

    @Override
    public boolean offer(final E e)
    {
        if (null == e)
        {
            throw new NullPointerException();
        }
        final int parallelQueuesMask = this.parallelQueuesMask;
        int start = (int) (Thread.currentThread().getId() & parallelQueuesMask);
        final MpscUnpaddedArrayQueue<E>[] queues = this.queues;
        if (queues[start].offer(e))
        {
            return true;
        }
        else
        {
            return slowOffer(queues, parallelQueuesMask, start + 1, e);
        }
    }

    private boolean slowOffer(MpscUnpaddedArrayQueue<E>[] queues, int parallelQueuesMask, int start, E e)
    {
        final int queueCount = parallelQueuesMask + 1;
        final int end = start + queueCount;
        while (true)
        {
            int status = 0;
            for (int i = start; i < end; i++)
            {
                int s = queues[i & parallelQueuesMask].failFastOffer(e);
                if (s == 0)
                {
                    return true;
                }
                status += s;
            }
            if (status == queueCount)
            {
                return false;
            }
        }
    }

    @Override
    public E poll()
    {
        int qIndex = consumerQueueIndex & parallelQueuesMask;
        int limit = qIndex + parallelQueues;
        E e = null;
        for (; qIndex < limit; qIndex++)
        {
            e = queues[qIndex & parallelQueuesMask].poll();
            if (e != null)
            {
                break;
            }
        }
        consumerQueueIndex = qIndex;
        return e;
    }

    @Override
    public E peek()
    {
        int qIndex = consumerQueueIndex & parallelQueuesMask;
        int limit = qIndex + parallelQueues;
        E e = null;
        for (; qIndex < limit; qIndex++)
        {
            e = queues[qIndex & parallelQueuesMask].peek();
            if (e != null)
            {
                break;
            }
        }
        consumerQueueIndex = qIndex;
        return e;
    }

    @Override
    public int size()
    {
        int size = 0;
        for (MpscUnpaddedArrayQueue<E> lane : queues)
        {
            size += lane.size();
        }
        return size;
    }

    @Override
    public Iterator<E> iterator()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString()
    {
        return this.getClass().getName();
    }

    @Override
    public boolean relaxedOffer(E e)
    {
        if (null == e)
        {
            throw new NullPointerException();
        }
        final int parallelQueuesMask = this.parallelQueuesMask;
        int start = (int) (Thread.currentThread().getId() & parallelQueuesMask);
        final MpscUnpaddedArrayQueue<E>[] queues = this.queues;
        if (queues[start].failFastOffer(e) == 0)
        {
            return true;
        }
        else
        {
            // we already offered to first queue, try the rest
            for (int i = start + 1; i < start + parallelQueuesMask + 1; i++)
            {
                if (queues[i & parallelQueuesMask].failFastOffer(e) == 0)
                {
                    return true;
                }
            }
            // this is a relaxed offer, we can fail for any reason we like
            return false;
        }
    }

    @Override
    public E relaxedPoll()
    {
        int qIndex = consumerQueueIndex & parallelQueuesMask;
        int limit = qIndex + parallelQueues;
        E e = null;
        for (; qIndex < limit; qIndex++)
        {
            e = queues[qIndex & parallelQueuesMask].relaxedPoll();
            if (e != null)
            {
                break;
            }
        }
        consumerQueueIndex = qIndex;
        return e;
    }

    @Override
    public E relaxedPeek()
    {
        int qIndex = consumerQueueIndex & parallelQueuesMask;
        int limit = qIndex + parallelQueues;
        E e = null;
        for (; qIndex < limit; qIndex++)
        {
            e = queues[qIndex & parallelQueuesMask].relaxedPeek();
            if (e != null)
            {
                break;
            }
        }
        consumerQueueIndex = qIndex;
        return e;
    }

    @Override
    public int capacity()
    {
        return queues.length * queues[0].capacity();
    }


    @Override
    public int drain(Consumer<E> c)
    {
        final int limit = capacity();
        return drain(c, limit);
    }

    @Override
    public int fill(Supplier<E> s)
    {

        return MessagePassingQueueUtil.fillBounded(this, s);
    }

    @Override
    public int drain(Consumer<E> c, int limit)
    {
        return MessagePassingQueueUtil.drain(this, c, limit);
    }

    @Override
    public int fill(Supplier<E> s, int limit)
    {
        if (null == s)
            throw new IllegalArgumentException("supplier is null");
        if (limit < 0)
            throw new IllegalArgumentException("limit is negative:" + limit);
        if (limit == 0)
            return 0;

        final int parallelQueuesMask = this.parallelQueuesMask;
        int start = (int) (Thread.currentThread().getId() & parallelQueuesMask);
        final MpscUnpaddedArrayQueue<E>[] queues = this.queues;
        int filled = queues[start].fill(s, limit);
        if (filled == limit)
        {
            return limit;
        }
        else
        {
            // we already offered to first queue, try the rest
            for (int i = start + 1; i < start + parallelQueuesMask + 1; i++)
            {
                filled += queues[i & parallelQueuesMask].fill(s, limit - filled);
                if (filled == limit)
                {
                    return limit;
                }
            }
            // this is a relaxed offer, we can fail for any reason we like
            return filled;
        }
    }

    @Override
    public void drain(Consumer<E> c, WaitStrategy wait, ExitCondition exit)
    {
        MessagePassingQueueUtil.drain(this, c, wait, exit);
    }

    @Override
    public void fill(Supplier<E> s, WaitStrategy wait, ExitCondition exit)
    {
        MessagePassingQueueUtil.fill(this, s, wait, exit);
    }
}
