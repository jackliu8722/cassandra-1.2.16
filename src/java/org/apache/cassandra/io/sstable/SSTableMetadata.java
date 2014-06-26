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
package org.apache.cassandra.io.sstable;

import java.io.*;
import java.util.*;

import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.StreamingHistogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.EstimatedHistogram;

/**
 * Metadata for a SSTable.
 * Metadata includes:
 *  - estimated row size histogram
 *  - estimated column count histogram
 *  - replay position
 *  - max column timestamp
 *  - compression ratio
 *  - partitioner
 *  - generations of sstables from which this sstable was compacted, if any
 *  - tombstone drop time histogram
 *
 * An SSTableMetadata should be instantiated via the Collector, openFromDescriptor()
 * or createDefaultInstance()
 */
public class SSTableMetadata
{
    public static final double NO_COMPRESSION_RATIO = -1.0;
    public static final SSTableMetadataSerializer serializer = new SSTableMetadataSerializer();

    public final EstimatedHistogram estimatedRowSize;
    public final EstimatedHistogram estimatedColumnCount;
    public final ReplayPosition replayPosition;
    public final long minTimestamp;
    public final long maxTimestamp;
    public final double compressionRatio;
    public final String partitioner;
    public final StreamingHistogram estimatedTombstoneDropTime;

    private SSTableMetadata()
    {
        this(defaultRowSizeHistogram(),
             defaultColumnCountHistogram(),
             ReplayPosition.NONE,
             Long.MIN_VALUE,
             Long.MAX_VALUE,
             NO_COMPRESSION_RATIO,
             null,
             defaultTombstoneDropTimeHistogram());
    }

    private SSTableMetadata(EstimatedHistogram rowSizes,
                            EstimatedHistogram columnCounts,
                            ReplayPosition replayPosition,
                            long minTimestamp,
                            long maxTimestamp,
                            double cr,
                            String partitioner,
                            StreamingHistogram estimatedTombstoneDropTime)
    {
        this.estimatedRowSize = rowSizes;
        this.estimatedColumnCount = columnCounts;
        this.replayPosition = replayPosition;
        this.minTimestamp = minTimestamp;
        this.maxTimestamp = maxTimestamp;
        this.compressionRatio = cr;
        this.partitioner = partitioner;
        this.estimatedTombstoneDropTime = estimatedTombstoneDropTime;
    }

    public static Collector createCollector()
    {
        return new Collector();
    }

    static EstimatedHistogram defaultColumnCountHistogram()
    {
        // EH of 114 can track a max value of 2395318855, i.e., > 2B columns
        return new EstimatedHistogram(114);
    }

    static EstimatedHistogram defaultRowSizeHistogram()
    {
        // EH of 150 can track a max value of 1697806495183, i.e., > 1.5PB
        return new EstimatedHistogram(150);
    }

    static StreamingHistogram defaultTombstoneDropTimeHistogram()
    {
        return new StreamingHistogram(SSTable.TOMBSTONE_HISTOGRAM_BIN_SIZE);
    }

    /**
     * @param gcBefore
     * @return estimated droppable tombstone ratio at given gcBefore time.
     */
    public double getEstimatedDroppableTombstoneRatio(int gcBefore)
    {
        long estimatedColumnCount = this.estimatedColumnCount.mean() * this.estimatedColumnCount.count();
        if (estimatedColumnCount > 0)
        {
            double droppable = getDroppableTombstonesBefore(gcBefore);
            return droppable / estimatedColumnCount;
        }
        return 0.0f;
    }

    /**
     * Get the amount of droppable tombstones
     * @param gcBefore the gc time
     * @return amount of droppable tombstones
     */
    public double getDroppableTombstonesBefore(int gcBefore)
    {
        return estimatedTombstoneDropTime.sum(gcBefore);
    }

    public static class Collector
    {
        protected EstimatedHistogram estimatedRowSize = defaultRowSizeHistogram();
        protected EstimatedHistogram estimatedColumnCount = defaultColumnCountHistogram();
        protected ReplayPosition replayPosition = ReplayPosition.NONE;
        protected long minTimestamp = Long.MAX_VALUE;
        protected long maxTimestamp = Long.MIN_VALUE;
        protected double compressionRatio = NO_COMPRESSION_RATIO;
        protected Set<Integer> ancestors = new HashSet<Integer>();
        protected StreamingHistogram estimatedTombstoneDropTime = defaultTombstoneDropTimeHistogram();

        public void addRowSize(long rowSize)
        {
            estimatedRowSize.add(rowSize);
        }

        public void addColumnCount(long columnCount)
        {
            estimatedColumnCount.add(columnCount);
        }

        public void mergeTombstoneHistogram(StreamingHistogram histogram)
        {
            estimatedTombstoneDropTime.merge(histogram);
        }

        /**
         * Ratio is compressed/uncompressed and it is
         * if you have 1.x then compression isn't helping
         */
        public void addCompressionRatio(long compressed, long uncompressed)
        {
            compressionRatio = (double) compressed/uncompressed;
        }

        public void updateMinTimestamp(long potentialMin)
        {
            minTimestamp = Math.min(minTimestamp, potentialMin);
        }

        public void updateMaxTimestamp(long potentialMax)
        {
            maxTimestamp = Math.max(maxTimestamp, potentialMax);
        }

        public SSTableMetadata finalizeMetadata(String partitioner)
        {
            return new SSTableMetadata(estimatedRowSize,
                                       estimatedColumnCount,
                                       replayPosition,
                                       minTimestamp,
                                       maxTimestamp,
                                       compressionRatio,
                                       partitioner,
                                       estimatedTombstoneDropTime);
        }

