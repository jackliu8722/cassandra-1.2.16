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
package org.apache.cassandra.db.marshal;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.utils.Pair;

public class ListType<T> extends CollectionType<List<T>>
{
    // interning instances
    private static final Map<AbstractType<?>, ListType> instances = new HashMap<AbstractType<?>, ListType>();

    public final AbstractType<T> elements;

    public static ListType<?> getInstance(TypeParser parser) throws ConfigurationException, SyntaxException
    {
        List<AbstractType<?>> l = parser.getTypeParameters();
        if (l.size() != 1)
            throw new ConfigurationException("ListType takes exactly 1 type parameter");

        return getInstance(l.get(0));
    }

    public static synchronized <T> ListType<T> getInstance(AbstractType<T> elements)
    {
        ListType<T> t = instances.get(elements);
        if (t == null)
        {
            t = new ListType<T>(elements);
            instances.put(elements, t);
        }
        return t;
    }

    private ListType(AbstractType<T> elements)
    {
        super(Kind.LIST);
        this.elements = elements;
    }

    public AbstractType<UUID> nameComparator()
    {
        return TimeUUIDType.instance;
    }

    public AbstractType<T> valueComparator()
    {
        return elements;
    }

    public List<T> compose(ByteBuffer bytes)
    {
        try
        {
            ByteBuffer input = bytes.duplicate();
            int n = getUnsignedShort(input);
            List<T> l = new ArrayList<T>(n);
            for (int i = 0; i < n; i++)
            {
                int s = getUnsignedShort(input);
                byte[] data = new byte[s];
                input.get(data);
                ByteBuffer databb = ByteBuffer.wrap(data);
                elements.validate(databb);
                l.add(elements.compose(databb));
            }
            return l;
        }
        catch (BufferUnderflowException e)
        {
            throw new MarshalException("Not enough bytes to read a list");
        }
    }

    /**
     * Layout is: {@code <n><s_1><b_1>...<s_n><b_n> }
     * where:
     *   n is the number of elements
     *   s_i is the number of bytes composing the ith element
     *   b_i is the s_i bytes composing the ith element
     */
    public ByteBuffer decompose(List<T> value)
    {
        List<ByteBuffer> bbs = new ArrayList<ByteBuffer>(value.size());
        int size = 0;
        for (T elt : value)
        {
            ByteBuffer bb = elements.decompose(elt);
            bbs.add(bb);
            size += 2 + bb.remaining();
        }
        return pack(bbs, value.size(), size);
    }

    protected void appendToStringBuilder(StringBuilder sb)
    {
        sb.append(getClass().getName()).append(TypeParser.stringifyTypeParameters(Collections.<AbstractType<?>>singletonList(elements)));
    }

    public ByteBuffer serialize(List<Pair<ByteBuffer, IColumn>> columns)
    {
        columns = enforceLimit(columns);

        List<ByteBuffer> bbs = new ArrayList<ByteBuffer>(columns.size());
        int size = 0;
        for (Pair<ByteBuffer, IColumn> p : columns)
        {
            bbs.add(p.right.value());
            size += 2 + p.right.value().remaining();
        }
        return pack(bbs, columns.size(), size);
    }
}
