package ameba.container.grizzly.server;

import ameba.container.grizzly.server.websocket.WebSocketAddOn;
import ameba.container.server.Connector;
import ameba.util.ClassUtils;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.grizzly.http.CompressionConfig;
import org.glassfish.grizzly.http.ajp.AjpAddOn;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.spdy.SpdyAddOn;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.tyrus.core.DebugContext;
import org.glassfish.tyrus.core.TyrusWebSocketEngine;
import org.glassfish.tyrus.core.Utils;
import org.glassfish.tyrus.core.cluster.ClusterContext;
import org.glassfish.tyrus.core.monitoring.ApplicationEventListener;
import org.glassfish.tyrus.ext.monitoring.jmx.SessionAwareApplicationMonitor;
import org.glassfish.tyrus.server.TyrusServerContainer;
import org.glassfish.tyrus.spi.ServerContainer;
import org.glassfish.tyrus.spi.WebSocketEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.util.*;

/**
 * @author icode
 */
public class GrizzlyServerUtil {
    public static final Logger logger = LoggerFactory.getLogger(GrizzlyServerUtil.class);
    public static final String DEFAULT_NETWORK_LISTENER_NAME = "ameba";
    /**
     * Server-side property to set custom worker {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}.
     * <p/>
     * Value is expected to be instance of {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}, can be {@code null} (it won't be used).
     */
    public static final String WORKER_THREAD_POOL_CONFIG = "container.server.workerThreadPoolConfig";

    /**
     * Server-side property to set custom selector {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}.
     * <p/>
     * Value is expected to be instance of {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}, can be {@code null} (it won't be used).
     */
    public static final String SELECTOR_THREAD_POOL_CONFIG = "container.server.selectorThreadPoolConfig";
    /**
     * Maximum size of incoming buffer in bytes.
     * <p/>
     * The value must be {@link java.lang.Integer} or its primitive alternative.
     * <p/>
     * Default value is 4194315, which means that TyrusWebSocketEngine is by default
     * capable of processing messages up to 4 MB.
     */
    public static final String WEBSOCKET_INCOMING_BUFFER_SIZE = "websocket.incomingBufferSize";

    /**
     * Maximum number of open sessions per server application.
     * <p/>
     * The value must be positive {@link java.lang.Integer} or its primitive alternative. Negative values
     * and zero are ignored.
     * <p/>
     * The number of open sessions per application is not limited by default.
     */
    public static final String WEBSOCKET_MAX_SESSIONS_PER_APP = "websocket.maxSessionsPerApp";

    /**
     * Maximum number of open sessions per unique remote address.
     * <p/>
     * The value must be positive {@link java.lang.Integer} or its primitive alternative. Negative values
     * and zero are ignored.
     * <p/>
     * The number of open sessions per remote address is not limited by default.
     */
    public static final String WEBSOCKET_MAX_SESSIONS_PER_REMOTE_ADDR = "websocket.maxSessionsPerRemoteAddr";

    /**
     * Property used for configuring the type of tracing supported by the server.
     * <p/>
     * The value is expected to be string value of {@link org.glassfish.tyrus.core.DebugContext.TracingType}.
     * <p/>
     * The default value is {@link org.glassfish.tyrus.core.DebugContext.TracingType#OFF}.
     */
    public static final String WEBSOCKET_TRACING_TYPE = "websocket.tracingType";

    /**
     * Property used for configuring tracing threshold.
     * <p/>
     * The value is expected to be string value of {@link org.glassfish.tyrus.core.DebugContext.TracingThreshold}.
     * <p/>
     * The default value is {@link org.glassfish.tyrus.core.DebugContext.TracingThreshold#SUMMARY}.
     */
    public static final String WEBSOCKET_TRACING_THRESHOLD = "websocket.tracingThreshold";

    /**
     * Parallel broadcast support.
     * <p/>
     * {@link org.glassfish.tyrus.core.TyrusSession#broadcast(String)} and {@link org.glassfish.tyrus.core.TyrusSession#broadcast(java.nio.ByteBuffer)}
     * operations are by default executed in parallel. The parallel execution of broadcast can be disabled by setting
     * this server property to {@code false}.
     * <p/>
     * Expected value is {@code true} or {@code false} and the default value is {@code true}.
     *
     * @see org.glassfish.tyrus.core.TyrusSession#broadcast(String).
     * @see org.glassfish.tyrus.core.TyrusSession#broadcast(java.nio.ByteBuffer).
     */
    public static final String WEBSOCKET_PARALLEL_BROADCAST_ENABLED = "websocket.parallelBroadcastEnabled";


    /**
     * ClusterContext registration property.
     * <p/>
     * ClusterContext is registered to the Server container via properties passed to {@link org.glassfish.tyrus.spi.ServerContainerFactory#createServerContainer(java.util.Map)}.
     */
    public static final String WEBSOCKET_CLUSTER_CONTEXT = "websocket.cluster.ClusterContext";


