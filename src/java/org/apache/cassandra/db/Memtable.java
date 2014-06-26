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
package org.apache.cassandra.db;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import org.cliffc.high_scale_lib.NonBlockingHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.JMXEnabledThreadPoolExecutor;
import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.columniterator.OnDiskAtomIterator;
import org.apache.cassandra.db.columniterator.SimpleAbstractColumnIterator;
import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.apache.cassandra.db.filter.AbstractColumnIterator;
import org.apache.cassandra.db.filter.NamesQueryFilter;
import org.apache.cassandra.db.filter.SliceQueryFilter;
import org.apache.cassandra.db.index.SecondaryIndexManager;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.io.sstable.SSTableMetadata;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.sstable.SSTableWriter;
import org.apache.cassandra.io.util.DiskAwareRunnable;
import org.apache.cassandra.utils.Allocator;
import org.github.jamm.MemoryMeter;

public class Memtable
{
    private static final Logger logger = LoggerFactory.getLogger(Memtable.class);

    /*
     * switchMemtable puts Memtable.getSortedContents on the writer executor.  When the write is complete,
     * we turn the writer into an SSTableReader and add it to ssTables where it is available for reads.
     *
     * There are two other things that switchMemtable does.
     * First, it puts the Memtable into memtablesPendingFlush, where it stays until the flush is complete
     * and it's been added as an SSTableReader to ssTables_.  Second, it adds an entry to commitLogUpdater
     * that waits for the flush to complete, then calls onMemtableFlush.  This allows multiple flushes
     * to happen simultaneously on multicore systems, while still calling onMF in the correct order,
     * which is necessary for replay in case of a restart since CommitLog assumes that when onMF is
     * called, all data up to the given context has been persisted to SSTables.
     */
    private static final ExecutorService flushWriter
            = new JMXEnabledThreadPoolExecutor(DatabaseDescriptor.getFlushWriters(),
                                               StageManager.KEEPALIVE,
                                               TimeUnit.SECONDS,
                                               new LinkedBlockingQueue<Runnable>(DatabaseDescriptor.getFlushQueueSize()),
                                               new NamedThreadFactory("FlushWriter"),
                                               "internal");

    // size in memory can never be less than serialized size
    private static final double MIN_SANE_LIVE_RATIO = 1.0;
    // max liveratio seen w/ 1-byte columns on a 64-bit jvm was 19. If it gets higher than 64 something is probably broken.
    private static final double MAX_SANE_LIVE_RATIO = 64.0;

    // we want to limit the amount of concurrently running and/or queued meterings, because counting is slow (can be
    // minutes, for a large memtable and a busy server). so we could keep memtables
    // alive after they're flushed and would otherwise be GC'd. the approach we take is to bound the number of
    // outstanding/running meterings to a maximum of one per CFS using this set; the executor's queue is unbounded but
    // will implicitly be bounded by the number of CFS:s.
    private static final Set<ColumnFamilyStore> meteringInProgress = new NonBlockingHashSet<ColumnFamilyStore>();
    private static final ExecutorService meterExecutor = new JMXEnabledThreadPoolExecutor(1,
                                                                                          Integer.MAX_VALUE,
                                                                                          TimeUnit.MILLISECONDS,
                                                                                          new LinkedBlockingQueue<Runnable>(),
                                                                                          new NamedThreadFactory("MemoryMeter"),
                                                                                          "internal");
    private final MemoryMeter meter;

    volatile static ColumnFamilyStore activelyMeasuring;

    private final AtomicLong currentSize = new AtomicLong(0);
    private final AtomicLong currentOperations = new AtomicLong(0);

    // We index the memtable by RowPosition only for the purpose of being able
    // to select key range using Token.KeyBound. However put() ensures that we
    // actually only store DecoratedKey.
    private final ConcurrentNavigableMap<RowPosition, ColumnFamily> columnFamilies = new ConcurrentSkipListMap<RowPosition, ColumnFamily>();
    public final ColumnFamilyStore cfs;
    private final long creationTime;

    private final Allocator allocator = DatabaseDescriptor.getMemtableAllocator();
    // We really only need one column by allocator but one by memtable is not a big waste and avoids needing allocators to know about CFS
    private final Function<IColumn, IColumn> localCopyFunction = new Function<IColumn, IColumn>()
    {
        public IColumn apply(IColumn c)
        {
            return c.localCopy(cfs, allocator);
        };
    };

    // Record the comparator of the CFS at the creation of the memtable. This
    // is only used when a user update the CF comparator, to know if the
    // memtable was created with the new or old comparator.
    public final AbstractType initialComparator;

