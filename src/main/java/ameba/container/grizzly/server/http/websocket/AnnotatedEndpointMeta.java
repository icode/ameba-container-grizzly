package ameba.container.grizzly.server.http.websocket;

import ameba.websocket.WebSocket;
import ameba.websocket.internal.AbstractAnnotatedEndpointMeta;
import com.google.common.collect.Lists;
import com.google.common.primitives.Primitives;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.Injections;
import org.glassfish.tyrus.core.ComponentProviderService;
import org.glassfish.tyrus.core.TyrusServerEndpointConfig;
import org.glassfish.tyrus.core.coder.*;

import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.server.ServerEndpointConfig;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * @author icode
 */
public class AnnotatedEndpointMeta extends AbstractAnnotatedEndpointMeta {
    private ComponentProviderService componentProvider;

    public AnnotatedEndpointMeta(Class endpointClass,
                                 WebSocket webSocket,
                                 Integer incomingBufferSize,
                                 InjectionManager manager,
                                 ComponentProviderService componentProviderService) {
        super(endpointClass, webSocket, incomingBufferSize, manager);
        componentProvider = componentProviderService;
    }

    @Override
    protected Method getInvocableMethod(Method method) {
        return componentProvider.getInvocableMethod(method);
    }

    @Override
    protected <T> Object getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        return componentProvider.getEndpointInstance(endpointClass);
    }

    @Override
    protected ServerEndpointConfig buildServerEndpointConfig(String path, WebSocket wseAnnotation, Class<?> annotatedClass, String[] subProtocols, List<Class<? extends Encoder>> encoderClasses, List<Class<? extends Decoder>> decoderClasses) {
        int max = getMaxSessions(annotatedClass);
        if (max != -1) {
            TyrusServerEndpointConfig.Builder builder =
                    TyrusServerEndpointConfig.Builder
                            .create(annotatedClass, path)
                            .encoders(encoderClasses)
                            .decoders(decoderClasses)
                            .subprotocols(Arrays.asList(subProtocols));
            if (!wseAnnotation.configurator().equals(ServerEndpointConfig.Configurator.class)) {
                builder = builder.configurator(Injections.getOrCreate(manager, wseAnnotation.configurator()));
            }
            builder.maxSessions(max);
            return builder.build();
        }
        return super.buildServerEndpointConfig(path, wseAnnotation, annotatedClass, subProtocols, encoderClasses, decoderClasses);
    }

    @Override
    protected List<Class<? extends Decoder>> getDefaultDecoders() {
        final List<Class<? extends Decoder>> classList = Lists.newArrayList();
        classList.addAll(PrimitiveDecoders.ALL);
        classList.add(NoOpTextCoder.class);
        classList.add(NoOpByteBufferCoder.class);
        classList.add(NoOpByteArrayCoder.class);
        classList.add(ReaderDecoder.class);
        classList.add(InputStreamDecoder.class);
        return classList;
    }

    @Override
    protected Decoder.Text<?> getPathParameterDecoder(Class<?> type) {
        return PrimitiveDecoders.ALL_INSTANCES.get(Primitives.wrap(type));
    }
}