        public Collector estimatedRowSize(EstimatedHistogram estimatedRowSize)
        {
            this.estimatedRowSize = estimatedRowSize;
            return this;
        }

        public Collector estimatedColumnCount(EstimatedHistogram estimatedColumnCount)
        {
            this.estimatedColumnCount = estimatedColumnCount;
            return this;
        }

        public Collector replayPosition(ReplayPosition replayPosition)
        {
            this.replayPosition = replayPosition;
            return this;
        }

        public Collector addAncestor(int generation)
        {
            this.ancestors.add(generation);
            return this;
        }

        void update(long size, ColumnStats stats)
        {
            updateMinTimestamp(stats.minTimestamp);
            /*
             * The max timestamp is not always collected here (more precisely, row.maxTimestamp() may return Long.MIN_VALUE),
             * to avoid deserializing an EchoedRow.
             * This is the reason why it is collected first when calling ColumnFamilyStore.createCompactionWriter
             * However, for old sstables without timestamp, we still want to update the timestamp (and we know
             * that in this case we will not use EchoedRow, since CompactionControler.needsDeserialize() will be true).
            */
            updateMaxTimestamp(stats.maxTimestamp);
            addRowSize(size);
            addColumnCount(stats.columnCount);
            mergeTombstoneHistogram(stats.tombstoneHistogram);
        }
    }

    public static class SSTableMetadataSerializer
    {
        private static final Logger logger = LoggerFactory.getLogger(SSTableMetadataSerializer.class);

        public void serialize(SSTableMetadata sstableStats, Set<Integer> ancestors, DataOutput dos) throws IOException
        {
            assert sstableStats.partitioner != null;

            EstimatedHistogram.serializer.serialize(sstableStats.estimatedRowSize, dos);
            EstimatedHistogram.serializer.serialize(sstableStats.estimatedColumnCount, dos);
            ReplayPosition.serializer.serialize(sstableStats.replayPosition, dos);
            dos.writeLong(sstableStats.minTimestamp);
            dos.writeLong(sstableStats.maxTimestamp);
            dos.writeDouble(sstableStats.compressionRatio);
            dos.writeUTF(sstableStats.partitioner);
            dos.writeInt(ancestors.size());
            for (Integer g : ancestors)
                dos.writeInt(g);
            StreamingHistogram.serializer.serialize(sstableStats.estimatedTombstoneDropTime, dos);
        }

        /**
         * deserializes the metadata
         *
         * returns a pair containing the part of the metadata meant to be kept-in memory and the part
         * that should not.
         *
         * @param descriptor the descriptor
         * @return a pair containing data that needs to be in memory and data that is potentially big and is not needed
         *         in memory
         * @throws IOException
         */
        public Pair<SSTableMetadata, Set<Integer>> deserialize(Descriptor descriptor) throws IOException
        {
            logger.debug("Load metadata for {}", descriptor);
            File statsFile = new File(descriptor.filenameFor(SSTable.COMPONENT_STATS));
            if (!statsFile.exists())
            {
                logger.debug("No sstable stats for {}", descriptor);
                return Pair.create(new SSTableMetadata(), Collections.<Integer>emptySet());
            }

            DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(statsFile)));
            try
            {
                return deserialize(dis, descriptor);
            }
            finally
            {
                FileUtils.closeQuietly(dis);
            }
        }

        public Pair<SSTableMetadata, Set<Integer>> deserialize(DataInputStream dis, Descriptor desc) throws IOException
        {
            EstimatedHistogram rowSizes = EstimatedHistogram.serializer.deserialize(dis);
            EstimatedHistogram columnCounts = EstimatedHistogram.serializer.deserialize(dis);
            ReplayPosition replayPosition = desc.version.metadataIncludesReplayPosition
                                          ? ReplayPosition.serializer.deserialize(dis)
                                          : ReplayPosition.NONE;
            if (!desc.version.metadataIncludesModernReplayPosition)
            {
                // replay position may be "from the future" thanks to older versions generating them with nanotime.
                // make sure we don't omit replaying something that we should.  see CASSANDRA-4782
                replayPosition = ReplayPosition.NONE;
            }
            long minTimestamp = desc.version.tracksMinTimestamp ? dis.readLong() : Long.MIN_VALUE;
            long maxTimestamp = desc.version.containsTimestamp() ? dis.readLong() : Long.MAX_VALUE;
            if (!desc.version.tracksMaxTimestamp) // see javadoc to Descriptor.containsTimestamp
                maxTimestamp = Long.MAX_VALUE;
            double compressionRatio = desc.version.hasCompressionRatio
                                    ? dis.readDouble()
                                    : NO_COMPRESSION_RATIO;
            String partitioner = desc.version.hasPartitioner ? dis.readUTF() : null;
            int nbAncestors = desc.version.hasAncestors ? dis.readInt() : 0;
            Set<Integer> ancestors = new HashSet<Integer>(nbAncestors);
            for (int i = 0; i < nbAncestors; i++)
                ancestors.add(dis.readInt());
            StreamingHistogram tombstoneHistogram = desc.version.tracksTombstones
                                                   ? StreamingHistogram.serializer.deserialize(dis)
                                                   : defaultTombstoneDropTimeHistogram();
            return Pair.create(new SSTableMetadata(rowSizes,
                                                   columnCounts,
                                                   replayPosition,
                                                   minTimestamp,
                                                   maxTimestamp,
                                                   compressionRatio,
                                                   partitioner,
                                                   tombstoneHistogram),
                                                   ancestors);
        }
    }
}
