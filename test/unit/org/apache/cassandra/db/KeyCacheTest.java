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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.junit.AfterClass;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.cache.KeyCacheKey;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.service.CacheService;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.utils.ByteBufferUtil;

import static junit.framework.Assert.assertEquals;

public class KeyCacheTest extends SchemaLoader
{
    private static final String TABLE1 = "KeyCacheSpace";
    private static final String COLUMN_FAMILY1 = "Standard1";
    private static final String COLUMN_FAMILY2 = "Standard2";

    @AfterClass
    public static void cleanup()
    {
        cleanupSavedCaches();
    }

    @Test
    public void testKeyCacheLoad() throws Exception
    {
        CompactionManager.instance.disableAutoCompaction();

        ColumnFamilyStore store = Table.open(TABLE1).getColumnFamilyStore(COLUMN_FAMILY2);

        // empty the cache
        CacheService.instance.invalidateKeyCache();
        assertKeyCacheSize(0, TABLE1, COLUMN_FAMILY2);

        // insert data and force to disk
        insertData(TABLE1, COLUMN_FAMILY2, 0, 100);
        store.forceBlockingFlush();

        // populate the cache
        readData(TABLE1, COLUMN_FAMILY2, 0, 100);
        assertKeyCacheSize(100, TABLE1, COLUMN_FAMILY2);

        // really? our caches don't implement the map interface? (hence no .addAll)
        Map<KeyCacheKey, RowIndexEntry> savedMap = new HashMap<KeyCacheKey, RowIndexEntry>();
        for (KeyCacheKey k : CacheService.instance.keyCache.getKeySet())
        {
            if (k.desc.ksname.equals(TABLE1) && k.desc.cfname.equals(COLUMN_FAMILY2))
                savedMap.put(k, CacheService.instance.keyCache.get(k));
        }

        // force the cache to disk
        CacheService.instance.keyCache.submitWrite(Integer.MAX_VALUE).get();

        CacheService.instance.invalidateKeyCache();
        assertKeyCacheSize(0, TABLE1, COLUMN_FAMILY2);

        CacheService.instance.keyCache.loadSaved(store);
        assertKeyCacheSize(savedMap.size(), TABLE1, COLUMN_FAMILY2);

        // probably it's better to add equals/hashCode to RowIndexEntry...
        for (Map.Entry<KeyCacheKey, RowIndexEntry> entry : savedMap.entrySet())
        {
            RowIndexEntry expected = entry.getValue();
            RowIndexEntry actual = CacheService.instance.keyCache.get(entry.getKey());
            assertEquals(expected.position, actual.position);
            assertEquals(expected.columnsIndex(), actual.columnsIndex());
            if (expected.isIndexed())
            {
                assertEquals(expected.deletionTime(), actual.deletionTime());
            }
        }
    }

    @Test
    public void testKeyCache() throws IOException, ExecutionException, InterruptedException
    {
        CompactionManager.instance.disableAutoCompaction();

        Table table = Table.open(TABLE1);
        ColumnFamilyStore cfs = table.getColumnFamilyStore(COLUMN_FAMILY1);

        // just to make sure that everything is clean
        CacheService.instance.invalidateKeyCache();

        // KeyCache should start at size 0 if we're caching X% of zero data.
        assertKeyCacheSize(0, TABLE1, COLUMN_FAMILY1);

        DecoratedKey key1 = Util.dk("key1");
        DecoratedKey key2 = Util.dk("key2");
        RowMutation rm;

        // inserts
        rm = new RowMutation(TABLE1, key1.key);
        rm.add(new QueryPath(COLUMN_FAMILY1, null, ByteBufferUtil.bytes("1")), ByteBufferUtil.EMPTY_BYTE_BUFFER, 0);
        rm.apply();
        rm = new RowMutation(TABLE1, key2.key);
        rm.add(new QueryPath(COLUMN_FAMILY1, null, ByteBufferUtil.bytes("2")), ByteBufferUtil.EMPTY_BYTE_BUFFER, 0);
        rm.apply();

        // to make sure we have SSTable
        cfs.forceBlockingFlush();

        // reads to cache key position
        cfs.getColumnFamily(QueryFilter.getSliceFilter(key1,
                                                       new QueryPath(new ColumnParent(COLUMN_FAMILY1)),
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       false,
                                                       10));

        cfs.getColumnFamily(QueryFilter.getSliceFilter(key2,
                                                       new QueryPath(new ColumnParent(COLUMN_FAMILY1)),
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       false,
                                                       10));

        assertKeyCacheSize(2, TABLE1, COLUMN_FAMILY1);

        Util.compactAll(cfs).get();
        // after compaction cache should have entries for
        // new SSTables, if we had 2 keys in cache previously it should become 4
        assertKeyCacheSize(4, TABLE1, COLUMN_FAMILY1);

        // re-read same keys to verify that key cache didn't grow further
        cfs.getColumnFamily(QueryFilter.getSliceFilter(key1,
                                                       new QueryPath(new ColumnParent(COLUMN_FAMILY1)),
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       false,
                                                       10));

        cfs.getColumnFamily(QueryFilter.getSliceFilter(key2,
                                                       new QueryPath(new ColumnParent(COLUMN_FAMILY1)),
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       false,
                                                       10));

        assertKeyCacheSize(4, TABLE1, COLUMN_FAMILY1);
    }

    private void assertKeyCacheSize(int expected, String keyspace, String columnFamily)
    {
        int size = 0;
        for (KeyCacheKey k : CacheService.instance.keyCache.getKeySet())
        {
            if (k.desc.ksname.equals(keyspace) && k.desc.cfname.equals(columnFamily))
                size++;
        }
        assertEquals(expected, size);
    }
}
