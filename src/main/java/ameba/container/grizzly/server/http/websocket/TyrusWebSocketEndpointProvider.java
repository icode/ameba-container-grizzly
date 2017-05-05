package ameba.container.grizzly.server.http.websocket;

import ameba.websocket.WebSocket;
import ameba.websocket.WebSocketEndpointProvider;
import ameba.websocket.internal.EndpointMeta;
import org.glassfish.jersey.internal.inject.InjectionManager;
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
    private InjectionManager manager;

    @Inject
    public TyrusWebSocketEndpointProvider(InjectionManager manager) {
        Hk2ComponentProvider.manager = manager;
        this.manager = manager;
    }

    @Override
    public EndpointMeta parseMeta(Class endpointClass, WebSocket webSocketConf) {
        return new AnnotatedEndpointMeta(
                endpointClass,
                webSocketConf,
                container.getIncomingBufferSize(),
                manager,
                componentProviderService
        );
    }
}
