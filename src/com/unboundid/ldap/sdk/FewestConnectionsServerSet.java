/*
 * Copyright 2013-2019 Ping Identity Corporation
 * All Rights Reserved.
 */
/*
 * Copyright (C) 2013-2019 Ping Identity Corporation
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPLv2 only)
 * or the terms of the GNU Lesser General Public License (LGPLv2.1 only)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 */
package com.unboundid.ldap.sdk;



import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.SocketFactory;

import com.unboundid.util.Debug;
import com.unboundid.util.NotMutable;
import com.unboundid.util.ObjectPair;
import com.unboundid.util.StaticUtils;
import com.unboundid.util.ThreadSafety;
import com.unboundid.util.ThreadSafetyLevel;
import com.unboundid.util.Validator;



/**
 * This class provides a server set implementation that will establish a
 * connection to the server with the fewest established connections previously
 * created by the same server set instance.  If there are multiple servers that
 * share the fewest number of established connections, the first one in the list
 * will be chosen.  If a server is unavailable when an attempt is made to
 * establish a connection to it, then the connection will be established to the
 * available server with the next fewest number of established connections.
 * <BR><BR>
 * Note that this server set implementation is primarily intended for use with
 * connection pools, but is also suitable for cases in which standalone
 * connections are created as long as there will not be any attempt to close the
 * connections when they are re-established.  It is not suitable for use in
 * connections that may be re-established one or more times after being closed.
 * <BR><BR>
 * <H2>Example</H2>
 * The following example demonstrates the process for creating a fewest
 * connections server set that may be used to establish connections to either of
 * two servers.
 * <PRE>
 * // Create arrays with the addresses and ports of the directory server
 * // instances.
 * String[] addresses =
 * {
 *   server1Address,
 *   server2Address
 * };
 * int[] ports =
 * {
 *   server1Port,
 *   server2Port
 * };
 *
 * // Create the server set using the address and port arrays.
 * FewestConnectionsServerSet fewestConnectionsSet =
 *      new FewestConnectionsServerSet(addresses, ports);
 *
 * // Verify that we can establish a single connection using the server set.
 * LDAPConnection connection = fewestConnectionsSet.getConnection();
 * RootDSE rootDSEFromConnection = connection.getRootDSE();
 * connection.close();
 *
 * // Verify that we can establish a connection pool using the server set.
 * SimpleBindRequest bindRequest =
 *      new SimpleBindRequest("uid=pool.user,dc=example,dc=com", "password");
 * LDAPConnectionPool pool =
 *      new LDAPConnectionPool(fewestConnectionsSet, bindRequest, 10);
 * RootDSE rootDSEFromPool = pool.getRootDSE();
 * pool.close();
 * </PRE>
 */
