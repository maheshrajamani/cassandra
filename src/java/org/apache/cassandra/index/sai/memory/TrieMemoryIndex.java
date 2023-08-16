/*
 * All changes to the original code are Copyright DataStax, Inc.
 *
 * Please see the included license file for details.
 */

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.index.sai.memory;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SortedSet;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.FastThreadLocal;
import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.PartitionPosition;
import org.apache.cassandra.db.memtable.TrieMemtable;
import org.apache.cassandra.db.tries.MemtableTrie;
import org.apache.cassandra.db.tries.Trie;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.index.sai.IndexContext;
import org.apache.cassandra.index.sai.analyzer.AbstractAnalyzer;
import org.apache.cassandra.index.sai.plan.Expression;
import org.apache.cassandra.index.sai.utils.PrimaryKey;
import org.apache.cassandra.index.sai.utils.PrimaryKeys;
import org.apache.cassandra.index.sai.utils.RangeIterator;
import org.apache.cassandra.index.sai.utils.TypeUtil;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.Throwables;
import org.apache.cassandra.utils.bytecomparable.ByteComparable;
import org.apache.cassandra.utils.bytecomparable.ByteSource;
import org.apache.cassandra.utils.bytecomparable.ByteSourceInverse;

public class TrieMemoryIndex extends MemoryIndex
{
    private static final Logger logger = LoggerFactory.getLogger(TrieMemoryIndex.class);
    private static final int MINIMUM_QUEUE_SIZE = 128;
    private static final int MAX_RECURSIVE_KEY_LENGTH = 128;

    private final MemtableTrie<PrimaryKeys> data;
    private final PrimaryKeysReducer primaryKeysReducer;

    private ByteBuffer minTerm;
    private ByteBuffer maxTerm;

    private static final FastThreadLocal<Integer> lastQueueSize = new FastThreadLocal<Integer>()
    {
        protected Integer initialValue()
        {
            return MINIMUM_QUEUE_SIZE;
        }
    };

    public TrieMemoryIndex(IndexContext indexContext)
    {
        super(indexContext);
        this.data = new MemtableTrie<>(TrieMemtable.BUFFER_TYPE);
        this.primaryKeysReducer = new PrimaryKeysReducer();
    }

    public synchronized void add(DecoratedKey key,
                                 Clustering clustering,
                                 ByteBuffer value,
                                 LongConsumer onHeapAllocationsTracker,
                                 LongConsumer offHeapAllocationsTracker)
    {
        AbstractAnalyzer analyzer = indexContext.getAnalyzerFactory().create();
        try
        {
            value = TypeUtil.encode(value, indexContext.getValidator());
            analyzer.reset(value.duplicate());
            final PrimaryKey primaryKey = indexContext.keyFactory().create(key, clustering);
            final long initialSizeOnHeap = data.sizeOnHeap();
            final long initialSizeOffHeap = data.sizeOffHeap();
            final long reducerHeapSize = primaryKeysReducer.heapAllocations();

            while (analyzer.hasNext())
            {
                final ByteBuffer term = analyzer.next();

                setMinMaxTerm(term.duplicate());

                final ByteComparable encodedTerm = encode(term.duplicate());

                try
                {
                    if (term.limit() <= MAX_RECURSIVE_KEY_LENGTH)
                    {
                        data.putRecursive(encodedTerm, primaryKey, primaryKeysReducer);
                    }
                    else
                    {
                        data.apply(Trie.singleton(encodedTerm, primaryKey), primaryKeysReducer);
                    }
                }
                catch (MemtableTrie.SpaceExhaustedException e)
                {
                    Throwables.throwAsUncheckedException(e);
                }
            }

            onHeapAllocationsTracker.accept((data.sizeOnHeap() - initialSizeOnHeap) +
                                            (primaryKeysReducer.heapAllocations() - reducerHeapSize));
            offHeapAllocationsTracker.accept(data.sizeOffHeap() - initialSizeOffHeap);
        }
        finally
        {
            analyzer.end();
        }
    }

    @Override
    public Iterator<Pair<ByteComparable, PrimaryKeys>> iterator()
    {
        Iterator<Map.Entry<ByteComparable, PrimaryKeys>> iterator = data.entrySet().iterator();
        return new Iterator<Pair<ByteComparable, PrimaryKeys>>()
        {
            @Override
            public boolean hasNext()
            {
                return iterator.hasNext();
            }

            @Override
            public Pair<ByteComparable, PrimaryKeys> next()
            {
                Map.Entry<ByteComparable, PrimaryKeys> entry = iterator.next();
                return Pair.create(decode(entry.getKey()), entry.getValue());
            }
        };
    }

    @Override
    public RangeIterator<PrimaryKey> search(Expression expression, AbstractBounds<PartitionPosition> keyRange)
    {
        if (logger.isTraceEnabled())
            logger.trace("Searching memtable index on expression '{}'...", expression);

        switch (expression.getOp())
        {
            case MATCH:
            case EQ:
            case CONTAINS_KEY:
            case CONTAINS_VALUE:
                return exactMatch(expression, keyRange);
            case RANGE:
                return rangeMatch(expression, keyRange);
            default:
                throw new IllegalArgumentException("Unsupported expression: " + expression);
        }
    }