    public Memtable(ColumnFamilyStore cfs)
    {
        this.cfs = cfs;
        this.creationTime = System.currentTimeMillis();
        this.initialComparator = cfs.metadata.comparator;

        Callable<Set<Object>> provider = new Callable<Set<Object>>()
        {
            public Set<Object> call() throws Exception
            {
                // avoid counting this once for each row
                Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                set.add(Memtable.this.cfs.metadata);
                return set;
            }
        };
        meter = new MemoryMeter().omitSharedBufferOverhead().withTrackerProvider(provider);
    }

    public long getLiveSize()
    {
        long estimatedSize = (long) (currentSize.get() * cfs.liveRatio);

        // liveRatio is just an estimate; we can get a lower bound directly from the allocator
        if (estimatedSize < allocator.getMinimumSize())
            return allocator.getMinimumSize();

        return estimatedSize;
    }

    public long getSerializedSize()
    {
        return currentSize.get();
    }

    public long getOperations()
    {
        return currentOperations.get();
    }

    /**
     * Should only be called by ColumnFamilyStore.apply.  NOT a public API.
     * (CFS handles locking to avoid submitting an op
     *  to a flushing memtable.  Any other way is unsafe.)
    */
    void put(DecoratedKey key, ColumnFamily columnFamily, SecondaryIndexManager.Updater indexer)
    {
        resolve(key, columnFamily, indexer);
    }

    public void updateLiveRatio() throws RuntimeException
    {
        if (!MemoryMeter.isInitialized())
        {
            // hack for openjdk.  we log a warning about this in the startup script too.
            logger.error("MemoryMeter uninitialized (jamm not specified as java agent); assuming liveRatio of {}.  "
                         + " Usually this means cassandra-env.sh disabled jamm because you are using a buggy JRE; "
                         + " upgrade to the Sun JRE instead", cfs.liveRatio);
            return;
        }

        if (!meteringInProgress.add(cfs))
        {
            logger.debug("Metering already pending or active for {}; skipping liveRatio update", cfs);
            return;
        }

        meterExecutor.submit(new MeteringRunnable(cfs));
    }

    private void resolve(DecoratedKey key, ColumnFamily cf, SecondaryIndexManager.Updater indexer)
    {
        ColumnFamily previous = columnFamilies.get(key);

        if (previous == null)
        {
            // AtomicSortedColumns doesn't work for super columns (see #3821)
            ColumnFamily empty = cf.cloneMeShallow(cf.isSuper() ? ThreadSafeSortedColumns.factory() : AtomicSortedColumns.factory(), false);
            // We'll add the columns later. This avoids wasting works if we get beaten in the putIfAbsent
            previous = columnFamilies.putIfAbsent(new DecoratedKey(key.token, allocator.clone(key.key)), empty);
            if (previous == null)
                previous = empty;
        }

        long sizeDelta = previous.addAllWithSizeDelta(cf, allocator, localCopyFunction, indexer);
        currentSize.addAndGet(sizeDelta);
        currentOperations.addAndGet(cf.getColumnCount() + (cf.isMarkedForDelete() ? 1 : 0) + cf.deletionInfo().rangeCount());
    }

