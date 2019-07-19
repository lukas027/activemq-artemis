/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.core.remoting.impl.netty;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.apache.activemq.artemis.api.config.ActiveMQDefaultConfiguration;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.management.CoreNotificationType;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryImpl;
import org.apache.activemq.artemis.core.protocol.ProtocolHandler;
import org.apache.activemq.artemis.core.remoting.impl.AbstractAcceptor;
import org.apache.activemq.artemis.core.remoting.impl.ssl.SSLSupport;
import org.apache.activemq.artemis.core.security.ActiveMQPrincipal;
import org.apache.activemq.artemis.core.server.ActiveMQComponent;
import org.apache.activemq.artemis.core.server.ActiveMQMessageBundle;
import org.apache.activemq.artemis.core.server.ActiveMQServerLogger;
import org.apache.activemq.artemis.core.server.cluster.ClusterConnection;
import org.apache.activemq.artemis.core.server.management.Notification;
import org.apache.activemq.artemis.core.server.management.NotificationService;
import org.apache.activemq.artemis.spi.core.protocol.ProtocolManager;
import org.apache.activemq.artemis.spi.core.remoting.BufferHandler;
import org.apache.activemq.artemis.spi.core.remoting.Connection;
import org.apache.activemq.artemis.spi.core.remoting.ServerConnectionLifeCycleListener;
import org.apache.activemq.artemis.utils.ActiveMQThreadFactory;
import org.apache.activemq.artemis.utils.ConfigurationHelper;
import org.apache.activemq.artemis.utils.collections.TypedProperties;
import org.jboss.logging.Logger;

/**
 * A Netty TCP Acceptor that is embedding Netty.
 */
public class NettyAcceptor extends AbstractAcceptor {

   private static final Logger logger = Logger.getLogger(NettyAcceptor.class);


   public static String INVM_ACCEPTOR_TYPE = "IN-VM";
   public static String NIO_ACCEPTOR_TYPE = "NIO";
   public static String EPOLL_ACCEPTOR_TYPE = "EPOLL";
   public static String KQUEUE_ACCEPTOR_TYPE = "KQUEUE";

   static {
      // Disable default Netty leak detection if the Netty leak detection level system properties are not in use
      if (System.getProperty("io.netty.leakDetectionLevel") == null && System.getProperty("io.netty.leakDetection.level") == null) {
         ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
      }
   }

   //just for debug
   private final String protocolsString;

   private final String name;

   private final ClusterConnection clusterConnection;

   private Class<? extends ServerChannel> channelClazz;
   private EventLoopGroup eventLoopGroup;

   private volatile ChannelGroup serverChannelGroup;

   private volatile ChannelGroup channelGroup;

   private ServerBootstrap bootstrap;

   private final BufferHandler handler;

   private final ServerConnectionLifeCycleListener listener;

   private final boolean sslEnabled;

   private final boolean useInvm;

   private final boolean useEpoll;

   private final boolean useKQueue;

   private final ProtocolHandler protocolHandler;

   private final String host;

   private final int port;

   private final String keyStoreProvider;

   // non-final for testing purposes
   private String keyStorePath;

   private final String keyStorePassword;

   private final String trustStoreProvider;

   private final String trustStorePath;

   private final String trustStorePassword;

   private final String crlPath;

   private final String enabledCipherSuites;

   private final String enabledProtocols;

   private final boolean needClientAuth;

   private final boolean wantClientAuth;

   private final String sslProvider;

   private final boolean verifyHost;

   private final String kerb5Config;

   private String sniHost;

   private final boolean tcpNoDelay;

   private final int backlog;

   private final int tcpSendBufferSize;

   private final int tcpReceiveBufferSize;

   private final int writeBufferLowWaterMark;

   private final int writeBufferHighWaterMark;

   private int remotingThreads;

   private final ConcurrentMap<Object, NettyServerConnection> connections = new ConcurrentHashMap<>();

   private final Map<String, Object> configuration;

   private final ScheduledExecutorService scheduledThreadPool;

   private NotificationService notificationService;

   /** The amount of time we wait before new tasks are added during a shutdown period. */
   private int quietPeriod;

   /** The total amount of time we wait before a hard shutdown. */
   private int shutdownTimeout;

   private boolean paused;

   private BatchFlusher flusher;

   private ScheduledFuture<?> batchFlusherFuture;

   private final long batchDelay;

   private final boolean directDeliver;

   private final boolean httpUpgradeEnabled;

   private final long connectionsAllowed;

   private Map<String, Object> extraConfigs;


   final AtomicBoolean warningPrinted = new AtomicBoolean(false);

