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
package org.apache.cassandra.streaming;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Collection;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Directories;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.FBUtilities;

/**
 * for streaming data from other nodes in to this one.
 * Sends a STREAM_REQUEST Message to the source node(s), after which StreamOut on that side takes over.
 * See StreamOut for details.
 */
public class StreamIn
{
    private static final Logger logger = LoggerFactory.getLogger(StreamIn.class);

    /** Request ranges for all column families in the given keyspace. */
    public static void requestRanges(InetAddress source, String tableName, Collection<Range<Token>> ranges, IStreamCallback callback, OperationType type)
    {
        requestRanges(source, tableName, Table.open(tableName).getColumnFamilyStores(), ranges, callback, type);
    }

    /**
     * Request ranges to be transferred from specific CFs
     */
    public static void requestRanges(InetAddress source, String tableName, Collection<ColumnFamilyStore> columnFamilies, Collection<Range<Token>> ranges, IStreamCallback callback, OperationType type)
    {
        assert ranges.size() > 0;

        if (logger.isDebugEnabled())
            logger.debug("Requesting from {} ranges {}", source, StringUtils.join(ranges, ", "));
        StreamInSession session = StreamInSession.create(source, callback);
        StreamRequest srm = new StreamRequest(FBUtilities.getBroadcastAddress(),
                                                            ranges,
                                                            tableName,
                                                            columnFamilies,
                                                            session.getSessionId(),
                                                            type);
        MessagingService.instance().sendOneWay(srm.createMessage(), source);
    }

    /** Translates remote files to local files by creating a local sstable per remote sstable. */
    public static PendingFile getContextMapping(PendingFile remote) throws IOException
    {
        /* Create a local sstable for each remote sstable */
        Descriptor remotedesc = remote.desc;
        if (!remotedesc.isStreamCompatible())
            throw new UnsupportedOperationException(String.format("SSTable %s is not compatible with current version %s",
                                                                  remote.getFilename(), Descriptor.Version.CURRENT));

        // new local sstable
        Table table = Table.open(remotedesc.ksname);
        ColumnFamilyStore cfStore = table.getColumnFamilyStore(remotedesc.cfname);
        Directories.DataDirectory localDir = cfStore.directories.getWriteableLocation();
        if (localDir == null)
            throw new RuntimeException("Insufficient disk space to store " + remote.size + " bytes");
        Descriptor localdesc = Descriptor.fromFilename(cfStore.getTempSSTablePath(cfStore.directories.getLocationForDisk(localDir)));

        return new PendingFile(localdesc, remote);
    }
}
