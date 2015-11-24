package ameba.container.grizzly.server;

import ameba.Ameba;
import ameba.container.Container;
import ameba.container.grizzly.server.http.GrizzlyHttpContainer;
import ameba.container.grizzly.server.http.GrizzlyServerUtil;
import ameba.container.grizzly.server.http.websocket.WebSocketServerContainer;
import ameba.container.server.Connector;
import ameba.core.Application;
import ameba.exception.AmebaException;
import ameba.i18n.Messages;
import ameba.util.ClassUtils;
import ameba.websocket.WebSocketAddon;
import ameba.websocket.WebSocketException;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.server.ContainerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.tyrus.core.Utils;

import javax.websocket.DeploymentException;
import javax.websocket.server.ServerContainer;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author icode
 */
public class GrizzlyContainer extends Container {

    public static final String WEB_SOCKET_CONTEXT_PATH = "websocket.contextPath";

    /**
     * Server-side property to set custom worker {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}.
     * <br>
     * Value is expected to be instance of {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}, can be {@code null} (it won't be used).
     */
    public static final String WORKER_THREAD_POOL_CONFIG = "container.server.workerThreadPoolConfig";
    public static final String WORKER_THREAD_POOL_CORE_SIZE = "container.server.worker.coreSize";
    public static final String WORKER_THREAD_POOL_MAX_SIZE = "container.server.worker.maxSize";

    /**
     * Server-side property to set custom selector {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}.
     * <br>
     * Value is expected to be instance of {@link org.glassfish.grizzly.threadpool.ThreadPoolConfig}, can be {@code null} (it won't be used).
     */
    public static final String SELECTOR_THREAD_POOL_CONFIG = "container.server.selectorThreadPoolConfig";
    public static final String SELECTOR_THREAD_POOL_SIZE = "container.server.selector.size";

    private static final String TYPE_NAME = "Grizzly";

    private HttpServer httpServer;

    private GrizzlyHttpContainer container;

    private WebSocketContainerProvider webSocketServerContainerProvider;

    private WebSocketServerContainer webSocketServerContainer;

    private List<Connector> connectors;
    private boolean webSocketEnabled;

    public GrizzlyContainer(Application app) {
        super(app);
    }

    @Override
    public ServiceLocator getServiceLocator() {
        return container.getApplicationHandler().getServiceLocator();
    }


    private void buildWebSocketContainer() {
        webSocketServerContainer = new WebSocketServerContainer(getApplication().getProperties());
    }