   final Executor failureExecutor;

   public NettyAcceptor(final String name,
                        final ClusterConnection clusterConnection,
                        final Map<String, Object> configuration,
                        final BufferHandler handler,
                        final ServerConnectionLifeCycleListener listener,
                        final ScheduledExecutorService scheduledThreadPool,
                        final Executor failureExecutor,
                        final Map<String, ProtocolManager> protocolMap) {
      super(protocolMap);

      this.failureExecutor = failureExecutor;

      this.name = name;

      this.clusterConnection = clusterConnection;

      this.configuration = configuration;

      this.handler = handler;

      this.listener = listener;

      sslEnabled = ConfigurationHelper.getBooleanProperty(TransportConstants.SSL_ENABLED_PROP_NAME, TransportConstants.DEFAULT_SSL_ENABLED, configuration);

      kerb5Config = ConfigurationHelper.getStringProperty(TransportConstants.SSL_KRB5_CONFIG_PROP_NAME, TransportConstants.DEFAULT_SSL_KRB5_CONFIG, configuration);

      remotingThreads = ConfigurationHelper.getIntProperty(TransportConstants.NIO_REMOTING_THREADS_PROPNAME, -1, configuration);
      remotingThreads = ConfigurationHelper.getIntProperty(TransportConstants.REMOTING_THREADS_PROPNAME, remotingThreads, configuration);

      useEpoll = ConfigurationHelper.getBooleanProperty(TransportConstants.USE_EPOLL_PROP_NAME, TransportConstants.DEFAULT_USE_EPOLL, configuration);
      useKQueue = ConfigurationHelper.getBooleanProperty(TransportConstants.USE_KQUEUE_PROP_NAME, TransportConstants.DEFAULT_USE_KQUEUE, configuration);

      backlog = ConfigurationHelper.getIntProperty(TransportConstants.BACKLOG_PROP_NAME, -1, configuration);
      useInvm = ConfigurationHelper.getBooleanProperty(TransportConstants.USE_INVM_PROP_NAME, TransportConstants.DEFAULT_USE_INVM, configuration);

      this.protocolHandler = new ProtocolHandler(protocolMap, this, scheduledThreadPool);

      this.protocolsString = getProtocols(protocolMap);

      this.quietPeriod = ConfigurationHelper.getIntProperty(TransportConstants.QUIET_PERIOD, TransportConstants.DEFAULT_QUIET_PERIOD, configuration);

      this.shutdownTimeout = ConfigurationHelper.getIntProperty(TransportConstants.SHUTDOWN_TIMEOUT, TransportConstants.DEFAULT_SHUTDOWN_TIMEOUT, configuration);

      host = ConfigurationHelper.getStringProperty(TransportConstants.HOST_PROP_NAME, TransportConstants.DEFAULT_HOST, configuration);
      port = ConfigurationHelper.getIntProperty(TransportConstants.PORT_PROP_NAME, TransportConstants.DEFAULT_PORT, configuration);
      if (sslEnabled) {
         keyStoreProvider = ConfigurationHelper.getStringProperty(TransportConstants.KEYSTORE_PROVIDER_PROP_NAME, TransportConstants.DEFAULT_KEYSTORE_PROVIDER, configuration);

         keyStorePath = ConfigurationHelper.getStringProperty(TransportConstants.KEYSTORE_PATH_PROP_NAME, TransportConstants.DEFAULT_KEYSTORE_PATH, configuration);

         keyStorePassword = ConfigurationHelper.getPasswordProperty(TransportConstants.KEYSTORE_PASSWORD_PROP_NAME, TransportConstants.DEFAULT_KEYSTORE_PASSWORD, configuration, ActiveMQDefaultConfiguration.getPropMaskPassword(), ActiveMQDefaultConfiguration.getPropPasswordCodec());

         trustStoreProvider = ConfigurationHelper.getStringProperty(TransportConstants.TRUSTSTORE_PROVIDER_PROP_NAME, TransportConstants.DEFAULT_TRUSTSTORE_PROVIDER, configuration);

         trustStorePath = ConfigurationHelper.getStringProperty(TransportConstants.TRUSTSTORE_PATH_PROP_NAME, TransportConstants.DEFAULT_TRUSTSTORE_PATH, configuration);

         trustStorePassword = ConfigurationHelper.getPasswordProperty(TransportConstants.TRUSTSTORE_PASSWORD_PROP_NAME, TransportConstants.DEFAULT_TRUSTSTORE_PASSWORD, configuration, ActiveMQDefaultConfiguration.getPropMaskPassword(), ActiveMQDefaultConfiguration.getPropPasswordCodec());

         crlPath = ConfigurationHelper.getStringProperty(TransportConstants.CRL_PATH_PROP_NAME, TransportConstants.DEFAULT_CRL_PATH, configuration);

         enabledCipherSuites = ConfigurationHelper.getStringProperty(TransportConstants.ENABLED_CIPHER_SUITES_PROP_NAME, TransportConstants.DEFAULT_ENABLED_CIPHER_SUITES, configuration);

         enabledProtocols = ConfigurationHelper.getStringProperty(TransportConstants.ENABLED_PROTOCOLS_PROP_NAME, TransportConstants.DEFAULT_ENABLED_PROTOCOLS, configuration);

         needClientAuth = ConfigurationHelper.getBooleanProperty(TransportConstants.NEED_CLIENT_AUTH_PROP_NAME, TransportConstants.DEFAULT_NEED_CLIENT_AUTH, configuration);

         wantClientAuth = ConfigurationHelper.getBooleanProperty(TransportConstants.WANT_CLIENT_AUTH_PROP_NAME, TransportConstants.DEFAULT_WANT_CLIENT_AUTH, configuration);

         verifyHost = ConfigurationHelper.getBooleanProperty(TransportConstants.VERIFY_HOST_PROP_NAME, TransportConstants.DEFAULT_VERIFY_HOST, configuration);

         sslProvider = ConfigurationHelper.getStringProperty(TransportConstants.SSL_PROVIDER, TransportConstants.DEFAULT_SSL_PROVIDER, configuration);

         sniHost = ConfigurationHelper.getStringProperty(TransportConstants.SNIHOST_PROP_NAME, TransportConstants.DEFAULT_SNIHOST_CONFIG, configuration);
      } else {
         keyStoreProvider = TransportConstants.DEFAULT_KEYSTORE_PROVIDER;
         keyStorePath = TransportConstants.DEFAULT_KEYSTORE_PATH;
         keyStorePassword = TransportConstants.DEFAULT_KEYSTORE_PASSWORD;
         trustStoreProvider = TransportConstants.DEFAULT_TRUSTSTORE_PROVIDER;
         trustStorePath = TransportConstants.DEFAULT_TRUSTSTORE_PATH;
         trustStorePassword = TransportConstants.DEFAULT_TRUSTSTORE_PASSWORD;
         crlPath = TransportConstants.DEFAULT_CRL_PATH;
         enabledCipherSuites = TransportConstants.DEFAULT_ENABLED_CIPHER_SUITES;
         enabledProtocols = TransportConstants.DEFAULT_ENABLED_PROTOCOLS;
         needClientAuth = TransportConstants.DEFAULT_NEED_CLIENT_AUTH;
         wantClientAuth = TransportConstants.DEFAULT_WANT_CLIENT_AUTH;
         verifyHost = TransportConstants.DEFAULT_VERIFY_HOST;
         sslProvider = TransportConstants.DEFAULT_SSL_PROVIDER;
         sniHost = TransportConstants.DEFAULT_SNIHOST_CONFIG;
      }

      tcpNoDelay = ConfigurationHelper.getBooleanProperty(TransportConstants.TCP_NODELAY_PROPNAME, TransportConstants.DEFAULT_TCP_NODELAY, configuration);
      tcpSendBufferSize = ConfigurationHelper.getIntProperty(TransportConstants.TCP_SENDBUFFER_SIZE_PROPNAME, TransportConstants.DEFAULT_TCP_SENDBUFFER_SIZE, configuration);
      tcpReceiveBufferSize = ConfigurationHelper.getIntProperty(TransportConstants.TCP_RECEIVEBUFFER_SIZE_PROPNAME, TransportConstants.DEFAULT_TCP_RECEIVEBUFFER_SIZE, configuration);
      this.writeBufferLowWaterMark = ConfigurationHelper.getIntProperty(TransportConstants.WRITE_BUFFER_LOW_WATER_MARK_PROPNAME, TransportConstants.DEFAULT_WRITE_BUFFER_LOW_WATER_MARK, configuration);
      this.writeBufferHighWaterMark = ConfigurationHelper.getIntProperty(TransportConstants.WRITE_BUFFER_HIGH_WATER_MARK_PROPNAME, TransportConstants.DEFAULT_WRITE_BUFFER_HIGH_WATER_MARK, configuration);
      this.scheduledThreadPool = scheduledThreadPool;

      batchDelay = ConfigurationHelper.getLongProperty(TransportConstants.BATCH_DELAY, TransportConstants.DEFAULT_BATCH_DELAY, configuration);

      directDeliver = ConfigurationHelper.getBooleanProperty(TransportConstants.DIRECT_DELIVER, TransportConstants.DEFAULT_DIRECT_DELIVER, configuration);

      httpUpgradeEnabled = ConfigurationHelper.getBooleanProperty(TransportConstants.HTTP_UPGRADE_ENABLED_PROP_NAME, TransportConstants.DEFAULT_HTTP_UPGRADE_ENABLED, configuration);

      connectionsAllowed = ConfigurationHelper.getLongProperty(TransportConstants.CONNECTIONS_ALLOWED, TransportConstants.DEFAULT_CONNECTIONS_ALLOWED, configuration);
   }

