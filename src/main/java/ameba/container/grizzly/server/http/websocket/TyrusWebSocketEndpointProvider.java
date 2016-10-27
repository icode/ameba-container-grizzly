package ameba.container.grizzly.server.http.websocket;

import ameba.websocket.WebSocket;
import ameba.websocket.WebSocketEndpointProvider;
import ameba.websocket.internal.EndpointMeta;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.tyrus.core.ComponentProviderService;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * @author icode
 */
@Singleton
public class TyrusWebSocketEndpointProvider implements WebSocketEndpointProvider {
    private ComponentProviderService componentProviderService = ComponentProviderService.create();
    @Inject
    private WebSocketServerContainer container;
    private ServiceLocator locator;

    @Inject
    public TyrusWebSocketEndpointProvider(ServiceLocator locator) {
        Hk2ComponentProvider.locator = locator;
        this.locator = locator;
    }

    @Override
    public EndpointMeta parseMeta(Class endpointClass, WebSocket webSocketConf) {
        return new AnnotatedEndpointMeta(
                endpointClass,
                webSocketConf,
                container.getIncomingBufferSize(),
                locator,
                componentProviderService
        );
    }
}
