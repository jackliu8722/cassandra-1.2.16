/**
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
package org.apache.cassandra.db.compaction;

import java.nio.ByteBuffer;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.cassandra.OrderedJUnit4ClassRunner;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.sstable.SSTable;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.service.AntiEntropyService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;

@RunWith(OrderedJUnit4ClassRunner.class)
public class LeveledCompactionStrategyTest extends SchemaLoader
{
    /*
     * This excercise in particular the code of #4142
     */
    @Test
    public void testValidationMultipleSSTablePerLevel() throws Exception
    {
        String ksname = "Keyspace1";
        String cfname = "StandardLeveled";
        Table table = Table.open(ksname);
        ColumnFamilyStore store = table.getColumnFamilyStore(cfname);

        ByteBuffer value = ByteBuffer.wrap(new byte[100 * 1024]); // 100 KB value, make it easy to have multiple files

        // Enough data to have a level 1 and 2
        int rows = 20;
        int columns = 10;

        // Adds enough data to trigger multiple sstable per level
        for (int r = 0; r < rows; r++)
        {
            DecoratedKey key = Util.dk(String.valueOf(r));
            RowMutation rm = new RowMutation(ksname, key.key);
            for (int c = 0; c < columns; c++)
            {
                rm.add(new QueryPath(cfname, null, ByteBufferUtil.bytes("column" + c)), value, 0);
            }
            rm.apply();
            store.forceBlockingFlush();
        }

        LeveledCompactionStrategy strat = (LeveledCompactionStrategy)store.getCompactionStrategy();

        while (strat.getLevelSize(0) > 1)
        {
            store.forceMajorCompaction();
            Thread.sleep(200);
        }
        // Checking we're not completely bad at math
        assert strat.getLevelSize(1) > 0;
        assert strat.getLevelSize(2) > 0;

        AntiEntropyService.CFPair p = new AntiEntropyService.CFPair(ksname, cfname);
        Range<Token> range = new Range<Token>(Util.token(""), Util.token(""));
        AntiEntropyService.TreeRequest req = new AntiEntropyService.TreeRequest("1", FBUtilities.getLocalAddress(), range, p);
        AntiEntropyService.Validator validator = new AntiEntropyService.Validator(req);
        CompactionManager.instance.submitValidation(store, validator).get();
    }

    @Test
    public void testCompactionProgress() throws Exception
    {
        String ksname = "Keyspace1";
        String cfname = "StandardLeveled";
        Table table = Table.open(ksname);
        ColumnFamilyStore store = table.getColumnFamilyStore(cfname);

        // make sure we have SSTables in L1
        ByteBuffer value = ByteBuffer.wrap(new byte[100 * 1024]);
        int rows = 2;
        int columns = 10;
        for (int r = 0; r < rows; r++)
        {
            DecoratedKey key = Util.dk(String.valueOf(r));
            RowMutation rm = new RowMutation(ksname, key.key);
            for (int c = 0; c < columns; c++)
            {
                rm.add(new QueryPath(cfname, null, ByteBufferUtil.bytes("column" + c)), value, 0);
            }
            rm.apply();
            store.forceBlockingFlush();
        }
        store.forceMajorCompaction();

        LeveledCompactionStrategy strat = (LeveledCompactionStrategy)store.getCompactionStrategy();
        assert strat.getLevelSize(1) > 0;

        // get LeveledScanner for level 1 sstables
        Collection<SSTableReader> sstables = strat.manifest.getLevel(1);
        ICompactionScanner scanner = strat.getScanners(sstables).get(0);
        // scan through to the end
        while (scanner.hasNext())
            scanner.next();

        // scanner.getCurrentPosition should be equal to total bytes of L1 sstables
        assert scanner.getCurrentPosition() == SSTable.getTotalBytes(sstables);
    }
}