   @Override
   public synchronized void start() throws Exception {
      if (channelClazz != null) {
         // Already started
         return;
      }

      String acceptorType;

      if (useInvm) {
         acceptorType = INVM_ACCEPTOR_TYPE;
         channelClazz = LocalServerChannel.class;
         eventLoopGroup = new DefaultEventLoopGroup();
      } else {

         if (remotingThreads == -1) {
            // Default to number of cores * 3
            remotingThreads = Runtime.getRuntime().availableProcessors() * 3;
         }

         if (useEpoll && CheckDependencies.isEpollAvailable()) {
            channelClazz = EpollServerSocketChannel.class;
            eventLoopGroup = new EpollEventLoopGroup(remotingThreads, AccessController.doPrivileged(new PrivilegedAction<ActiveMQThreadFactory>() {
               @Override
               public ActiveMQThreadFactory run() {
                  return new ActiveMQThreadFactory("activemq-netty-threads", true, ClientSessionFactoryImpl.class.getClassLoader());
               }
            }));
            acceptorType = EPOLL_ACCEPTOR_TYPE;

            logger.debug("Acceptor using native epoll");
         } else if (useKQueue && CheckDependencies.isKQueueAvailable()) {
            channelClazz = KQueueServerSocketChannel.class;
            eventLoopGroup = new KQueueEventLoopGroup(remotingThreads, AccessController.doPrivileged(new PrivilegedAction<ActiveMQThreadFactory>() {
               @Override
               public ActiveMQThreadFactory run() {
                  return new ActiveMQThreadFactory("activemq-netty-threads", true, ClientSessionFactoryImpl.class.getClassLoader());
               }
            }));
            acceptorType = KQUEUE_ACCEPTOR_TYPE;

            logger.debug("Acceptor using native kqueue");
         } else {
            channelClazz = NioServerSocketChannel.class;
            eventLoopGroup = new NioEventLoopGroup(remotingThreads, AccessController.doPrivileged(new PrivilegedAction<ActiveMQThreadFactory>() {
               @Override
               public ActiveMQThreadFactory run() {
                  return new ActiveMQThreadFactory("activemq-netty-threads", true, ClientSessionFactoryImpl.class.getClassLoader());
               }
            }));
            acceptorType = NIO_ACCEPTOR_TYPE;
            logger.debug("Acceptor using nio");
         }
      }

      bootstrap = new ServerBootstrap();
      bootstrap.group(eventLoopGroup);
      bootstrap.channel(channelClazz);

      ChannelInitializer<Channel> factory = new ChannelInitializer<Channel>() {
         @Override
         public void initChannel(Channel channel) throws Exception {
            ChannelPipeline pipeline = channel.pipeline();
            if (sslEnabled) {
               pipeline.addLast("ssl", getSslHandler(channel.alloc()));
               pipeline.addLast("sslHandshakeExceptionHandler", new SslHandshakeExceptionHandler());
            }
            pipeline.addLast(protocolHandler.getProtocolDecoder());
         }
      };
      bootstrap.childHandler(factory);

      // Bind
      bootstrap.childOption(ChannelOption.TCP_NODELAY, tcpNoDelay);
      if (tcpReceiveBufferSize != -1) {
         bootstrap.childOption(ChannelOption.SO_RCVBUF, tcpReceiveBufferSize);
      }
      if (tcpSendBufferSize != -1) {
         bootstrap.childOption(ChannelOption.SO_SNDBUF, tcpSendBufferSize);
      }
      final int writeBufferLowWaterMark = this.writeBufferLowWaterMark != -1 ? this.writeBufferLowWaterMark : WriteBufferWaterMark.DEFAULT.low();
      final int writeBufferHighWaterMark = this.writeBufferHighWaterMark != -1 ? this.writeBufferHighWaterMark : WriteBufferWaterMark.DEFAULT.high();
      final WriteBufferWaterMark writeBufferWaterMark = new WriteBufferWaterMark(writeBufferLowWaterMark, writeBufferHighWaterMark);
      bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark);
      if (backlog != -1) {
         bootstrap.option(ChannelOption.SO_BACKLOG, backlog);
      }
      bootstrap.option(ChannelOption.SO_REUSEADDR, true);
      bootstrap.childOption(ChannelOption.SO_REUSEADDR, true);
      bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
      channelGroup = new DefaultChannelGroup("activemq-accepted-channels", GlobalEventExecutor.INSTANCE);

