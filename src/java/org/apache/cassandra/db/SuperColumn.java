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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Comparator;

import com.google.common.base.Objects;
import com.google.common.collect.Iterables;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.MarshalException;
import org.apache.cassandra.io.IColumnSerializer;
import org.apache.cassandra.io.util.ColumnSortedMap;
import org.apache.cassandra.io.util.DataOutputBuffer;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.Allocator;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.HeapAllocator;
import org.cliffc.high_scale_lib.NonBlockingHashMap;

public class SuperColumn extends AbstractColumnContainer implements IColumn
{
    private static final NonBlockingHashMap<Comparator, SuperColumnSerializer> serializers = new NonBlockingHashMap<Comparator, SuperColumnSerializer>();
    public static SuperColumnSerializer serializer(AbstractType<?> comparator)
    {
        SuperColumnSerializer serializer = serializers.get(comparator);
        if (serializer == null)
        {
            serializer = new SuperColumnSerializer(comparator);
            serializers.put(comparator, serializer);
        }
        return serializer;
    }

    public static OnDiskAtom.Serializer onDiskSerializer(AbstractType<?> comparator)
    {
        return new OnDiskAtom.Serializer(serializer(comparator));
    }

    private final ByteBuffer name;

    public SuperColumn(ByteBuffer name, AbstractType<?> comparator)
    {
        this(name, AtomicSortedColumns.factory().create(comparator, false));
    }

    SuperColumn(ByteBuffer name, ISortedColumns columns)
    {
        super(columns);
        assert name != null;
        assert name.remaining() <= IColumn.MAX_NAME_LENGTH;
        this.name = name;
    }

    public SuperColumn cloneMeShallow()
    {
        SuperColumn sc = new SuperColumn(name, getComparator());
        sc.delete(this);
        return sc;
    }

    public IColumn cloneMe()
    {
        SuperColumn sc = new SuperColumn(name, columns.cloneMe());
        sc.delete(this);
        return sc;
    }

    public ByteBuffer name()
    {
        return name;
    }

    public Collection<IColumn> getSubColumns()
    {
        return getSortedColumns();
    }

    public IColumn getSubColumn(ByteBuffer columnName)
    {
        IColumn column = columns.getColumn(columnName);
        assert column == null || column instanceof Column;
        return column;
    }

    /**
     * This calculates the exact size of the sub columns on the fly
     */
    public int dataSize()
    {
        int size = TypeSizes.NATIVE.sizeof(getMarkedForDeleteAt());
        for (IColumn subColumn : getSubColumns())
            size += subColumn.dataSize();
        return size;
    }

    /**
     * This returns the size of the super-column when serialized.
     * @see org.apache.cassandra.db.IColumn#serializedSize(TypeSizes)
    */
    public int serializedSize(TypeSizes typeSizes)
    {
        /*
         * We need to keep the way we are calculating the column size in sync with the
         * way we are calculating the size for the column family serializer.
         *
         * 2 bytes for name size
         * n bytes for the name
         * 4 bytes for getLocalDeletionTime
         * 8 bytes for getMarkedForDeleteAt
         * 4 bytes for the subcolumns size
         * size(constantSize) of subcolumns.
         */
        int nameSize = name.remaining();
        int subColumnsSize = 0;
        for (IColumn subColumn : getSubColumns())
            subColumnsSize += subColumn.serializedSize(typeSizes);
        int size = typeSizes.sizeof((short) nameSize) + nameSize
                 + typeSizes.sizeof(getLocalDeletionTime())
                 + typeSizes.sizeof(getMarkedForDeleteAt())
                 + typeSizes.sizeof(subColumnsSize) + subColumnsSize;
        return size;
    }

    public long serializedSizeForSSTable()
    {
        return serializedSize(TypeSizes.NATIVE);
    }

    public long timestamp()
    {
        throw new UnsupportedOperationException("This operation is not supported for Super Columns.");
    }

