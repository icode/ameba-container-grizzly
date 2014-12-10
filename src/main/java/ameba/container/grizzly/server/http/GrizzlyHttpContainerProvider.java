package ameba.container.grizzly.server.http;

import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.jersey.server.spi.ContainerProvider;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.Application;

/**
 * @author icode
 * @since 14-12-11
 */
public class GrizzlyHttpContainerProvider implements ContainerProvider {

    @Override
    public <T> T createContainer(Class<T> type, Application application) throws ProcessingException {
        if (HttpHandler.class == type || GrizzlyHttpContainer.class == type) {
            return type.cast(new GrizzlyHttpContainer(application));
        }

        return null;
    }
}