    /**
     * A key used for registering a application event listener implementation.
     * <p/>
     * For monitoring in Grizzly server an instance should be passed to the server in server properties:
     * <pre>
     *     serverProperties.put(ApplicationEventListener.APPLICATION_EVENT_LISTENER, new MyApplicationEventListener());
     * </pre>
     * For use in servlet container the class name should be passed as a context parameter in web.xml:
     * <pre>{@code
     *     <context-param>
     *         <param-name>org.glassfish.tyrus.core.monitoring.ApplicationEventListener</param-name>
     *         <param-value>com.acme.MyApplicationEventListener</param-value>
     *     </context-param>}</pre>
     */
    public static final String WEBSOCKET_APPLICATION_EVENT_LISTENER = "websocket.monitoring.ApplicationEventListener";


    @SuppressWarnings("unchecked")
    public static List<NetworkListener> createListeners(List<Connector> connectors, CompressionConfig compression) {
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

    public static CompressionConfig createCompressionConfig(Map<String, Object> properties) {
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

    public static SSLEngineConfigurator createSslEngineConfigurator(Connector connector) {
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

    public static ServerContainer bindWebSocket(Map<String, Object> properties, final List<NetworkListener> listeners) {

        final Map<String, Object> localProperties;
        // defensive copy
        if (properties == null) {
            localProperties = Collections.emptyMap();
        } else {
            localProperties = new HashMap<String, Object>(properties);
        }

        final Integer incomingBufferSize = Utils.getProperty(localProperties, WEBSOCKET_INCOMING_BUFFER_SIZE, Integer.class);
        final Integer maxSessionsPerApp = Utils.getProperty(localProperties, WEBSOCKET_MAX_SESSIONS_PER_APP, Integer.class);
        final Integer maxSessionsPerRemoteAddr = Utils.getProperty(localProperties, WEBSOCKET_MAX_SESSIONS_PER_REMOTE_ADDR, Integer.class);
        final Boolean parallelBroadcastEnabled = Utils.getProperty(localProperties, WEBSOCKET_PARALLEL_BROADCAST_ENABLED, Boolean.class);

        ClusterContext clusterContext = null;
        String clusterContextClass = Utils.getProperty(localProperties, WEBSOCKET_CLUSTER_CONTEXT, String.class);
        if (StringUtils.isNotBlank(clusterContextClass)) {
            clusterContext = (ClusterContext) ClassUtils.newInstance(clusterContextClass);
        }

        ApplicationEventListener applicationEventListener = null;
        String applicationEventListenerClass = Utils.getProperty(localProperties, WEBSOCKET_APPLICATION_EVENT_LISTENER, String.class);
        if (StringUtils.isNotBlank(applicationEventListenerClass)) {
            applicationEventListener = (ApplicationEventListener) ClassUtils.newInstance(applicationEventListenerClass);
        } else if (Utils.getProperty(localProperties, "app.jmx.enabled", Boolean.class)) {
            applicationEventListener = new SessionAwareApplicationMonitor();
        }

        final DebugContext.TracingType tracingType = Utils.getProperty(localProperties, WEBSOCKET_TRACING_TYPE, DebugContext.TracingType.class, DebugContext.TracingType.OFF);
        final DebugContext.TracingThreshold tracingThreshold = Utils.getProperty(localProperties, WEBSOCKET_TRACING_THRESHOLD, DebugContext.TracingThreshold.class, DebugContext.TracingThreshold.TRACE);

        final ClusterContext finalClusterContext = clusterContext;
        final ApplicationEventListener finalApplicationEventListener = applicationEventListener;
        return new TyrusServerContainer((Set<Class<?>>) null) {

            private final WebSocketEngine engine = TyrusWebSocketEngine.builder(this)
                    .incomingBufferSize(incomingBufferSize)
                    .clusterContext(finalClusterContext)
                    .applicationEventListener(finalApplicationEventListener)
                    .maxSessionsPerApp(maxSessionsPerApp)
                    .maxSessionsPerRemoteAddr(maxSessionsPerRemoteAddr)
                    .parallelBroadcastEnabled(BooleanUtils.isTrue(parallelBroadcastEnabled))
                    .tracingType(tracingType)
                    .tracingThreshold(tracingThreshold)
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

                WebSocketAddOn addOn = new WebSocketAddOn(this, contextPath);

                for (NetworkListener listener : listeners) {
                    // idle timeout set to indefinite.
                    listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
                    listener.registerAddOn(addOn);
                }

                if (finalApplicationEventListener != null) {
                    finalApplicationEventListener.onApplicationInitialized(rootPath);
                }

                super.start(rootPath, port);
            }

            @Override
            public void stop() {
                super.stop();
                if (finalApplicationEventListener != null) {
                    finalApplicationEventListener.onApplicationDestroyed();
                }
            }
        };
    }
}
