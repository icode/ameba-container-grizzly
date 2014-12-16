package ameba.container.grizzly.server;

import ameba.container.Container;
import ameba.container.grizzly.server.http.GrizzlyHttpContainer;
import ameba.container.grizzly.server.http.GrizzlyServerUtil;
import ameba.container.server.Connector;
import ameba.core.Application;
import ameba.exceptions.AmebaException;
import ameba.mvc.assets.AssetsFeature;
import ameba.util.ClassUtils;
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

    private static final String TYPE_NAME = "Grizzly";

    private HttpServer httpServer;

    private GrizzlyHttpContainer container;

    private WebSocketContainerProvider webSocketContainerProvider;

    private ServerContainer serverContainer;

    private List<Connector> connectors;

    public GrizzlyContainer(Application app) {
        super(app);
    }


    @Override
    public ServiceLocator getServiceLocator() {
        return container.getApplicationHandler().getServiceLocator();
    }

    @Override
    protected void configureHttpServer() {
        final Map<String, Object> properties = getApplication().getProperties();
        connectors = Connector.createDefaultConnectors(properties);
        List<NetworkListener> listeners = GrizzlyServerUtil.createListeners(connectors, GrizzlyServerUtil.createCompressionConfig(properties));

        boolean webSocketEnabled = !"false".equals(properties.get(WebSocketFeature.WEB_SOCKET_ENABLED_CONF));
        final org.glassfish.tyrus.spi.ServerContainer webSocketContainer = webSocketEnabled ? GrizzlyServerUtil.bindWebSocket(properties, listeners) : null;
        serverContainer = webSocketContainer;

        httpServer = new HttpServer() {
            @Override
            public synchronized void start() throws IOException {
                if (webSocketContainer != null)
                    try {
                        webSocketContainer.start(StringUtils.defaultIfBlank((String) properties.get(WEB_SOCKET_CONTEXT_PATH), "/"), -1);
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

        httpServer.getServerConfiguration().setJmxEnabled(getApplication().isJmxEnabled());

        ThreadPoolConfig workerThreadPoolConfig = null;

        String workerThreadPoolConfigClass = Utils.getProperty(properties, GrizzlyServerUtil.WORKER_THREAD_POOL_CONFIG, String.class);
        if (StringUtils.isNotBlank(workerThreadPoolConfigClass)) {
            workerThreadPoolConfig = (ThreadPoolConfig) ClassUtils.newInstance(workerThreadPoolConfigClass);
        }

        ThreadPoolConfig selectorThreadPoolConfig = null;

        String selectorThreadPoolConfigClass = Utils.getProperty(properties, GrizzlyServerUtil.SELECTOR_THREAD_POOL_CONFIG, String.class);
        if (StringUtils.isNotBlank(selectorThreadPoolConfigClass)) {
            selectorThreadPoolConfig = (ThreadPoolConfig) ClassUtils.newInstance(selectorThreadPoolConfigClass);
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
        config.setHttpServerVersion(getApplication().getApplicationVersion().toString());
        config.setName("Ameba-HttpServer-" + getApplication().getApplicationName().toUpperCase());

    }

    @Override
    protected void configureHttpContainer() {
        container = ContainerFactory.createContainer(GrizzlyHttpContainer.class, getApplication());
        ServerConfiguration serverConfiguration = httpServer.getServerConfiguration();
        serverConfiguration.addHttpHandler(container);

        String charset = StringUtils.defaultIfBlank((String) getApplication().getProperty("app.encoding"), "utf-8");
        serverConfiguration.setSendFileEnabled(true);
        if (!getApplication().isRegistered(AssetsFeature.class)) {
            Map<String, String[]> assetMap = AssetsFeature.getAssetMap(getApplication());
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
        return serverContainer;
    }

    @Override
    protected void configureWebSocketContainerProvider() {
        webSocketContainerProvider = new WebSocketContainerProvider() {
            @Override
            public void dispose(ServerContainer serverContainer) {
                if (serverContainer instanceof org.glassfish.tyrus.spi.ServerContainer)
                    ((org.glassfish.tyrus.spi.ServerContainer) serverContainer).stop();
            }
        };
    }

    @Override
    protected WebSocketContainerProvider getWebSocketContainerProvider() {
        return webSocketContainerProvider;
    }

    @Override
    protected void doReload(ResourceConfig resourceConfig) {
        container.reload(resourceConfig);
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
