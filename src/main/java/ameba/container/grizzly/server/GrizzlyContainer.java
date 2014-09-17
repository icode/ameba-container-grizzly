package ameba.container.grizzly.server;

import ameba.Application;
import ameba.container.Container;
import ameba.exceptions.AmebaException;
import ameba.mvc.assets.AssetsFeature;
import ameba.server.Connector;
import ameba.util.ClassUtils;
import ameba.websocket.WebSocketFeature;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.CLStaticHttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpContainer;
import org.glassfish.jersey.server.ContainerFactory;
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

    private HttpServer httpServer;

    private GrizzlyHttpContainer container;

    private WebSocketContainerProvider webSocketContainerProvider;

    private ServerContainer serverContainer;

    public GrizzlyContainer(Application app) {
        super(app);
    }

    @Override
    public ServiceLocator getServiceLocator() {
        return container.getApplicationHandler().getServiceLocator();
    }

    @Override
    protected void configureHttpServer() {
        final Map<String, Object> properties = application.getProperties();
        final List<Connector> connectors = application.getConnectors();
        List<NetworkListener> listeners = GrizzlyServerUtil.createListeners(connectors, GrizzlyServerUtil.createCompressionConfig(properties));

        boolean webSocketEnabled = !"false".equals(properties.get(WebSocketFeature.WEB_SOCKET_ENABLED_CONF));
        final org.glassfish.tyrus.spi.ServerContainer webSocketContainer = webSocketEnabled ? GrizzlyServerUtil.bindWebSocket(properties, listeners) : null;
        serverContainer = webSocketContainer;

        httpServer = new HttpServer() {
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

        httpServer.getServerConfiguration().setJmxEnabled(application.isJmxEnabled());

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

        config.setHttpServerName(application.getApplicationName());
        config.setHttpServerVersion(application.getApplicationVersion().toString());
        config.setName("Ameba-HttpServer-" + application.getApplicationName());

        String charset = StringUtils.defaultIfBlank((String) application.getProperty("app.encoding"), "utf-8");
        config.setSendFileEnabled(true);
        if (!application.isRegistered(AssetsFeature.class)) {
            Map<String, String[]> assetMap = AssetsFeature.getAssetMap(application);
            Set<String> mapKey = assetMap.keySet();
            for (String key : mapKey) {
                CLStaticHttpHandler httpHandler = new CLStaticHttpHandler(Application.class.getClassLoader(), key + "/");
                httpHandler.setRequestURIEncoding(charset);
                httpHandler.setFileCacheEnabled(application.getMode().isProd());
                config.addHttpHandler(httpHandler,
                        assetMap.get(key));
            }
        }

        config.setDefaultQueryEncoding(Charset.forName(charset));
    }

    @Override
    protected void configureHttpContainer() {
        container = ContainerFactory.createContainer(GrizzlyHttpContainer.class, application);
        ServerConfiguration serverConfiguration = httpServer.getServerConfiguration();
        serverConfiguration.addHttpHandler(container);
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
    public void start() {
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
}
