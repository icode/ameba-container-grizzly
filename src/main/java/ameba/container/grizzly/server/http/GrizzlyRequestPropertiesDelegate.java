package ameba.container.grizzly.server.http;

import org.glassfish.grizzly.http.server.Request;
import org.glassfish.jersey.internal.PropertiesDelegate;

import java.util.Collection;

/**
 * @author icode
 * @since 14-12-11
 */
class GrizzlyRequestPropertiesDelegate implements PropertiesDelegate {
    private final Request request;

    /**
     * Create new Grizzly container properties delegate instance.
     *
     * @param request grizzly HTTP request.
     */
    GrizzlyRequestPropertiesDelegate(Request request) {
        this.request = request;
    }

    @Override
    public Object getProperty(String name) {
        return request.getAttribute(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return request.getAttributeNames();
    }

    @Override
    public void setProperty(String name, Object value) {
        request.setAttribute(name, value);
    }

    @Override
    public void removeProperty(String name) {
        request.removeAttribute(name);
    }
}