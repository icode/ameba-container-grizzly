package ameba.container.grizzly.server;

import ameba.container.grizzly.server.websocket.WebSocketAddOn;
import ameba.server.Connector;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.CompressionConfig;
import org.glassfish.grizzly.http.ajp.AjpAddOn;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.spdy.SpdyAddOn;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.strategies.WorkerThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpContainer;
import org.glassfish.tyrus.core.TyrusWebSocketEngine;
import org.glassfish.tyrus.core.Utils;
import org.glassfish.tyrus.core.cluster.ClusterContext;
import org.glassfish.tyrus.core.monitoring.ApplicationEventListener;
import org.glassfish.tyrus.server.TyrusServerContainer;
import org.glassfish.tyrus.spi.ServerContainer;
import org.glassfish.tyrus.spi.WebSocketEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpointConfig;
import javax.ws.rs.ProcessingException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author icode
 */
public class GrizzlyServerFactory {
    public static final Logger logger = LoggerFactory.getLogger(GrizzlyServerFactory.class);
    public static final String DEFAULT_NETWORK_LISTENER_NAME = "ameba";
    /**
     * Server-side property to set custom worker {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}.
     * <p/>
     * Value is expected to be instance of {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}, can be {@code null} (it won't be used).
     */
    public static final String WORKER_THREAD_POOL_CONFIG = "app.container.server.workerThreadPoolConfig";

    /**
     * Server-side property to set custom selector {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}.
     * <p/>
     * Value is expected to be instance of {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}, can be {@code null} (it won't be used).
     */
    public static final String SELECTOR_THREAD_POOL_CONFIG = "app.container.server.selectorThreadPoolConfig";
    /**
     * Maximum size of incoming buffer in bytes.
     * <p/>
     * The value must be {@link java.lang.Integer} or its primitive alternative.
     * <p/>
     * Default value is 4194315, which means that TyrusWebSocketEngine is by default
     * capable of processing messages up to 4 MB.
     */
    public static final String INCOMING_BUFFER_SIZE = "app.websocket.incomingBufferSize";

    /**
     * Maximum number of open sessions per server application.
     * <p/>
     * The value must be positive {@link java.lang.Integer} or its primitive alternative. Negative values
     * and zero are ignored.
     * <p/>
     * The number of open sessions per application is not limited by default.
     */
    public static final String MAX_SESSIONS_PER_APP = "app.websocket.maxSessionsPerApp";

    /**
     * Maximum number of open sessions per unique remote address.
     * <p/>
     * The value must be positive {@link java.lang.Integer} or its primitive alternative. Negative values
     * and zero are ignored.
     * <p/>
     * The number of open sessions per remote address is not limited by default.
     */
    public static final String MAX_SESSIONS_PER_REMOTE_ADDR = "app.websocket.maxSessionsPerRemoteAddr";

    @SuppressWarnings("unchecked")
    private static List<NetworkListener> createListeners(List<Connector> connectors, CompressionConfig compression) {
        List<NetworkListener> listeners = Lists.newArrayList();

        for (Connector connector : connectors) {
            final String host = (connector.getHost() == null) ? NetworkListener.DEFAULT_NETWORK_HOST
                    : connector.getHost();
            final int port = (connector.getPort() == -1) ? 80 : connector.getPort();
            final NetworkListener listener = new NetworkListener(
                    StringUtils.defaultString(connector.getName(), DEFAULT_NETWORK_LISTENER_NAME),
                    host,
                    port);
            listener.setSecure(connector.isSecureEnabled());
            SSLEngineConfigurator sslEngineConfigurator = createSslEngineConfigurator(connector);
            if (sslEngineConfigurator != null) {
                listener.setSSLEngineConfig(sslEngineConfigurator);

                if (connector.isSecureEnabled() && !connector.isAjpEnabled()) {
                    SpdyAddOn spdyAddon = new SpdyAddOn();
                    listener.registerAddOn(spdyAddon);
                } else if (connector.isSecureEnabled()) {
                    logger.warn("AJP模式开启，不启动SPDY支持");
                }
            }

            if (connector.isAjpEnabled()) {
                AjpAddOn ajpAddon = new AjpAddOn();
                listener.registerAddOn(ajpAddon);
            }

            CompressionConfig compressionConfig = listener.getCompressionConfig();
            CompressionConfig compressionCfg = createCompressionConfig((Map) connector.getRawProperties());

            if (compressionCfg == null) {
                compressionCfg = compression;
            }

            if (compressionCfg != null) {
                compressionConfig.set(compressionCfg);
            }
            listeners.add(listener);
        }

        return listeners;
    }

