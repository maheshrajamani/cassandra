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
package org.apache.cassandra.db.memtable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.common.annotations.VisibleForTesting;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.PartitionPosition;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;

/**
 * Holds boundaries (tokens) used to map a particular token (so partition key) to a shard id.
 * In practice, each keyspace has its associated boundaries, see {@link Keyspace}.
 * <p>
 * Technically, if we use {@code n} shards, this is a list of {@code n-1} tokens and each token {@code tk} gets assigned
 * to the core ID corresponding to the slot of the smallest token in the list that is greater to {@code tk}, or {@code n}
 * if {@code tk} is bigger than any token in the list.
 */
public class ShardBoundaries
{
    private static final PartitionPosition[] EMPTY_TOKEN_ARRAY = new PartitionPosition[0];
    private static final Range<PartitionPosition>[] EMPTY_RANGE_ARRAY = new Range[0];
    private static final List<Integer> EMPTY_BOUNDARIES_SHARDS = Collections.singletonList(0);

    // Special boundaries that map all tokens to one shard.
    // These boundaries will be used in either of these cases:
    // - there is only 1 shard configured
    // - the default partitioner doesn't support splitting
    // - the keyspace is local system keyspace
    public static final ShardBoundaries NONE = new ShardBoundaries(EMPTY_TOKEN_ARRAY, -1);

    private final PartitionPosition[] boundaries;
    private final Range<PartitionPosition>[] ranges;
    private final List<Integer> allShards;
    public final long ringVersion;

    @VisibleForTesting
    public ShardBoundaries(PartitionPosition[] boundaries, long ringVersion)
    {
        this.boundaries = boundaries;
        this.ringVersion = ringVersion;
        if (boundaries.length == 0)
            ranges = EMPTY_RANGE_ARRAY;
        else
        {
            ranges = new Range[boundaries.length + 1];
            int rangeIndex = 0;
            PartitionPosition minimum = DatabaseDescriptor.getPartitioner().getMinimumToken().minKeyBound();
            for (PartitionPosition boundary : boundaries)
            {
                ranges[rangeIndex++] = new Range<>(minimum, boundary);
                minimum = boundary;
            }
            ranges[rangeIndex] = new Range<>(minimum, DatabaseDescriptor.getPartitioner().getMaximumToken().maxKeyBound());
        }
        allShards = IntStream.range(0, boundaries.length + 1).boxed().collect(Collectors.toList());
    }

    public ShardBoundaries(List<PartitionPosition> boundaries, long ringVersion)
    {
        this(boundaries.toArray(EMPTY_TOKEN_ARRAY), ringVersion);
    }

    /**
     * Computes the shard to use for the provided token.
     */
    public int getShardForToken(Token tk)
    {
        for (int i = 0; i < boundaries.length; i++)
        {
            if (tk.compareTo(boundaries[i].getToken()) < 0)
                return i;
        }
        return boundaries.length;
    }

    /**
     * Computes the shard to use for the provided key.
     */
    public int getShardForKey(DecoratedKey key)
    {
        // Boundaries are missing if the node is not sufficiently initialized yet
        if (boundaries.length == 0)
            return 0;

        assert (key.getPartitioner() == DatabaseDescriptor.getPartitioner());
        return getShardForToken(key.getToken());
    }

    public List<Integer> getShardsForRange(AbstractBounds<PartitionPosition> keyRange)
    {
        if (boundaries.length == 0)
            return EMPTY_BOUNDARIES_SHARDS;

        // If the keyRange tokens match and are minimum then it represents the entire token ring
        // then we need to return all the shards.
        if (keyRange.right.isMinimum() && keyRange.left.compareTo(keyRange.right) == 0)
            return allShards;

        // Otherwise we need to return all the shards whose range intersects the keyrange
        return allShards.stream().filter(s -> ranges[s].intersects(keyRange)).collect(Collectors.toList());
    }

    /**
     * The number of shards that this boundaries support, that is how many different shard ids {@link #getShardForToken} might
     * possibly return.
     *
     * @return the number of shards supported by theses boundaries.
     */
    public int shardCount()
    {
        return boundaries.length + 1;
    }

    @Override
    public String toString()
    {
        if (boundaries.length == 0)
            return "shard 0: (min, max)";

        StringBuilder sb = new StringBuilder();
        sb.append("shard 0: (min, ").append(boundaries[0]).append(") ");
        for (int i = 0; i < boundaries.length - 1; i++)
            sb.append("shard ").append(i+1).append(": (").append(boundaries[i]).append(", ").append(boundaries[i+1]).append("] ");
        sb.append("shard ").append(boundaries.length).append(": (").append(boundaries[boundaries.length-1]).append(", max)");
        return sb.toString();
    }

    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ShardBoundaries that = (ShardBoundaries) o;

        return Arrays.equals(boundaries, that.boundaries);
    }

    public int hashCode()
    {
        return Arrays.hashCode(boundaries);
    }
}