    public long minTimestamp()
    {
        long minTimestamp = getMarkedForDeleteAt();
        for (IColumn subColumn : getSubColumns())
            minTimestamp = Math.min(minTimestamp, subColumn.minTimestamp());
        return minTimestamp;
    }

    public long maxTimestamp()
    {
        long maxTimestamp = getMarkedForDeleteAt();
        for (IColumn subColumn : getSubColumns())
            maxTimestamp = Math.max(maxTimestamp, subColumn.maxTimestamp());
        return maxTimestamp;
    }

    public long mostRecentLiveChangeAt()
    {
        long max = Long.MIN_VALUE;
        for (IColumn column : getSubColumns())
        {
            if (!column.isMarkedForDelete() && column.timestamp() > max)
            {
                max = column.timestamp();
            }
        }
        return max;
    }

    public long getMarkedForDeleteAt()
    {
        return deletionInfo().getTopLevelDeletion().markedForDeleteAt;
    }

    public int getLocalDeletionTime()
    {
        return deletionInfo().getTopLevelDeletion().localDeletionTime;
    }

    public long mostRecentNonGCableChangeAt(int gcbefore)
    {
        long max = Long.MIN_VALUE;
        for (IColumn column : getSubColumns())
        {
            if (column.getLocalDeletionTime() >= gcbefore && column.timestamp() > max)
            {
                max = column.timestamp();
            }
        }
        return max;
    }

    public ByteBuffer value()
    {
        throw new UnsupportedOperationException("This operation is not supported for Super Columns.");
    }

    @Override
    public void addColumn(IColumn column, Allocator allocator)
    {
        assert column instanceof Column : "A super column can only contain simple columns";
        super.addColumn(column, allocator);
    }

    /*
     * Go through each sub column if it exists then as it to resolve itself
     * if the column does not exist then create it.
     */
    void putColumn(SuperColumn column, Allocator allocator)
    {
        for (IColumn subColumn : column.getSubColumns())
        {
            addColumn(subColumn, allocator);
        }
        delete(column);
    }

    public IColumn diff(IColumn columnNew)
    {
        IColumn columnDiff = new SuperColumn(columnNew.name(), ((SuperColumn)columnNew).getComparator());
        ((SuperColumn)columnDiff).delete(((SuperColumn)columnNew).deletionInfo());

        // (don't need to worry about columnNew containing subColumns that are shadowed by
        // the delete tombstone, since columnNew was generated by CF.resolve, which
        // takes care of those for us.)
        for (IColumn subColumn : columnNew.getSubColumns())
        {
            IColumn columnInternal = columns.getColumn(subColumn.name());
            if(columnInternal == null )
            {
                columnDiff.addColumn(subColumn);
            }
            else
            {
                IColumn subColumnDiff = columnInternal.diff(subColumn);
                if(subColumnDiff != null)
                {
                    columnDiff.addColumn(subColumnDiff);
                }
            }
        }

        if (!columnDiff.getSubColumns().isEmpty() || columnNew.isMarkedForDelete())
            return columnDiff;
        else
            return null;
    }

    public void updateDigest(MessageDigest digest)
    {
        assert name != null;
        digest.update(name.duplicate());
        DataOutputBuffer buffer = new DataOutputBuffer();
        try
        {
            buffer.writeLong(getMarkedForDeleteAt());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        digest.update(buffer.getData(), 0, buffer.getLength());
        for (IColumn column : getSubColumns())
        {
            column.updateDigest(digest);
        }
    }

    public String getString(AbstractType<?> comparator)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("SuperColumn(");
        sb.append(comparator.getString(name));

        if (isMarkedForDelete()) {
            sb.append(" -delete at ").append(getMarkedForDeleteAt()).append("-");
        }

        sb.append(" [");
        sb.append(getComparator().getColumnsString(getSubColumns()));
        sb.append("])");

        return sb.toString();
    }