      serverChannelGroup = new DefaultChannelGroup("activemq-acceptor-channels", GlobalEventExecutor.INSTANCE);

      if (httpUpgradeEnabled) {
         // the channel will be bound by the Web container and hand over after the HTTP Upgrade
         // handshake is successful
      } else {
         startServerChannels();

         paused = false;

         if (notificationService != null) {
            TypedProperties props = new TypedProperties();
            props.putSimpleStringProperty(new SimpleString("factory"), new SimpleString(NettyAcceptorFactory.class.getName()));
            props.putSimpleStringProperty(new SimpleString("host"), new SimpleString(host));
            props.putIntProperty(new SimpleString("port"), port);
            Notification notification = new Notification(null, CoreNotificationType.ACCEPTOR_STARTED, props);
            notificationService.sendNotification(notification);
         }

         ActiveMQServerLogger.LOGGER.startedAcceptor(acceptorType, host, port, protocolsString);
      }

      if (batchDelay > 0) {
         flusher = new BatchFlusher();

         batchFlusherFuture = scheduledThreadPool.scheduleWithFixedDelay(flusher, batchDelay, batchDelay, TimeUnit.MILLISECONDS);
      }
   }

   @Override
   public String getName() {
      return name;
   }

   // only for testing purposes
   public void setKeyStorePath(String keyStorePath) {
      this.keyStorePath = keyStorePath;
   }

   /**
    * Transfers the Netty channel that has been created outside of this NettyAcceptor
    * to control it and configure it according to this NettyAcceptor setting.
    *
    * @param channel A Netty channel created outside this NettyAcceptor.
    */
   public void transfer(Channel channel) {
      if (paused || eventLoopGroup == null) {
         throw ActiveMQMessageBundle.BUNDLE.acceptorUnavailable();
      }
      channel.pipeline().addLast(protocolHandler.getProtocolDecoder());
   }

   @Override
   public void reload() {
      serverChannelGroup.disconnect();
      serverChannelGroup.clear();
      startServerChannels();
   }

   public synchronized SslHandler getSslHandler(ByteBufAllocator alloc) throws Exception {
      SSLEngine engine;
      if (sslProvider.equals(TransportConstants.OPENSSL_PROVIDER)) {
         engine = loadOpenSslEngine(alloc);
      } else {
         engine = loadJdkSslEngine();
      }

      engine.setUseClientMode(false);

      if (needClientAuth) {
         engine.setNeedClientAuth(true);
      } else if (wantClientAuth) {
         engine.setWantClientAuth(true);
      }

      // setting the enabled cipher suites resets the enabled protocols so we need
      // to save the enabled protocols so that after the customer cipher suite is enabled
      // we can reset the enabled protocols if a customer protocol isn't specified
      String[] originalProtocols = engine.getEnabledProtocols();

      if (enabledCipherSuites != null) {
         try {
            engine.setEnabledCipherSuites(SSLSupport.parseCommaSeparatedListIntoArray(enabledCipherSuites));
         } catch (IllegalArgumentException e) {
            ActiveMQServerLogger.LOGGER.invalidCipherSuite(SSLSupport.parseArrayIntoCommandSeparatedList(engine.getSupportedCipherSuites()));
            throw e;
         }
      }

      if (enabledProtocols != null) {
         try {
            engine.setEnabledProtocols(SSLSupport.parseCommaSeparatedListIntoArray(enabledProtocols));
         } catch (IllegalArgumentException e) {
            ActiveMQServerLogger.LOGGER.invalidProtocol(SSLSupport.parseArrayIntoCommandSeparatedList(engine.getSupportedProtocols()));
            throw e;
         }
      } else {
         engine.setEnabledProtocols(originalProtocols);
      }

      // Strip "SSLv3" from the current enabled protocols to address the POODLE exploit.
      // This recommendation came from http://www.oracle.com/technetwork/java/javase/documentation/cve-2014-3566-2342133.html
      String[] protocols = engine.getEnabledProtocols();
      Set<String> set = new HashSet<>();
      for (String s : protocols) {
         if (s.equalsIgnoreCase("SSLv3") || s.equals("SSLv2Hello")) {
            if (!warningPrinted.get()) {
               ActiveMQServerLogger.LOGGER.disallowedProtocol(s, name);
            }
            continue;
         }
         set.add(s);
      }

      warningPrinted.set(true);

      engine.setEnabledProtocols(set.toArray(new String[set.size()]));

      if (verifyHost) {
         SSLParameters sslParameters = engine.getSSLParameters();
         sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
         engine.setSSLParameters(sslParameters);
      }

      if (sniHost != null) {
         SSLParameters sslParameters = engine.getSSLParameters();
         sslParameters.setSNIMatchers(Arrays.asList(SNIHostName.createSNIMatcher(sniHost)));
         engine.setSSLParameters(sslParameters);
      }

      return new SslHandler(engine);
   }

   private SSLEngine loadJdkSslEngine() throws Exception {
      final SSLContext context;
      try {
         if (kerb5Config == null && keyStorePath == null && TransportConstants.DEFAULT_TRUSTSTORE_PROVIDER.equals(keyStoreProvider))
            throw new IllegalArgumentException("If \"" + TransportConstants.SSL_ENABLED_PROP_NAME + "\" is true then \"" + TransportConstants.KEYSTORE_PATH_PROP_NAME + "\" must be non-null " + "unless an alternative \"" + TransportConstants.KEYSTORE_PROVIDER_PROP_NAME + "\" has been specified.");
         context = new SSLSupport()
            .setKeystoreProvider(keyStoreProvider)
            .setKeystorePath(keyStorePath)
            .setKeystorePassword(keyStorePassword)
            .setTruststoreProvider(trustStoreProvider)
            .setTruststorePath(trustStorePath)
            .setTruststorePassword(trustStorePassword)
            .setCrlPath(crlPath)
            .createContext();
      } catch (Exception e) {
         IllegalStateException ise = new IllegalStateException("Unable to create NettyAcceptor for " + host + ":" + port);
         ise.initCause(e);
         throw ise;
      }
      Subject subject = null;
      if (kerb5Config != null) {
         LoginContext loginContext = new LoginContext(kerb5Config);
         loginContext.login();
         subject = loginContext.getSubject();
      }

      SSLEngine engine = Subject.doAs(subject, new PrivilegedExceptionAction<SSLEngine>() {
         @Override
         public SSLEngine run() {
            if (verifyHost) {
               return context.createSSLEngine(host, port);
            } else {
               return context.createSSLEngine();
            }
         }
      });
      return engine;
   }

   private SSLEngine loadOpenSslEngine(ByteBufAllocator alloc) throws Exception {
      final SslContext context;
      try {
         if (kerb5Config == null && keyStorePath == null && TransportConstants.DEFAULT_TRUSTSTORE_PROVIDER.equals(keyStoreProvider))
            throw new IllegalArgumentException("If \"" + TransportConstants.SSL_ENABLED_PROP_NAME + "\" is true then \"" + TransportConstants.KEYSTORE_PATH_PROP_NAME + "\" must be non-null " + "unless an alternative \"" + TransportConstants.KEYSTORE_PROVIDER_PROP_NAME + "\" has been specified.");
         context = new SSLSupport()
            .setKeystoreProvider(keyStoreProvider)
            .setKeystorePath(keyStorePath)
            .setKeystorePassword(keyStorePassword)
            .setTruststoreProvider(trustStoreProvider)
            .setTruststorePath(trustStorePath)
            .setTruststorePassword(trustStorePassword)
            .setSslProvider(sslProvider)
            .createNettyContext();
      } catch (Exception e) {
         IllegalStateException ise = new IllegalStateException("Unable to create NettyAcceptor for " + host + ":" + port);
         ise.initCause(e);
         throw ise;
      }
      Subject subject = null;
      if (kerb5Config != null) {
         LoginContext loginContext = new LoginContext(kerb5Config);
         loginContext.login();
         subject = loginContext.getSubject();
      }

      SSLEngine engine = Subject.doAs(subject, new PrivilegedExceptionAction<SSLEngine>() {
         @Override
         public SSLEngine run() {
            if (verifyHost) {
               return context.newEngine(alloc, host, port);
            } else {
               return context.newEngine(alloc);
            }
         }
      });
      return engine;
   }

   private void startServerChannels() {
      String[] hosts = TransportConfiguration.splitHosts(host);
      for (String h : hosts) {
         SocketAddress address;
         if (useInvm) {
            address = new LocalAddress(h);
         } else {
            address = new InetSocketAddress(h, port);
         }
         Channel serverChannel = bootstrap.bind(address).syncUninterruptibly().channel();
         serverChannelGroup.add(serverChannel);
      }
   }

   @Override
   public Map<String, Object> getConfiguration() {
      return this.configuration;
   }

   @Override
   public void stop() throws Exception {
      CountDownLatch latch = new CountDownLatch(1);

      asyncStop(latch::countDown);

      latch.await();
   }

   @Override
   public synchronized void asyncStop(Runnable callback) {
      if (channelClazz == null) {
         callback.run();
         return;
      }

      if (protocolHandler != null) {
         protocolHandler.close();
      }

      if (batchFlusherFuture != null) {
         batchFlusherFuture.cancel(false);

         flusher.cancel();

         flusher = null;

         batchFlusherFuture = null;
      }

      // serverChannelGroup has been unbound in pause()
      if (serverChannelGroup != null) {
         serverChannelGroup.close().awaitUninterruptibly();
      }

      if (channelGroup != null) {
         ChannelGroupFuture future = channelGroup.close().awaitUninterruptibly();

         if (!future.isSuccess()) {
            ActiveMQServerLogger.LOGGER.nettyChannelGroupError();
            for (Channel channel : future.group()) {
               if (channel.isActive()) {
                  ActiveMQServerLogger.LOGGER.nettyChannelStillOpen(channel, channel.remoteAddress());
               }
            }
         }
      }

      channelClazz = null;

      for (Connection connection : connections.values()) {
         listener.connectionDestroyed(connection.getID());
      }

      connections.clear();

      if (notificationService != null) {
         TypedProperties props = new TypedProperties();
         props.putSimpleStringProperty(new SimpleString("factory"), new SimpleString(NettyAcceptorFactory.class.getName()));
         props.putSimpleStringProperty(new SimpleString("host"), new SimpleString(host));
         props.putIntProperty(new SimpleString("port"), port);
         Notification notification = new Notification(null, CoreNotificationType.ACCEPTOR_STOPPED, props);
         try {
            notificationService.sendNotification(notification);
         } catch (Exception e) {
            ActiveMQServerLogger.LOGGER.failedToSendNotification(e);
         }
      }

      paused = false;

      // Shutdown the EventLoopGroup if no new task was added for 100ms or if
      // 3000ms elapsed.
      eventLoopGroup.shutdownGracefully(quietPeriod, shutdownTimeout, TimeUnit.MILLISECONDS).addListener(f -> callback.run());
      eventLoopGroup = null;
   }

   @Override
   public boolean isStarted() {
      return channelClazz != null;
   }

   @Override
   public synchronized void pause() {
      if (paused) {
         return;
      }

      if (channelClazz == null) {
         return;
      }

      // We *pause* the acceptor so no new connections are made
      if (serverChannelGroup != null) {
         ChannelGroupFuture future = serverChannelGroup.close().awaitUninterruptibly();
         if (!future.isSuccess()) {
            ActiveMQServerLogger.LOGGER.nettyChannelGroupBindError();
            for (Channel channel : future.group()) {
               if (channel.isActive()) {
                  ActiveMQServerLogger.LOGGER.nettyChannelStillBound(channel, channel.remoteAddress());
               }
            }
         }
      }
      paused = true;
   }

   @Override
   public void setNotificationService(final NotificationService notificationService) {
      this.notificationService = notificationService;
   }

   /**
    * not allowed
    *
    * @param defaultActiveMQPrincipal
    */
   @Override
   public void setDefaultActiveMQPrincipal(ActiveMQPrincipal defaultActiveMQPrincipal) {
      throw new IllegalStateException("unsecure connections not allowed");
   }

   /**
    * only InVM acceptors should allow this
    *
    * @return
    */
   @Override
   public boolean isUnsecurable() {
      return false;
   }

   @Override
   public ClusterConnection getClusterConnection() {
      return clusterConnection;
   }

   public ConnectionCreator createConnectionCreator() {
      return new ActiveMQServerChannelHandler(channelGroup, handler, new Listener(), failureExecutor);
   }

   public int getQuietPeriod() {
      return quietPeriod;
   }

   public NettyAcceptor setQuietPeriod(int quietPeriod) {
      this.quietPeriod = quietPeriod;
      return this;
   }

   public int getShutdownTimeout() {
      return shutdownTimeout;
   }

   public NettyAcceptor setShutdownTimeout(int shutdownTimeout) {
      this.shutdownTimeout = shutdownTimeout;
      return this;
   }

   private static String getProtocols(Map<String, ProtocolManager> protocolManager) {
      StringBuilder sb = new StringBuilder();
      if (protocolManager != null) {
         Set<String> strings = protocolManager.keySet();
         for (String string : strings) {
            if (sb.length() > 0) {
               sb.append(",");
            }
            sb.append(string);
         }
      }
      return sb.toString();
   }

   // Inner classes -----------------------------------------------------------------------------

   private final class ActiveMQServerChannelHandler extends ActiveMQChannelHandler implements ConnectionCreator {

      ActiveMQServerChannelHandler(final ChannelGroup group,
                                   final BufferHandler handler,
                                   final ServerConnectionLifeCycleListener listener,
                                   final Executor failureExecutor) {
         super(group, handler, listener, failureExecutor);
      }

      @Override
      public NettyServerConnection createConnection(final ChannelHandlerContext ctx,
                                                    String protocol,
                                                    boolean httpEnabled) throws Exception {
         if (connectionsAllowed == -1 || connections.size() < connectionsAllowed) {
            super.channelActive(ctx);
            Listener connectionListener = new Listener();

            NettyServerConnection nc = new NettyServerConnection(configuration, ctx.channel(), connectionListener, !httpEnabled && batchDelay > 0, directDeliver);

            connectionListener.connectionCreated(NettyAcceptor.this, nc, protocolHandler.getProtocol(protocol));

            SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
            if (sslHandler != null) {
               sslHandler.handshakeFuture().addListener(new GenericFutureListener<io.netty.util.concurrent.Future<Channel>>() {
                  @Override
                  public void operationComplete(final io.netty.util.concurrent.Future<Channel> future) throws Exception {
                     if (future.isSuccess()) {
                        active = true;
                     } else {
                        future.getNow().close();
                     }
                  }
               });
            } else {
               active = true;
            }
            return nc;
         } else {
            ActiveMQServerLogger.LOGGER.connectionLimitReached(connectionsAllowed, ctx.channel().remoteAddress().toString());
            ctx.channel().close();
            return null;
         }
      }
   }

   private class Listener implements ServerConnectionLifeCycleListener {

      @Override
      public void connectionCreated(final ActiveMQComponent component,
                                    final Connection connection,
                                    final ProtocolManager protocol) {
         if (connections.putIfAbsent(connection.getID(), (NettyServerConnection) connection) != null) {
            throw ActiveMQMessageBundle.BUNDLE.connectionExists(connection.getID());
         }

         listener.connectionCreated(component, connection, protocol);
      }

      @Override
      public void connectionDestroyed(final Object connectionID) {
         if (connections.remove(connectionID) != null) {
            listener.connectionDestroyed(connectionID);
         }
      }

      @Override
      public void connectionException(final Object connectionID, final ActiveMQException me) {
         // Execute on different thread to avoid deadlocks
         new Thread() {
            @Override
            public void run() {
               listener.connectionException(connectionID, me);
            }
         }.start();

      }

      @Override
      public void connectionReadyForWrites(final Object connectionID, boolean ready) {
         NettyServerConnection conn = connections.get(connectionID);

         if (conn != null) {
            conn.fireReady(ready);
         }

         listener.connectionReadyForWrites(connectionID, ready);
      }
   }

   private class BatchFlusher implements Runnable {

      private boolean cancelled;

      @Override
      public synchronized void run() {
         if (!cancelled) {
            for (Connection connection : connections.values()) {
               connection.checkFlushBatchBuffer();
            }
         }
      }

      public synchronized void cancel() {
         cancelled = true;
      }
   }

   /**
    * Deal with SSL handshake exceptions which otherwise would not be handled and would result in a lengthy stack-trace
    * in the log.
    */
   private class SslHandshakeExceptionHandler implements ChannelHandler {

      @Override
      public void handlerAdded(ChannelHandlerContext ctx) throws Exception {

      }

      @Override
      public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {

      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
         if (cause.getMessage() != null && cause.getMessage().startsWith(SSLHandshakeException.class.getName())) {
            Throwable rootCause = getRootCause(cause);
            String errorMessage = rootCause.getClass().getName() + ": " + rootCause.getMessage();

            ActiveMQServerLogger.LOGGER.sslHandshakeFailed(ctx.channel().remoteAddress().toString(), errorMessage);

            if (ActiveMQServerLogger.LOGGER.isDebugEnabled()) {
               ActiveMQServerLogger.LOGGER.debug("SSL handshake failed", cause);
            }
         }
      }

      private Throwable getRootCause(Throwable throwable) {
         List<Throwable> list = new ArrayList<>();
         while (throwable != null && list.contains(throwable) == false) {
            list.add(throwable);
            throwable = throwable.getCause();
         }
         return (list.size() < 2 ? throwable : list.get(list.size() - 1));
      }
   }
}
