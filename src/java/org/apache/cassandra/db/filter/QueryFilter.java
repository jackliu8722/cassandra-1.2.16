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
package org.apache.cassandra.db.filter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.db.*;
import org.apache.cassandra.db.columniterator.ISSTableColumnIterator;
import org.apache.cassandra.db.columniterator.IdentityQueryFilter;
import org.apache.cassandra.db.columniterator.OnDiskAtomIterator;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.util.FileDataInput;
import org.apache.cassandra.utils.CloseableIterator;
import org.apache.cassandra.utils.MergeIterator;

public class QueryFilter
{
    public final DecoratedKey key;
    public final QueryPath path;
    public final IDiskAtomFilter filter;
    private final IDiskAtomFilter superFilter;

    public QueryFilter(DecoratedKey key, QueryPath path, IDiskAtomFilter filter)
    {
        this.key = key;
        this.path = path;
        this.filter = filter;
        superFilter = path.superColumnName == null ? null : new NamesQueryFilter(path.superColumnName);
    }

    public OnDiskAtomIterator getMemtableColumnIterator(Memtable memtable)
    {
        ColumnFamily cf = memtable.getColumnFamily(key);
        if (cf == null)
            return null;
        return getMemtableColumnIterator(cf, key);
    }

    public OnDiskAtomIterator getMemtableColumnIterator(ColumnFamily cf, DecoratedKey key)
    {
        assert cf != null;
        if (path.superColumnName == null)
            return filter.getMemtableColumnIterator(cf, key);
        return superFilter.getMemtableColumnIterator(cf, key);
    }

    // TODO move gcBefore into a field
    public ISSTableColumnIterator getSSTableColumnIterator(SSTableReader sstable)
    {
        if (path.superColumnName == null)
            return filter.getSSTableColumnIterator(sstable, key);
        return superFilter.getSSTableColumnIterator(sstable, key);
    }

    public ISSTableColumnIterator getSSTableColumnIterator(SSTableReader sstable, FileDataInput file, DecoratedKey key, RowIndexEntry indexEntry)
    {
        if (path.superColumnName == null)
            return filter.getSSTableColumnIterator(sstable, file, key, indexEntry);
        return superFilter.getSSTableColumnIterator(sstable, file, key, indexEntry);
    }

    public void collateOnDiskAtom(final ColumnFamily returnCF, List<? extends CloseableIterator<OnDiskAtom>> toCollate, final int gcBefore)
    {
        List<CloseableIterator<IColumn>> filteredIterators = new ArrayList<CloseableIterator<IColumn>>(toCollate.size());
        for (CloseableIterator<OnDiskAtom> iter : toCollate)
            filteredIterators.add(gatherTombstones(returnCF, iter));
        collateColumns(returnCF, filteredIterators, gcBefore);
    }

    // TODO move gcBefore into a field
    public void collateColumns(final ColumnFamily returnCF, List<? extends CloseableIterator<IColumn>> toCollate, final int gcBefore)
    {
        IDiskAtomFilter topLevelFilter = (superFilter == null ? filter : superFilter);

        Comparator<IColumn> fcomp = topLevelFilter.getColumnComparator(returnCF.getComparator());
        // define a 'reduced' iterator that merges columns w/ the same name, which
        // greatly simplifies computing liveColumns in the presence of tombstones.
        MergeIterator.Reducer<IColumn, IColumn> reducer = new MergeIterator.Reducer<IColumn, IColumn>()
        {
            ColumnFamily curCF = returnCF.cloneMeShallow();

            public void reduce(IColumn current)
            {
                if (curCF.isSuper() && curCF.isEmpty())
                {
                    // If it is the first super column we add, we must clone it since other super column may modify
                    // it otherwise and it could be aliased in a memtable somewhere. We'll also don't have to care about what
                    // consumers make of the result (for instance CFS.getColumnFamily() call removeDeleted() on the
                    // result which removes column; which shouldn't be done on the original super column).
                    assert current instanceof SuperColumn;
                    curCF.addColumn(((SuperColumn) current).cloneMe());
                }
                else
                {
                    curCF.addColumn(current);
                }
            }

            protected IColumn getReduced()
            {
                IColumn c = curCF.iterator().next();
                if (superFilter != null)
                {
                    // filterSuperColumn only looks at immediate parent (the supercolumn) when determining if a subcolumn
                    // is still live, i.e., not shadowed by the parent's tombstone.  so, bump it up temporarily to the tombstone
                    // time of the cf, if that is greater.
                    DeletionInfo delInfo = ((SuperColumn) c).deletionInfo();
                    ((SuperColumn) c).delete(returnCF.deletionInfo());
                    c = filter.filterSuperColumn((SuperColumn) c, gcBefore);
                    ((SuperColumn) c).setDeletionInfo(delInfo); // reset sc tombstone time to what it should be
                }
                curCF.clear();

                return c;
            }
        };
        Iterator<IColumn> reduced = MergeIterator.get(toCollate, fcomp, reducer);

        topLevelFilter.collectReducedColumns(returnCF, reduced, gcBefore);
    }