    @Override
    protected void configureHttpServer() {
        final Map<String, Object> properties = getApplication().getProperties();
        connectors = Connector.createDefaultConnectors(properties);
        if (connectors.size() == 0) {
            logger.warn(Messages.get("info.connector.none"));
            connectors.add(Connector.createDefault(Maps.<String, String>newHashMap()));
        }
        List<NetworkListener> listeners = GrizzlyServerUtil.createListeners(connectors,
                GrizzlyServerUtil.createCompressionConfig("http", properties));

        webSocketEnabled = !"false".equals(properties.get(WebSocketAddon.WEB_SOCKET_ENABLED_CONF));
        final String contextPath = StringUtils.defaultIfBlank((String) properties.get(WEB_SOCKET_CONTEXT_PATH), "/");
        if (webSocketEnabled) {
            buildWebSocketContainer();
            GrizzlyServerUtil.bindWebSocket(contextPath, getWebSocketContainerProvider(), listeners);
        }

        httpServer = new HttpServer() {
            @Override
            public synchronized void start() throws IOException {
                if (webSocketServerContainer != null)
                    try {
                        webSocketServerContainer.start(contextPath, -1);
                    } catch (DeploymentException e) {
                        logger.error("启动websocket容器失败", e);
                    }
                super.start();
            }

            @Override
            public synchronized GrizzlyFuture<HttpServer> shutdown(long gracePeriod, TimeUnit timeUnit) {
                if (webSocketServerContainer != null)
                    webSocketServerContainer.stop();
                return super.shutdown(gracePeriod, timeUnit);
            }

            @Override
            public synchronized void shutdownNow() {
                if (webSocketServerContainer != null)
                    webSocketServerContainer.stop();
                super.shutdownNow();
            }
        };

        httpServer.getServerConfiguration().setJmxEnabled(getApplication().isJmxEnabled());

        ThreadPoolConfig workerThreadPoolConfig = null;

        String workerThreadPoolConfigClass = Utils.getProperty(properties, WORKER_THREAD_POOL_CONFIG, String.class);
        if (StringUtils.isNotBlank(workerThreadPoolConfigClass)) {
            workerThreadPoolConfig = ClassUtils.newInstance(workerThreadPoolConfigClass);
        }

        ThreadPoolConfig selectorThreadPoolConfig = null;

        String selectorThreadPoolConfigClass = Utils.getProperty(properties, SELECTOR_THREAD_POOL_CONFIG, String.class);
        if (StringUtils.isNotBlank(selectorThreadPoolConfigClass)) {
            selectorThreadPoolConfig = ClassUtils.newInstance(selectorThreadPoolConfigClass);
        }


        TCPNIOTransportBuilder transportBuilder = null;

        if (workerThreadPoolConfig != null || selectorThreadPoolConfig != null) {
            transportBuilder = TCPNIOTransportBuilder.newInstance();
            if (workerThreadPoolConfig != null) {
                transportBuilder.setWorkerThreadPoolConfig(workerThreadPoolConfig);
            }
            if (selectorThreadPoolConfig != null) {
                transportBuilder.setSelectorThreadPoolConfig(selectorThreadPoolConfig);
            }
        }

        Integer selectorSize = Utils.getProperty(properties, SELECTOR_THREAD_POOL_SIZE, Integer.class);
        Integer workerCoreSize = Utils.getProperty(properties, WORKER_THREAD_POOL_CORE_SIZE, Integer.class);
        Integer workerMaxSize = Utils.getProperty(properties, WORKER_THREAD_POOL_MAX_SIZE, Integer.class);

        for (NetworkListener listener : listeners) {

            if (transportBuilder != null) {
                listener.setTransport(transportBuilder.build());
            }

            if (workerThreadPoolConfig == null) {
                TCPNIOTransport transport = listener.getTransport();
                workerThreadPoolConfig = transport.getWorkerThreadPoolConfig();
                boolean change = false;
                if (workerCoreSize != null && workerCoreSize > 0) {
                    workerThreadPoolConfig.setCorePoolSize(workerCoreSize);
                    change = true;
                }
                if (workerMaxSize != null && workerMaxSize > 0) {
                    workerThreadPoolConfig.setMaxPoolSize(workerMaxSize);
                    change = true;
                }
                if (change) {
                    transport.setWorkerThreadPoolConfig(workerThreadPoolConfig);
                }
            }

            if (selectorThreadPoolConfig == null && selectorSize != null && selectorSize > 0) {
                listener.getTransport().setSelectorRunnersCount(selectorSize);
            }

            httpServer.addListener(listener);
        }
        final ServerConfiguration config = httpServer.getServerConfiguration();

        config.setPassTraceRequest(true);

        config.setHttpServerName(getApplication().getApplicationName());
        String version = getApplication().getApplicationVersion().toString();
        config.setHttpServerVersion(
                config.getHttpServerName().equals(Application.DEFAULT_APP_NAME) ? Ameba.getVersion() : version);
        config.setName("Ameba-HttpServer-" + getApplication().getApplicationName());

    }

    @Override
    protected void configureHttpContainer() {
        container = ContainerFactory.createContainer(GrizzlyHttpContainer.class, getApplication().getConfig());
        ServerConfiguration serverConfiguration = httpServer.getServerConfiguration();

        String charset = StringUtils.defaultIfBlank((String) getApplication().getProperty("app.encoding"), "utf-8");
        serverConfiguration.setSendFileEnabled(true);
        serverConfiguration.setDefaultQueryEncoding(Charset.forName(charset));

        GrizzlyHttpContainer httpHandler = container;
        httpHandler.setRequestURIEncoding(charset);
        serverConfiguration.addHttpHandler(httpHandler);
    }

    @Override
    public ServerContainer getWebSocketContainer() {
        return webSocketServerContainer;
    }

    @Override
    protected void configureWebSocketContainerProvider() {
        webSocketServerContainerProvider = new WebSocketContainerProvider() {
            @Override
            public void dispose(ServerContainer serverContainer) {
                if (serverContainer instanceof org.glassfish.tyrus.spi.ServerContainer)
                    ((org.glassfish.tyrus.spi.ServerContainer) serverContainer).stop();
            }
        };
    }

    @Override
    protected WebSocketContainerProvider getWebSocketContainerProvider() {
        return webSocketServerContainerProvider;
    }

    @Override
    protected void doReload(ResourceConfig odlConfig) {
        WebSocketServerContainer old = null;
        if (webSocketEnabled) {
            old = webSocketServerContainer;
            buildWebSocketContainer();
        }
        container.reload(getApplication().getConfig());
        if (webSocketServerContainer != null && old != null)
            try {
                webSocketServerContainer.start(old.getContextPath(), old.getPort());
            } catch (IOException | DeploymentException e) {
                throw new WebSocketException("reload web socket endpoint error", e);
            }
    }

    @Override
    public void doStart() {
        try {
            httpServer.start();
        } catch (IOException e) {
            throw new AmebaException("端口无法使用", e);
        }
    }

    @Override
    public void doShutdown() throws Exception {
        httpServer.shutdown().get();
    }

    @Override
    public List<Connector> getConnectors() {
        return connectors;
    }

    @Override
    public String getType() {
        return TYPE_NAME;
    }
}