@NotMutable()
@ThreadSafety(level=ThreadSafetyLevel.COMPLETELY_THREADSAFE)
public final class FewestConnectionsServerSet
       extends ServerSet
{
  // The bind request to use to authenticate connections created by this
  // server set.
  private final BindRequest bindRequest;

  // The set of connection options to use for new connections.
  private final LDAPConnectionOptions connectionOptions;

  // A map with the number of connections currently established for each server.
  private final Map<ObjectPair<String,Integer>,AtomicLong>
       connectionCountsByServer;

  // The post-connect processor to invoke against connections created by this
  // server set.
  private final PostConnectProcessor postConnectProcessor;

  // The socket factory to use to establish connections.
  private final SocketFactory socketFactory;



  /**
   * Creates a new fewest connections server set with the specified set of
   * directory server addresses and port numbers.  It will use the default
   * socket factory provided by the JVM to create the underlying sockets.
   *
   * @param  addresses  The addresses of the directory servers to which the
   *                    connections should be established.  It must not be
   *                    {@code null} or empty.
   * @param  ports      The ports of the directory servers to which the
   *                    connections should be established.  It must not be
   *                    {@code null}, and it must have the same number of
   *                    elements as the {@code addresses} array.  The order of
   *                    elements in the {@code addresses} array must correspond
   *                    to the order of elements in the {@code ports} array.
   */
  public FewestConnectionsServerSet(final String[] addresses, final int[] ports)
  {
    this(addresses, ports, null, null);
  }



  /**
   * Creates a new fewest connections server set with the specified set of
   * directory server addresses and port numbers.  It will use the default
   * socket factory provided by the JVM to create the underlying sockets.
   *
   * @param  addresses          The addresses of the directory servers to which
   *                            the connections should be established.  It must
   *                            not be {@code null} or empty.
   * @param  ports              The ports of the directory servers to which the
   *                            connections should be established.  It must not
   *                            be {@code null}, and it must have the same
   *                            number of elements as the {@code addresses}
   *                            array.  The order of elements in the
   *                            {@code addresses} array must correspond to the
   *                            order of elements in the {@code ports} array.
   * @param  connectionOptions  The set of connection options to use for the
   *                            underlying connections.
   */
  public FewestConnectionsServerSet(final String[] addresses, final int[] ports,
              final LDAPConnectionOptions connectionOptions)
  {
    this(addresses, ports, null, connectionOptions);
  }



  /**
   * Creates a new fewest connections server set with the specified set of
   * directory server addresses and port numbers.  It will use the provided
   * socket factory to create the underlying sockets.
   *
   * @param  addresses      The addresses of the directory servers to which the
   *                        connections should be established.  It must not be
   *                        {@code null} or empty.
   * @param  ports          The ports of the directory servers to which the
   *                        connections should be established.  It must not be
   *                        {@code null}, and it must have the same number of
   *                        elements as the {@code addresses} array.  The order
   *                        of elements in the {@code addresses} array must
   *                        correspond to the order of elements in the
   *                        {@code ports} array.
   * @param  socketFactory  The socket factory to use to create the underlying
   *                        connections.
   */
  public FewestConnectionsServerSet(final String[] addresses, final int[] ports,
                                    final SocketFactory socketFactory)
  {
    this(addresses, ports, socketFactory, null);
  }



  /**
   * Creates a new fewest connections server set with the specified set of
   * directory server addresses and port numbers.  It will use the provided
   * socket factory to create the underlying sockets.
   *
   * @param  addresses          The addresses of the directory servers to which
   *                            the connections should be established.  It must
   *                            not be {@code null} or empty.
   * @param  ports              The ports of the directory servers to which the
   *                            connections should be established.  It must not
   *                            be {@code null}, and it must have the same
   *                            number of elements as the {@code addresses}
   *                            array.  The order of elements in the
   *                            {@code addresses} array must correspond to the
   *                            order of elements in the {@code ports} array.
   * @param  socketFactory      The socket factory to use to create the
   *                            underlying connections.
   * @param  connectionOptions  The set of connection options to use for the
   *                            underlying connections.
   */
  public FewestConnectionsServerSet(final String[] addresses, final int[] ports,
              final SocketFactory socketFactory,
              final LDAPConnectionOptions connectionOptions)
  {
    this(addresses, ports, socketFactory, connectionOptions, null, null);
  }



  /**
   * Creates a new fewest connections server set with the specified set of
   * directory server addresses and port numbers.  It will use the provided
   * socket factory to create the underlying sockets.
   *
   * @param  addresses             The addresses of the directory servers to
   *                               which the connections should be established.
   *                               It must not be {@code null} or empty.
   * @param  ports                 The ports of the directory servers to which
   *                               the connections should be established.  It
   *                               must not be {@code null}, and it must have
   *                               the same number of elements as the
   *                               {@code addresses} array.  The order of
   *                               elements in the {@code addresses} array must
   *                               correspond to the order of elements in the
   *                               {@code ports} array.
   * @param  socketFactory         The socket factory to use to create the
   *                               underlying connections.
   * @param  connectionOptions     The set of connection options to use for the
   *                               underlying connections.
   * @param  bindRequest           The bind request that should be used to
   *                               authenticate newly-established connections.
   *                               It may be {@code null} if this server set
   *                               should not perform any authentication.
   * @param  postConnectProcessor  The post-connect processor that should be
   *                               invoked on newly-established connections.  It
   *                               may be {@code null} if this server set should
   *                               not perform any post-connect processing.
   */
  public FewestConnectionsServerSet(final String[] addresses, final int[] ports,
              final SocketFactory socketFactory,
              final LDAPConnectionOptions connectionOptions,
              final BindRequest bindRequest,
              final PostConnectProcessor postConnectProcessor)
  {
    Validator.ensureNotNull(addresses, ports);
    Validator.ensureTrue(addresses.length > 0,
         "FewestConnectionsServerSet.addresses must not be empty.");
    Validator.ensureTrue(addresses.length == ports.length,
         "FewestConnectionsServerSet addresses and ports arrays must be " +
              "the same size.");

    final LinkedHashMap<ObjectPair<String,Integer>,AtomicLong> m =
         new LinkedHashMap<>(StaticUtils.computeMapCapacity(ports.length));
    for (int i=0; i < addresses.length; i++)
    {
      m.put(new ObjectPair<>(addresses[i], ports[i]), new AtomicLong(0L));
    }

    connectionCountsByServer = Collections.unmodifiableMap(m);

    this.bindRequest = bindRequest;
    this.postConnectProcessor = postConnectProcessor;

    if (socketFactory == null)
    {
      this.socketFactory = SocketFactory.getDefault();
    }
    else
    {
      this.socketFactory = socketFactory;
    }

    if (connectionOptions == null)
    {
      this.connectionOptions = new LDAPConnectionOptions();
    }
    else
    {
      this.connectionOptions = connectionOptions;
    }
  }



  /**
   * Retrieves the addresses of the directory servers to which the connections
   * should be established.
   *
   * @return  The addresses of the directory servers to which the connections
   *          should be established.
   */
  public String[] getAddresses()
  {
    int i = 0;
    final String[] addresses = new String[connectionCountsByServer.size()];
    for (final ObjectPair<String,Integer> hostPort :
         connectionCountsByServer.keySet())
    {
      addresses[i++] = hostPort.getFirst();
    }

    return addresses;
  }



  /**
   * Retrieves the ports of the directory servers to which the connections
   * should be established.
   *
   * @return  The ports of the directory servers to which the connections should
   *          be established.
   */
  public int[] getPorts()
  {
    int i = 0;
    final int[] ports = new int[connectionCountsByServer.size()];
    for (final ObjectPair<String,Integer> hostPort :
         connectionCountsByServer.keySet())
    {
      ports[i++] = hostPort.getSecond();
    }

    return ports;
  }



  /**
   * Retrieves the socket factory that will be used to establish connections.
   *
   * @return  The socket factory that will be used to establish connections.
   */
  public SocketFactory getSocketFactory()
  {
    return socketFactory;
  }



  /**
   * Retrieves the set of connection options that will be used for underlying
   * connections.
   *
   * @return  The set of connection options that will be used for underlying
   *          connections.
   */
  public LDAPConnectionOptions getConnectionOptions()
  {
    return connectionOptions;
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public boolean includesAuthentication()
  {
    return (bindRequest != null);
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public boolean includesPostConnectProcessing()
  {
    return (postConnectProcessor != null);
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public LDAPConnection getConnection()
         throws LDAPException
  {
    return getConnection(null);
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public LDAPConnection getConnection(
                             final LDAPConnectionPoolHealthCheck healthCheck)
         throws LDAPException
  {
    // Organize the servers int lists by increasing numbers of connections.
    final TreeMap<Long,List<ObjectPair<String,Integer>>> serversByCount =
         new TreeMap<>();
    for (final Map.Entry<ObjectPair<String,Integer>,AtomicLong> e :
        connectionCountsByServer.entrySet())
    {
      final ObjectPair<String,Integer> hostPort = e.getKey();
      final long count = e.getValue().get();

      List<ObjectPair<String,Integer>> l = serversByCount.get(count);
      if (l == null)
      {
        l = new ArrayList<>(connectionCountsByServer.size());
        serversByCount.put(count, l);
      }
      l.add(hostPort);
    }


    // Try the servers in order of fewest connections to most.  If there are
    // multiple servers with the same number of connections, then randomize the
    // order of servers in that list to better spread the load across all of
    // the servers.
    LDAPException lastException = null;
    for (final List<ObjectPair<String,Integer>> l : serversByCount.values())
    {
      if (l.size() > 1)
      {
        Collections.shuffle(l);
      }

      for (final ObjectPair<String,Integer> hostPort : l)
      {
        try
        {
          final LDAPConnection conn = new LDAPConnection(socketFactory,
               connectionOptions, hostPort.getFirst(), hostPort.getSecond());
          doBindPostConnectAndHealthCheckProcessing(conn, bindRequest,
               postConnectProcessor, healthCheck);
          connectionCountsByServer.get(hostPort).incrementAndGet();
          associateConnectionWithThisServerSet(conn);
          return conn;
        }
        catch (final LDAPException le)
        {
          Debug.debugException(le);
          lastException = le;
        }
      }
    }


    // If we've gotten here, then we've tried all servers without any success,
    // so throw the last exception that was encountered.
    throw lastException;
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  protected void handleConnectionClosed(final LDAPConnection connection,
                                        final String host, final int port,
                                        final DisconnectType disconnectType,
                                        final String message,
                                        final Throwable cause)
  {
    final ObjectPair<String,Integer> hostPort = new ObjectPair<>(host, port);
    final AtomicLong counter = connectionCountsByServer.get(hostPort);
    if (counter != null)
    {
      final long remainingCount = counter.decrementAndGet();
      if (remainingCount < 0L)
      {
        // This shouldn't happen.  If it does, reset it back to zero.
        counter.compareAndSet(remainingCount, 0L);
      }
    }
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public void toString(final StringBuilder buffer)
  {
    buffer.append("FewestConnectionsServerSet(servers={");

    final Iterator<Map.Entry<ObjectPair<String,Integer>,AtomicLong>>
         cbsIterator = connectionCountsByServer.entrySet().iterator();
    while (cbsIterator.hasNext())
    {
      final Map.Entry<ObjectPair<String,Integer>,AtomicLong> e =
           cbsIterator.next();
      final ObjectPair<String,Integer> hostPort = e.getKey();
      final long count = e.getValue().get();

      buffer.append('\'');
      buffer.append(hostPort.getFirst());
      buffer.append(':');
      buffer.append(hostPort.getSecond());
      buffer.append("':");
      buffer.append(count);

      if (cbsIterator.hasNext())
      {
        buffer.append(", ");
      }
    }

    buffer.append("}, includesAuthentication=");
    buffer.append(bindRequest != null);
    buffer.append(", includesPostConnectProcessing=");
    buffer.append(postConnectProcessor != null);
    buffer.append(')');
  }
}