    // for debugging
    public String contents()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("{");
        for (Map.Entry<RowPosition, ColumnFamily> entry : columnFamilies.entrySet())
        {
            builder.append(entry.getKey()).append(": ").append(entry.getValue()).append(", ");
        }
        builder.append("}");
        return builder.toString();
    }

    public void flushAndSignal(final CountDownLatch latch, final Future<ReplayPosition> context)
    {
        flushWriter.execute(new FlushRunnable(latch, context));
    }

    public String toString()
    {
        return String.format("Memtable-%s@%s(%s/%s serialized/live bytes, %s ops)",
                             cfs.getColumnFamilyName(), hashCode(), currentSize, getLiveSize(), currentOperations);
    }

    /**
     * @param startWith Include data in the result from and including this key and to the end of the memtable
     * @return An iterator of entries with the data from the start key
     */
    public Iterator<Map.Entry<DecoratedKey, ColumnFamily>> getEntryIterator(final RowPosition startWith, final RowPosition stopAt)
    {
        return new Iterator<Map.Entry<DecoratedKey, ColumnFamily>>()
        {
            private Iterator<Map.Entry<RowPosition, ColumnFamily>> iter = stopAt.isMinimum()
                                                                        ? columnFamilies.tailMap(startWith).entrySet().iterator()
                                                                        : columnFamilies.subMap(startWith, true, stopAt, true).entrySet().iterator();

            public boolean hasNext()
            {
                return iter.hasNext();
            }

            public Map.Entry<DecoratedKey, ColumnFamily> next()
            {
                Map.Entry<RowPosition, ColumnFamily> entry = iter.next();
                // Actual stored key should be true DecoratedKey
                assert entry.getKey() instanceof DecoratedKey;
                return (Map.Entry<DecoratedKey, ColumnFamily>)(Object)entry; // yes, it's ugly
            }

            public void remove()
            {
                iter.remove();
            }
        };
    }

    public boolean isClean()
    {
        return columnFamilies.isEmpty();
    }

    /**
     * obtain an iterator of columns in this memtable in the specified order starting from a given column.
     */
    public static OnDiskAtomIterator getSliceIterator(final DecoratedKey key, final ColumnFamily cf, SliceQueryFilter filter)
    {
        assert cf != null;
        final Iterator<IColumn> filteredIter = filter.reversed ? cf.reverseIterator(filter.slices) : cf.iterator(filter.slices);

        return new AbstractColumnIterator()
        {
            public ColumnFamily getColumnFamily()
            {
                return cf;
            }

            public DecoratedKey getKey()
            {
                return key;
            }

            public boolean hasNext()
            {
                return filteredIter.hasNext();
            }

            public OnDiskAtom next()
            {
                return filteredIter.next();
            }
        };
    }

    public static OnDiskAtomIterator getNamesIterator(final DecoratedKey key, final ColumnFamily cf, final NamesQueryFilter filter)
    {
        assert cf != null;
        final boolean isStandard = !cf.isSuper();

        return new SimpleAbstractColumnIterator()
        {
            private Iterator<ByteBuffer> iter = filter.columns.iterator();

            public ColumnFamily getColumnFamily()
            {
                return cf;
            }

            public DecoratedKey getKey()
            {
                return key;
            }

            protected OnDiskAtom computeNext()
            {
                while (iter.hasNext())
                {
                    ByteBuffer current = iter.next();
                    IColumn column = cf.getColumn(current);
                    if (column != null)
                        // clone supercolumns so caller can freely removeDeleted or otherwise mutate it
                        return isStandard ? column : ((SuperColumn)column).cloneMe();
                }
                return endOfData();
            }
        };
    }

    public ColumnFamily getColumnFamily(DecoratedKey key)
    {
        return columnFamilies.get(key);
    }

    void clearUnsafe()
    {
        columnFamilies.clear();
    }

    public long creationTime()
    {
        return creationTime;
    }

    class FlushRunnable extends DiskAwareRunnable
    {
        private final CountDownLatch latch;
        private final Future<ReplayPosition> context;
        private final long estimatedSize;

        FlushRunnable(CountDownLatch latch, Future<ReplayPosition> context)
        {
            this.latch = latch;
            this.context = context;

            long keySize = 0;
            for (RowPosition key : columnFamilies.keySet())
            {
                //  make sure we don't write non-sensical keys
                assert key instanceof DecoratedKey;
                keySize += ((DecoratedKey)key).key.remaining();
            }
            estimatedSize = (long) ((keySize // index entries
                                    + keySize // keys in data file
                                    + currentSize.get()) // data
                                    * 1.2); // bloom filter and row index overhead
        }

        public long getExpectedWriteSize()
        {
            return estimatedSize;
        }

        protected void runWith(File sstableDirectory) throws Exception
        {
            assert sstableDirectory != null : "Flush task is not bound to any disk";

            SSTableReader sstable = writeSortedContents(context, sstableDirectory);
            cfs.replaceFlushed(Memtable.this, sstable);
            latch.countDown();
        }

        protected Directories getDirectories()
        {
            return cfs.directories;
        }

        private SSTableReader writeSortedContents(Future<ReplayPosition> context, File sstableDirectory)
        throws ExecutionException, InterruptedException
        {
            logger.info("Writing " + Memtable.this.toString());

            SSTableReader ssTable;
            // errors when creating the writer that may leave empty temp files.
            SSTableWriter writer = createFlushWriter(cfs.getTempSSTablePath(sstableDirectory));
            try
            {
                // (we can't clear out the map as-we-go to free up memory,
                //  since the memtable is being used for queries in the "pending flush" category)
                for (Map.Entry<RowPosition, ColumnFamily> entry : columnFamilies.entrySet())
                {
                    ColumnFamily cf = entry.getValue();
                    if (cf.isMarkedForDelete())
                    {
                        // When every node is up, there's no reason to write batchlog data out to sstables
                        // (which in turn incurs cost like compaction) since the BL write + delete cancel each other out,
                        // and BL data is strictly local, so we don't need to preserve tombstones for repair.
                        // If we have a data row + row level tombstone, then writing it is effectively an expensive no-op so we skip it.
                        // See CASSANDRA-4667.
                        if (cfs.columnFamily.equals(SystemTable.BATCHLOG_CF) && cfs.table.name.equals(Table.SYSTEM_KS) && !cf.isEmpty())
                            continue;

                        // Pedantically, you could purge column level tombstones that are past GcGRace when writing to the SSTable.
                        // But it can result in unexpected behaviour where deletes never make it to disk,
                        // as they are lost and so cannot override existing column values. So we only remove deleted columns if there
                        // is a CF level tombstone to ensure the delete makes it into an SSTable.
                        // We also shouldn't be dropping any columns obsoleted by partition and/or range tombstones in case
                        // the table has secondary indexes, or else the stale entries wouldn't be cleaned up during compaction,
                        // and will only be dropped during 2i query read-repair, if at all.
                        if (!cfs.indexManager.hasIndexes())
                            ColumnFamilyStore.removeDeletedColumnsOnly(cf, Integer.MIN_VALUE);
                    }
                    writer.append((DecoratedKey)entry.getKey(), cf);
                }

                if (writer.getFilePointer() > 0)
                {
                    ssTable = writer.closeAndOpenReader();
                    logger.info(String.format("Completed flushing %s (%d bytes) for commitlog position %s",
                                              ssTable.getFilename(), new File(ssTable.getFilename()).length(), context.get()));
                }
                else
                {
                    writer.abort();
                    ssTable = null;
                    logger.info("Completed flushing; nothing needed to be retained.  Commitlog position was {}",
                                context.get());
                }
                return ssTable;
            }
            catch (Throwable e)
            {
                writer.abort();
                throw Throwables.propagate(e);
            }
        }

        public SSTableWriter createFlushWriter(String filename) throws ExecutionException, InterruptedException
        {
            SSTableMetadata.Collector sstableMetadataCollector = SSTableMetadata.createCollector().replayPosition(context.get());
            return new SSTableWriter(filename,
                                     columnFamilies.size(),
                                     cfs.metadata,
                                     cfs.partitioner,
                                     sstableMetadataCollector);
        }
    }

    private static class MeteringRunnable implements Runnable
    {
        // we might need to wait in the meter queue for a while.  measure whichever memtable is active at that point,
        // rather than keeping the original memtable referenced (and thus un-freeable) until this runs.
        private final ColumnFamilyStore cfs;

        public MeteringRunnable(ColumnFamilyStore cfs)
        {
            this.cfs = cfs;
        }

        public void run()
        {
            try
            {
                activelyMeasuring = cfs;
                Memtable memtable = cfs.getMemtableThreadSafe();

                long start = System.currentTimeMillis();
                // ConcurrentSkipListMap has cycles, so measureDeep will have to track a reference to EACH object it visits.
                // So to reduce the memory overhead of doing a measurement, we break it up to row-at-a-time.
                long deepSize = memtable.meter.measure(memtable.columnFamilies);
                int objects = 0;
                for (Map.Entry<RowPosition, ColumnFamily> entry : memtable.columnFamilies.entrySet())
                {
                    deepSize += memtable.meter.measureDeep(entry.getKey()) + memtable.meter.measureDeep(entry.getValue());
                    objects += entry.getValue().getColumnCount();
                }
                double newRatio = (double) deepSize / memtable.currentSize.get();

                if (newRatio < MIN_SANE_LIVE_RATIO)
                {
                    logger.debug("setting live ratio to minimum of {} instead of {}", MIN_SANE_LIVE_RATIO, newRatio);
                    newRatio = MIN_SANE_LIVE_RATIO;
                }
                if (newRatio > MAX_SANE_LIVE_RATIO)
                {
                    logger.warn("setting live ratio to maximum of {} instead of {}", MAX_SANE_LIVE_RATIO, newRatio);
                    newRatio = MAX_SANE_LIVE_RATIO;
                }

                // we want to be very conservative about our estimate, since the penalty for guessing low is OOM
                // death.  thus, higher estimates are believed immediately; lower ones are averaged w/ the old
                if (newRatio > cfs.liveRatio)
                    cfs.liveRatio = newRatio;
                else
                    cfs.liveRatio = (cfs.liveRatio + newRatio) / 2.0;

                logger.info("{} liveRatio is {} (just-counted was {}).  calculation took {}ms for {} columns",
                            cfs, cfs.liveRatio, newRatio, System.currentTimeMillis() - start, objects);
            }
            finally
            {
                activelyMeasuring = null;
                meteringInProgress.remove(cfs);
            }
        }
    }
}