    private static CompressionConfig createCompressionConfig(Map<String, Object> properties) {
        CompressionConfig compressionConfig = null;
        String modeStr = (String) properties.get("http.compression.mode");
        if (StringUtils.isNotBlank(modeStr) && ((modeStr = modeStr.toUpperCase()).equals("ON") || modeStr.equals("FORCE"))) {

            String minSizeStr = (String) properties.get("http.compression.minSize");
            String mimeTypesStr = (String) properties.get("http.compression.mimeTypes");
            String userAgentsStr = (String) properties.get("http.compression.ignore.userAgents");

            compressionConfig = new CompressionConfig();
            compressionConfig.setCompressionMode(CompressionConfig.CompressionMode.fromString(modeStr)); // the mode
            if (StringUtils.isNotBlank(minSizeStr))
                try {
                    compressionConfig.setCompressionMinSize(Integer.parseInt(minSizeStr)); // the min amount of bytes to compress
                } catch (Exception e) {
                    logger.error("parse http.compression.minSize error", e);
                }
            if (StringUtils.isNotBlank(mimeTypesStr))
                compressionConfig.setCompressableMimeTypes(mimeTypesStr.split(",")); // the mime types to compress
            if (StringUtils.isNotBlank(userAgentsStr))
                compressionConfig.setNoCompressionUserAgents(userAgentsStr.split(","));
        }
        return compressionConfig;
    }

    private static SSLEngineConfigurator createSslEngineConfigurator(Connector connector) {
        SSLEngineConfigurator sslEngineConfigurator = null;
        if (connector.isSslConfigReady()) {
            SSLContextConfigurator sslContextConfiguration = new SSLContextConfigurator();
            sslContextConfiguration.setKeyPass(connector.getSslKeyPassword());
            sslContextConfiguration.setSecurityProtocol(connector.getSslProtocol());

            sslContextConfiguration.setKeyStoreBytes(connector.getSslKeyStoreFile());
            sslContextConfiguration.setKeyStorePass(connector.getSslKeyStorePassword());
            sslContextConfiguration.setKeyStoreProvider(connector.getSslKeyStoreProvider());
            sslContextConfiguration.setKeyStoreType(connector.getSslKeyStoreType());
            sslContextConfiguration.setKeyManagerFactoryAlgorithm(connector.getSslKeyManagerFactoryAlgorithm());

            sslContextConfiguration.setTrustStoreBytes(connector.getSslTrustStoreFile());
            if (StringUtils.isNotBlank(connector.getSslTrustStorePassword()))
                sslContextConfiguration.setTrustStorePass(connector.getSslTrustStorePassword());
            sslContextConfiguration.setTrustStoreType(connector.getSslTrustStoreType());
            sslContextConfiguration.setTrustStoreProvider(connector.getSslTrustStoreProvider());
            sslContextConfiguration.setTrustManagerFactoryAlgorithm(connector.getSslTrustManagerFactoryAlgorithm());

            sslEngineConfigurator = new SSLEngineConfigurator(
                    sslContextConfiguration,
                    connector.isSslClientMode(),
                    connector.isSslNeedClientAuth(),
                    connector.isSslWantClientAuth());
        }
        return sslEngineConfigurator;
    }

    /**
     * Creates HttpServer instance.
     *
     * @param handler    {@link org.glassfish.grizzly.http.server.HttpHandler} instance.
     * @param connectors connectors
     *                   {@link org.glassfish.grizzly.spdy.SpdyAddOn}.
     * @param jmxEnabled {@link org.glassfish.grizzly.http.server.ServerConfiguration#setJmxEnabled(boolean)}.
     * @param start      if set to false, server will not get started, this allows end users to set
     *                   additional properties on the underlying listener.
     * @return newly created {@link HttpServer}.
     * @throws javax.ws.rs.ProcessingException
     * @see GrizzlyHttpContainer
     */
    @SuppressWarnings("unchecked")
    public static HttpServer createHttpServer(final HttpHandler handler,
                                              final Map<String, Object> properties,
                                              List<Connector> connectors,
                                              final boolean jmxEnabled,
                                              final boolean start)
            throws ProcessingException {

        List<NetworkListener> listeners = createListeners(connectors, createCompressionConfig(properties));

        final ServerContainer webSocketContainer = bindWebSocket(properties, listeners);

        final HttpServer server = new HttpServer() {
            @Override
            public synchronized void start() throws IOException {
                if (webSocketContainer != null)
                    try {
                        webSocketContainer.start("/", -1);
                    } catch (DeploymentException e) {
                        logger.error("启动websocket容器失败", e);
                    }
                super.start();
            }

            @Override
            public synchronized GrizzlyFuture<HttpServer> shutdown(long gracePeriod, TimeUnit timeUnit) {
                if (webSocketContainer != null)
                    webSocketContainer.stop();
                return super.shutdown(gracePeriod, timeUnit);
            }

            @Override
            public synchronized void shutdownNow() {
                if (webSocketContainer != null)
                    webSocketContainer.stop();
                super.shutdownNow();
            }
        };


        server.getServerConfiguration().setJmxEnabled(jmxEnabled);

        for (NetworkListener listener : listeners) {
            server.addListener(listener);
        }
        // Map the path to the processor.
        final ServerConfiguration config = server.getServerConfiguration();
        if (handler != null) {
            config.addHttpHandler(handler);
        }

        config.setPassTraceRequest(true);

        if (start) {
            try {
                // Start the server.
                server.start();
            } catch (IOException ex) {
                String msg = "无法启动HTTP服务";
                logger.error(msg, ex);
                throw new ProcessingException(msg, ex);
            }
        }

        return server;
    }