    public RangeIterator<PrimaryKey> exactMatch(Expression expression, AbstractBounds<PartitionPosition> keyRange)
    {
        final ByteComparable prefix = expression.lower == null ? ByteComparable.EMPTY : encode(expression.lower.value.encoded);
        final PrimaryKeys primaryKeys = data.get(prefix);
        if (primaryKeys == null)
        {
            return RangeIterator.emptyKeys();
        }
        return new FilteringKeyRangeIterator(primaryKeys.keys(), keyRange);
    }

    private RangeIterator<PrimaryKey> rangeMatch(Expression expression, AbstractBounds<PartitionPosition> keyRange)
    {
        ByteComparable lowerBound, upperBound;
        boolean lowerInclusive, upperInclusive;
        if (expression.lower != null)
        {
            lowerBound = encode(expression.lower.value.encoded);
            lowerInclusive = expression.lower.inclusive;
        }
        else
        {
            lowerBound = ByteComparable.EMPTY;
            lowerInclusive = false;
        }

        if (expression.upper != null)
        {
            upperBound = encode(expression.upper.value.encoded);
            upperInclusive = expression.upper.inclusive;
        }
        else
        {
            upperBound = null;
            upperInclusive = false;
        }

        Collector cd = new Collector(keyRange);

        data.subtrie(lowerBound, lowerInclusive, upperBound, upperInclusive).values().forEach(cd::processContent);

        if (cd.mergedKeys.isEmpty())
        {
            return RangeIterator.emptyKeys();
        }

        lastQueueSize.set(Math.max(MINIMUM_QUEUE_SIZE, cd.mergedKeys.size()));
        return new KeyRangeIterator(cd.minimumKey, cd.maximumKey, cd.mergedKeys);
    }

    public ByteBuffer getMinTerm()
    {
        return minTerm;
    }

    public ByteBuffer getMaxTerm()
    {
        return maxTerm;
    }

    private void setMinMaxTerm(ByteBuffer term)
    {
        assert term != null;

        minTerm = TypeUtil.min(term, minTerm, indexContext.getValidator());
        maxTerm = TypeUtil.max(term, maxTerm, indexContext.getValidator());
    }

    private ByteComparable encode(ByteBuffer input)
    {
        return indexContext.isLiteral() ? version -> append(ByteSource.of(input, version), ByteSource.TERMINATOR)
                                        : version -> TypeUtil.asComparableBytes(input, indexContext.getValidator(), version);
    }

    private ByteComparable decode(ByteComparable term)
    {
        return indexContext.isLiteral() ? version -> ByteSourceInverse.unescape(ByteSource.peekable(term.asComparableBytes(version)))
                                        : term;
    }

    private ByteSource append(ByteSource src, int lastByte)
    {
        return new ByteSource()
        {
            boolean done = false;

            @Override
            public int next()
            {
                if (done)
                    return END_OF_STREAM;
                int n = src.next();
                if (n != END_OF_STREAM)
                    return n;

                done = true;
                return lastByte;
            }
        };
    }

    class PrimaryKeysReducer implements MemtableTrie.UpsertTransformer<PrimaryKeys, PrimaryKey>
    {
        private final LongAdder heapAllocations = new LongAdder();

        @Override
        public PrimaryKeys apply(PrimaryKeys existing, PrimaryKey neww)
        {
            if (existing == null)
            {
                existing = new PrimaryKeys();
                heapAllocations.add(existing.unsharedHeapSize());
            }
            heapAllocations.add(existing.add(neww));
            return existing;
        }

        long heapAllocations()
        {
            return heapAllocations.longValue();
        }
    }

    class Collector
    {
        PrimaryKey minimumKey = null;
        PrimaryKey maximumKey = null;
        PriorityQueue<PrimaryKey> mergedKeys = new PriorityQueue<>(lastQueueSize.get());

        AbstractBounds<PartitionPosition> keyRange;

        public Collector(AbstractBounds<PartitionPosition> keyRange)
        {
            this.keyRange = keyRange;
        }

        public void processContent(PrimaryKeys keys)
        {
            if (keys.isEmpty())
                return;

            SortedSet<PrimaryKey> primaryKeys = keys.keys();

            // shortcut to avoid generating iterator
            if (primaryKeys.size() == 1)
            {
                PrimaryKey first = primaryKeys.first();
                if (keyRange.contains(first.partitionKey()))
                {
                    mergedKeys.add(first);

                    minimumKey = minimumKey == null ? first : first.compareTo(minimumKey) < 0 ? first : minimumKey;
                    maximumKey = maximumKey == null ? first : first.compareTo(maximumKey) > 0 ? first : maximumKey;
                }

                return;
            }

            // skip entire partition keys if they don't overlap
            if (!keyRange.right.isMinimum() && primaryKeys.first().partitionKey().compareTo(keyRange.right) > 0
                || primaryKeys.last().partitionKey().compareTo(keyRange.left) < 0)
                return;

            for (PrimaryKey key : primaryKeys)
            {
                if (keyRange.contains(key.partitionKey()))
                {
                    mergedKeys.add(key);

                    minimumKey = minimumKey == null ? key : key.compareTo(minimumKey) < 0 ? key : minimumKey;
                    maximumKey = maximumKey == null ? key : key.compareTo(maximumKey) > 0 ? key : maximumKey;
                }
            }
        }
    }
}