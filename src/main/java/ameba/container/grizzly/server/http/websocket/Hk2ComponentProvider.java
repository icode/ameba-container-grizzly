package ameba.container.grizzly.server.http.websocket;

import com.google.common.collect.Sets;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.ServiceLocatorFactory;
import org.glassfish.tyrus.core.ComponentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * @author icode
 */
public class Hk2ComponentProvider extends ComponentProvider {
    private static final Logger logger = LoggerFactory.getLogger(Hk2ComponentProvider.class);
    private static final Set<Object> noneManageObject = Sets.newConcurrentHashSet();
    private ServiceLocator _serviceLocator;

    public Hk2ComponentProvider() {
        _serviceLocator = ServiceLocatorFactory.getInstance().find(null);
    }

    @Override
    public boolean isApplicable(Class<?> c) {
        return true;
    }

    @Override
    public <T> Object create(Class<T> c) {
        T t = _serviceLocator.getService(c);
        if (t == null) {
            t = _serviceLocator.createAndInitialize(c);
            noneManageObject.add(t);
        }
        return t;
    }

    @Override
    public boolean destroy(Object o) {
        if (o != null && noneManageObject.remove(o)) {
            try {
                _serviceLocator.preDestroy(o);
                return true;
            } catch (Exception e) {
                logger.debug("WebSocket Object destroy error", e);
                return false;
            }
        }
        return false;
    }
}
