package ameba.container.grizzly.server.http.websocket;

import ameba.Ameba;
import com.google.common.collect.Sets;
import org.glassfish.tyrus.core.ComponentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * @author icode
 */
public class Hk2ComponentProvider extends ComponentProvider {
    private static final Logger logger = LoggerFactory.getLogger(Hk2ComponentProvider.class);
    private static final Set<Object> noneManageObject = Sets.newConcurrentHashSet();

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

        T t = Ameba.getServiceLocator().getService(c);
        if (t == null) {
            t = Ameba.getServiceLocator().createAndInitialize(c);
            noneManageObject.add(t);
        }
        return t;
    }

    @Override
    public boolean destroy(Object o) {
        if (o != null && noneManageObject.remove(o)) {
            try {
                Ameba.getServiceLocator().preDestroy(o);
                return true;
            } catch (Exception e) {
                logger.debug(e.getMessage(), e);
                return false;
            }
        }
        return false;
    }
}
