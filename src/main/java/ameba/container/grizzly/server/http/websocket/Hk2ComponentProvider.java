package ameba.container.grizzly.server.http.websocket;

import ameba.container.Container;
import ameba.container.grizzly.server.GrizzlyContainer;
import org.glassfish.tyrus.core.ComponentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;

/**
 * @author icode
 */
public class Hk2ComponentProvider extends ComponentProvider {
    private static final Logger logger = LoggerFactory.getLogger(Hk2ComponentProvider.class);

    private Container container;

    public Hk2ComponentProvider() {
        container = GrizzlyContainer.currentThreadContainer.get();
    }

    @Override
    public boolean isApplicable(Class<?> c) {
        Annotation[] annotations = c.getAnnotations();

        for (Annotation annotation : annotations) {
            String annotationClassName = annotation.annotationType().getCanonicalName();
            if (annotationClassName.equals("javax.ejb.Singleton") ||
                    annotationClassName.equals("javax.ejb.Stateful") ||
                    annotationClassName.equals("javax.ejb.Stateless")) {
                return false;
            }
        }
        return true;
    }

    @Override
    public <T> Object create(Class<T> c) {
        return container.getServiceLocator().createAndInitialize(c);
    }

    @Override
    public boolean destroy(Object o) {
        try {
            container.getServiceLocator().preDestroy(o);
            return true;
        } catch (Exception e) {
            logger.debug(e.getMessage(), e);
            return false;
        }
    }
}
