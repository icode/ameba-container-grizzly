package ameba.container.grizzly.server;

import ameba.Application;
import ameba.container.Container;
import ameba.exceptions.AmebaException;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.ServerConfiguration;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpContainer;
import org.glassfish.jersey.server.ContainerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * @author icode
 */
public class GrizzlyContainer extends Container {

    private HttpServer httpServer;

    private GrizzlyHttpContainer containerProvider;

    public GrizzlyContainer(Application app) {
        super(app);

        httpServer = GrizzlyServerFactory.createHttpServer(app);
        containerProvider = ContainerFactory.createContainer(GrizzlyHttpContainer.class, application);
        ServerConfiguration serverConfiguration = httpServer.getServerConfiguration();
        serverConfiguration.addHttpHandler(containerProvider);
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