    private static ServerContainer bindWebSocket(Map<String, Object> properties, final List<NetworkListener> listeners) {

        final Map<String, Object> localProperties;
        // defensive copy
        if (properties == null) {
            localProperties = Collections.emptyMap();
        } else {
            localProperties = new HashMap<String, Object>(properties);
        }

        final Integer incomingBufferSize = Utils.getProperty(localProperties, INCOMING_BUFFER_SIZE, Integer.class);
        final ClusterContext clusterContext = Utils.getProperty(localProperties, ClusterContext.CLUSTER_CONTEXT, ClusterContext.class);
        final ApplicationEventListener applicationEventListener = Utils.getProperty(localProperties, ApplicationEventListener.APPLICATION_EVENT_LISTENER, ApplicationEventListener.class);
        final Integer maxSessionsPerApp = Utils.getProperty(localProperties, MAX_SESSIONS_PER_APP, Integer.class);
        final Integer maxSessionsPerRemoteAddr = Utils.getProperty(localProperties, MAX_SESSIONS_PER_REMOTE_ADDR, Integer.class);

        return new TyrusServerContainer((Set<Class<?>>) null) {

            private final WebSocketEngine engine = TyrusWebSocketEngine.builder(this)
                    .incomingBufferSize(incomingBufferSize)
                    .clusterContext(clusterContext)
                    .applicationEventListener(applicationEventListener)
                    .maxSessionsPerApp(maxSessionsPerApp)
                    .maxSessionsPerRemoteAddr(maxSessionsPerRemoteAddr)
                    .build();

            private String contextPath;

            @Override
            public void register(Class<?> endpointClass) throws DeploymentException {
                engine.register(endpointClass, contextPath);
            }

            @Override
            public void register(ServerEndpointConfig serverEndpointConfig) throws DeploymentException {
                engine.register(serverEndpointConfig, contextPath);
            }

            @Override
            public WebSocketEngine getWebSocketEngine() {
                return engine;
            }

            @Override
            public void start(final String rootPath, int port) throws IOException, DeploymentException {
                contextPath = rootPath;
                // server = HttpServer.createSimpleServer(rootPath, port);
                ThreadPoolConfig workerThreadPoolConfig = Utils.getProperty(localProperties, WORKER_THREAD_POOL_CONFIG, ThreadPoolConfig.class);
                ThreadPoolConfig selectorThreadPoolConfig = Utils.getProperty(localProperties, SELECTOR_THREAD_POOL_CONFIG, ThreadPoolConfig.class);

                TCPNIOTransportBuilder transportBuilder = null;

                // TYRUS-287: configurable server thread pools
                if (workerThreadPoolConfig != null || selectorThreadPoolConfig != null) {
                    transportBuilder = TCPNIOTransportBuilder.newInstance();
                    if (workerThreadPoolConfig != null) {
                        transportBuilder.setWorkerThreadPoolConfig(workerThreadPoolConfig);
                    }
                    if (selectorThreadPoolConfig != null) {
                        transportBuilder.setSelectorThreadPoolConfig(selectorThreadPoolConfig);
                    }
                    transportBuilder.setIOStrategy(WorkerThreadIOStrategy.getInstance());
                }

                WebSocketAddOn addOn = new WebSocketAddOn(this);

                for (NetworkListener listener : listeners) {

                    if (transportBuilder != null) {
                        listener.setTransport(transportBuilder.build());
                    } else {
                        listener.getTransport().setIOStrategy(WorkerThreadIOStrategy.getInstance());
                    }

                    // idle timeout set to indefinite.
                    listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
                    listener.registerAddOn(addOn);
                }

                if (applicationEventListener != null) {
                    applicationEventListener.onApplicationInitialized(rootPath);
                }

                super.start(rootPath, port);
            }

            @Override
            public void stop() {
                super.stop();
                if (applicationEventListener != null) {
                    applicationEventListener.onApplicationDestroyed();
                }
            }
        };
    }
}
