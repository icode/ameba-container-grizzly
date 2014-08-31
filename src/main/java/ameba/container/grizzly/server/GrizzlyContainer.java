package ameba.container.grizzly.server;

import ameba.Application;
import ameba.container.Container;
import ameba.exceptions.AmebaException;
import ameba.mvc.assets.AssetsFeature;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.grizzly.http.server.CLStaticHttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpContainer;
import org.glassfish.jersey.server.ContainerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * @author icode
 */
public class GrizzlyContainer extends Container {

    private HttpServer httpServer;

    private GrizzlyHttpContainer containerProvider;

    public GrizzlyContainer(Application app) {
        super(app);

        containerProvider = ContainerFactory.createContainer(GrizzlyHttpContainer.class, app);

        httpServer = GrizzlyServerFactory.createHttpServer(
                containerProvider,
                app.getProperties(),
                app.getConnectors(),
                app.isJmxEnabled(),
                false);

        ServerConfiguration serverConfiguration = httpServer.getServerConfiguration();
        serverConfiguration.setHttpServerName(app.getApplicationName());
        serverConfiguration.setHttpServerVersion(app.getApplicationVersion());
        serverConfiguration.setName("Ameba-HttpServer-" + app.getApplicationName());

        String charset = StringUtils.defaultIfBlank((String) app.getProperty("app.encoding"), "utf-8");
        serverConfiguration.setSendFileEnabled(true);
        if (!app.isRegistered(AssetsFeature.class)) {
            Map<String, String[]> assetMap = AssetsFeature.getAssetMap(app);
            Set<String> mapKey = assetMap.keySet();
            for (String key : mapKey) {
                CLStaticHttpHandler httpHandler = new CLStaticHttpHandler(ameba.Application.class.getClassLoader(), key + "/");
                httpHandler.setRequestURIEncoding(charset);
                httpHandler.setFileCacheEnabled(app.getMode().isProd());
                serverConfiguration.addHttpHandler(httpHandler,
                        assetMap.get(key));
            }
        }

        httpServer.getServerConfiguration().setDefaultQueryEncoding(Charset.forName(charset));
    }

    @Override
    public ServiceLocator getServiceLocator() {
        return containerProvider.getApplicationHandler().getServiceLocator();
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
