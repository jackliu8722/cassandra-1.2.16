package org.apache.cassandra.thrift;

/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

import org.apache.hadoop.conf.Configuration;
import org.apache.thrift.transport.TTransport;

import java.util.Map;
import java.util.Set;


/**
 * Transport factory for establishing thrift connections from clients to a remote server.
 */
public interface ITransportFactory
{
    static final String PROPERTY_KEY = "cassandra.client.transport.factory";
    static final String LONG_OPTION = "transport-factory";
    static final String SHORT_OPTION = "tr";

    /**
     * Opens a client transport to a thrift server.
     * Example:
     *
     * <pre>
     * TTransport transport = clientTransportFactory.openTransport(address, port);
     * Cassandra.Iface client = new Cassandra.Client(new BinaryProtocol(transport));
     * </pre>
     *
     * @param host fully qualified hostname of the server
     * @param port RPC port of the server
     * @param conf Hadoop configuration
     * @return open and ready to use transport
     * @throws Exception implementation defined; usually throws TTransportException or IOException
     *         if the connection cannot be established
     */
    TTransport openTransport(String host, int port, Configuration conf) throws Exception;

    /**
     * Sets an implementation defined set of options.
     * Keys in this map must conform to the set set returned by TClientTransportFactory#supportedOptions.
     * @param options option map
     */
    void setOptions(Map<String, String> options);

    /**
     * @return set of options supported by this transport factory implementation
     */
    Set<String> supportedOptions();
}

