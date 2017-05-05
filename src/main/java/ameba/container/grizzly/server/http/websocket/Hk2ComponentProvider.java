package ameba.container.grizzly.server.http.websocket;

import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.Injections;
import org.glassfish.tyrus.core.ComponentProvider;

/**
 * @author icode
 */
public class Hk2ComponentProvider extends ComponentProvider {
    static InjectionManager manager;

    @Override
    public boolean isApplicable(Class<?> c) {
        return true;
    }

    @Override
    public <T> Object create(Class<T> c) {
        return Injections.getOrCreate(manager, c);
    }

    @Override
    public boolean destroy(Object o) {
        return false;
    }
}