    /**
     * Given an iterator of on disk atom, returns an iterator that filters the tombstone range
     * markers adding them to {@code returnCF} and returns the normal column.
     */
    public static CloseableIterator<IColumn> gatherTombstones(final ColumnFamily returnCF, final CloseableIterator<OnDiskAtom> iter)
    {
        return new CloseableIterator<IColumn>()
        {
            private IColumn next;

            public boolean hasNext()
            {
                if (next != null)
                    return true;

                getNext();
                return next != null;
            }

            public IColumn next()
            {
                if (next == null)
                    getNext();

                assert next != null;
                IColumn toReturn = next;
                next = null;
                return toReturn;
            }

            private void getNext()
            {
                while (iter.hasNext())
                {
                    OnDiskAtom atom = iter.next();

                    if (atom instanceof IColumn)
                    {
                        next = (IColumn)atom;
                        break;
                    }
                    else
                    {
                        returnCF.addAtom(atom);
                    }
                }
            }

            public void remove()
            {
                throw new UnsupportedOperationException();
            }

            public void close() throws IOException
            {
                iter.close();
            }
        };
    }

    public String getColumnFamilyName()
    {
        return path.columnFamilyName;
    }

    public static boolean isRelevant(IColumn column, IColumnContainer container, int gcBefore)
    {
        // the column itself must be not gc-able (it is live, or a still relevant tombstone, or has live subcolumns), (1)
        // and if its container is deleted, the column must be changed more recently than the container tombstone (2)
        // (since otherwise, the only thing repair cares about is the container tombstone)
        long maxChange = column.mostRecentNonGCableChangeAt(gcBefore);
        return (column.getLocalDeletionTime() >= gcBefore || maxChange > column.getMarkedForDeleteAt()) // (1)
               && (!container.deletionInfo().isDeleted(column.name(), maxChange)); // (2)
    }

    /**
     * @return a QueryFilter object to satisfy the given slice criteria:
     * @param key the row to slice
     * @param path path to the level to slice at (CF or SuperColumn)
     * @param start column to start slice at, inclusive; empty for "the first column"
     * @param finish column to stop slice at, inclusive; empty for "the last column"
     * @param reversed true to start with the largest column (as determined by configured sort order) instead of smallest
     * @param limit maximum number of non-deleted columns to return
     */
    public static QueryFilter getSliceFilter(DecoratedKey key, QueryPath path, ByteBuffer start, ByteBuffer finish, boolean reversed, int limit)
    {
        return new QueryFilter(key, path, new SliceQueryFilter(start, finish, reversed, limit));
    }

    /**
     * return a QueryFilter object that includes every column in the row.
     * This is dangerous on large rows; avoid except for test code.
     */
    public static QueryFilter getIdentityFilter(DecoratedKey key, QueryPath path)
    {
        return new QueryFilter(key, path, new IdentityQueryFilter());
    }

    /**
     * @return a QueryFilter object that will return columns matching the given names
     * @param key the row to slice
     * @param path path to the level to slice at (CF or SuperColumn)
     * @param columns the column names to restrict the results to, sorted in comparator order
     */
    public static QueryFilter getNamesFilter(DecoratedKey key, QueryPath path, SortedSet<ByteBuffer> columns)
    {
        return new QueryFilter(key, path, new NamesQueryFilter(columns));
    }

    /**
     * convenience method for creating a name filter matching a single column
     */
    public static QueryFilter getNamesFilter(DecoratedKey key, QueryPath path, ByteBuffer column)
    {
        return new QueryFilter(key, path, new NamesQueryFilter(column));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(key=" + key +
               ", path=" + path +
               (filter == null ? "" : ", filter=" + filter) +
               (superFilter == null ? "" : ", superFilter=" + superFilter) +
               ")";
    }
}