    public boolean isLive()
    {
        return mostRecentLiveChangeAt() > getMarkedForDeleteAt();
    }

    public IColumn localCopy(ColumnFamilyStore cfs)
    {
        return localCopy(cfs, HeapAllocator.instance);
    }

    public IColumn localCopy(ColumnFamilyStore cfs, Allocator allocator)
    {
        // we don't try to intern supercolumn names, because if we're using Cassandra correctly it's almost
        // certainly just going to pollute our interning map with unique, dynamic values
        SuperColumn sc = new SuperColumn(allocator.clone(name), this.getComparator());
        sc.delete(this);

        for(IColumn c : columns)
        {
            sc.addColumn(c.localCopy(cfs, allocator));
        }

        return sc;
    }

    public IColumn reconcile(IColumn c)
    {
        return reconcile(null, null);
    }

    public IColumn reconcile(IColumn c, Allocator allocator)
    {
        throw new UnsupportedOperationException("This operation is unsupported on super columns.");
    }

    public int serializationFlags()
    {
        throw new UnsupportedOperationException("Super columns don't have a serialization mask");
    }

    public void validateFields(CFMetaData metadata) throws MarshalException
    {
        metadata.comparator.validate(name());
        for (IColumn column : getSubColumns())
        {
            column.validateFields(metadata);
        }
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        SuperColumn sc = (SuperColumn)o;

        if (!name.equals(sc.name))
            return false;
        if (getMarkedForDeleteAt() != sc.getMarkedForDeleteAt())
            return false;
        if (getLocalDeletionTime() != sc.getLocalDeletionTime())
            return false;
        return Iterables.elementsEqual(columns, sc.columns);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(name, getMarkedForDeleteAt(), getLocalDeletionTime(), columns);
    }
}

class SuperColumnSerializer implements IColumnSerializer
{
    private final AbstractType<?> comparator;

    public SuperColumnSerializer(AbstractType<?> comparator)
    {
        this.comparator = comparator;
    }

    public AbstractType<?> getComparator()
    {
        return comparator;
    }

    public void serialize(IColumn column, DataOutput dos) throws IOException
    {
        SuperColumn superColumn = (SuperColumn)column;
        ByteBufferUtil.writeWithShortLength(superColumn.name(), dos);
        DeletionInfo.serializer().serialize(superColumn.deletionInfo(), dos, MessagingService.VERSION_10);
        Collection<IColumn> columns = superColumn.getSubColumns();
        dos.writeInt(columns.size());
        for (IColumn subColumn : columns)
        {
            Column.serializer().serialize(subColumn, dos);
        }
    }

    public IColumn deserialize(DataInput dis) throws IOException
    {
        return deserialize(dis, IColumnSerializer.Flag.LOCAL);
    }

    public IColumn deserialize(DataInput dis, IColumnSerializer.Flag flag) throws IOException
    {
        return deserialize(dis, flag, (int)(System.currentTimeMillis() / 1000));
    }

    public IColumn deserialize(DataInput dis, IColumnSerializer.Flag flag, int expireBefore) throws IOException
    {
        ByteBuffer name = ByteBufferUtil.readWithShortLength(dis);
        DeletionInfo delInfo = DeletionInfo.serializer().deserialize(dis, MessagingService.VERSION_10, comparator);

        /* read the number of columns */
        int size = dis.readInt();
        ColumnSerializer serializer = Column.serializer();
        ColumnSortedMap preSortedMap = new ColumnSortedMap(comparator, serializer, dis, size, flag, expireBefore);
        SuperColumn superColumn = new SuperColumn(name, AtomicSortedColumns.factory().fromSorted(preSortedMap, false));
        superColumn.delete(delInfo);
        return superColumn;
    }

    public long serializedSize(IColumn object, TypeSizes typeSizes)
    {
        return object.serializedSize(typeSizes);
    }
}
