package ameba.container.grizzly.server;

import ameba.Ameba;
import ameba.container.Container;
import ameba.container.grizzly.server.http.GrizzlyHttpContainer;
import ameba.container.grizzly.server.http.GrizzlyServerUtil;
import ameba.container.grizzly.server.http.websocket.WebSocketServerContainer;
import ameba.container.server.Connector;
import ameba.core.Application;
import ameba.exception.AmebaException;
import ameba.mvc.assets.AssetsFeature;
import ameba.util.ClassUtils;
import ameba.websocket.WebSocketException;
import ameba.websocket.WebSocketFeature;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.*;
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
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author icode
 */
public class GrizzlyContainer extends Container {

    public static final String WEB_SOCKET_CONTEXT_PATH = "websocket.contextPath";

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

    private static final String TYPE_NAME = "Grizzly";

    private HttpServer httpServer;

    private GrizzlyHttpContainer container;

    private WebSocketContainerProvider webSocketServerContainerProvider;

    private WebSocketServerContainer webSocketServerContainer;

    private List<Connector> connectors;

    public GrizzlyContainer(Application app) {
        super(app);
    }

    public static final ThreadLocal<Container> currentThreadContainer = new ThreadLocal<Container>();

    @Override
    public ServiceLocator getServiceLocator() {
        return container.getApplicationHandler().getServiceLocator();
    }

    @Override
    protected void prepare() {
        currentThreadContainer.set(this);
    }

    private void buildWebSocketContainer() {
        webSocketServerContainer = new WebSocketServerContainer(getApplication().getProperties());
    }

    @Override
    protected void configureHttpServer() {
        final Map<String, Object> properties = getApplication().getProperties();
        connectors = Connector.createDefaultConnectors(properties);
        List<NetworkListener> listeners = GrizzlyServerUtil.createListeners(connectors, GrizzlyServerUtil.createCompressionConfig(properties));

        boolean webSocketEnabled = !"false".equals(properties.get(WebSocketFeature.WEB_SOCKET_ENABLED_CONF));
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

        for (NetworkListener listener : listeners) {

            if (transportBuilder != null) {
                listener.setTransport(transportBuilder.build());
            }

            httpServer.addListener(listener);
        }
        final ServerConfiguration config = httpServer.getServerConfiguration();

        config.setPassTraceRequest(true);

        config.setHttpServerName(getApplication().getApplicationName());
        String version = getApplication().getApplicationVersion().toString();
        config.setHttpServerVersion(config.getHttpServerName().equals(Application.DEFAULT_APP_NAME) ? Ameba.getVersion() : version);
        config.setName("Ameba-HttpServer-" + getApplication().getApplicationName().toUpperCase());

    }

    @Override
    protected void configureHttpContainer() {
        container = ContainerFactory.createContainer(GrizzlyHttpContainer.class, getApplication().getConfig());
        ServerConfiguration serverConfiguration = httpServer.getServerConfiguration();
        serverConfiguration.addHttpHandler(container);

        String charset = StringUtils.defaultIfBlank((String) getApplication().getProperty("app.encoding"), "utf-8");
        serverConfiguration.setSendFileEnabled(true);
        if (!getApplication().isRegistered(AssetsFeature.class)) {
            Map<String, String[]> assetMap = AssetsFeature.getAssetMap(getApplication().getConfig());
            Set<String> mapKey = assetMap.keySet();
            for (String key : mapKey) {
                CLStaticHttpHandler httpHandler = new CLStaticHttpHandler(Application.class.getClassLoader(),
                        assetMap.get(key)) {
                    @Override
                    protected void onMissingResource(Request request, Response response) throws Exception {
                        container.service(request, response);
                    }
                };
                httpHandler.setRequestURIEncoding(charset);
                httpHandler.setFileCacheEnabled(getApplication().getMode().isProd());
                serverConfiguration.addHttpHandler(httpHandler, key.startsWith("/") ? key : "/" + key);
            }
        }

        serverConfiguration.setDefaultQueryEncoding(Charset.forName(charset));
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
    protected void doReload(ResourceConfig resourceConfig) {
        WebSocketServerContainer old = webSocketServerContainer;
        buildWebSocketContainer();
        container.reload(resourceConfig);
        try {
            webSocketServerContainer.start(old.getContextPath(), old.getPort());
        } catch (IOException e) {
            throw new WebSocketException("reload web socket endpoint error", e);
        } catch (DeploymentException e) {
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
    public void shutdown() throws ExecutionException, InterruptedException {
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
