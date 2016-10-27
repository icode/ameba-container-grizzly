package ameba.container.grizzly.server.http.websocket;

import ameba.core.Requests;
import ameba.websocket.WebSocket;
import ameba.websocket.WebSocketException;
import ameba.websocket.WebSocketSession;
import ameba.websocket.internal.EndpointMeta;
import ameba.websocket.internal.NativeWebSocketSession;
import com.google.common.collect.Maps;
import com.google.common.primitives.Primitives;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.internal.inject.Injections;
import org.glassfish.jersey.internal.util.collection.Ref;
import org.glassfish.jersey.internal.util.collection.Refs;
import org.glassfish.tyrus.core.ComponentProviderService;
import org.glassfish.tyrus.core.ErrorCollector;
import org.glassfish.tyrus.core.MaxSessions;
import org.glassfish.tyrus.core.TyrusServerEndpointConfig;
import org.glassfish.tyrus.core.coder.*;
import org.glassfish.tyrus.core.l10n.LocalizationMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpointConfig;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.*;

/**
 * @author icode
 */
public class AnnotatedEndpointMeta extends EndpointMeta {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedEndpointMeta.class);
    private static final int INCOMING_BUFFER_SIZE = 4194315; // 4M (payload) + 11 (frame overhead)
    private MethodHandle onOpenMethodHandle;
    private MethodHandle onErrorMethodHandle;
    private MethodHandle onCloseMethodHandle;
    private ParameterExtractor[] onOpenParameters;
    private ParameterExtractor[] onCloseParameters;
    private ParameterExtractor[] onErrorParameters;
    private EndpointConfig configuration;
    private ComponentProviderService componentProvider;

    public AnnotatedEndpointMeta(Class endpointClass, WebSocket webSocket,
                                 Integer incomingBufferSize,
                                 ServiceLocator locator,
                                 ComponentProviderService componentProviderService) {
        super(endpointClass);
        if (incomingBufferSize == null) {
            incomingBufferSize = INCOMING_BUFFER_SIZE;
        }
        final ErrorCollector collector = new ErrorCollector();
        configuration = createEndpointConfig(endpointClass, webSocket, locator);
        componentProvider = new ComponentProviderService(componentProviderService) {
            @Override
            public <T> Object getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
                return ((ServerEndpointConfig) configuration).getConfigurator().getEndpointInstance(endpointClass);
            }
        };
        Method onOpen = null;
        Method onClose = null;
        Method onError = null;
        ParameterExtractor[] onOpenParameters = null;
        ParameterExtractor[] onCloseParameters = null;
        ParameterExtractor[] onErrorParameters = null;

        Map<Integer, Class<?>> unknownParams = Maps.newLinkedHashMap();
        for (Method m : endpointClass.getMethods()) {
            if (m.isBridge()) {
                continue;
            }

            for (Annotation a : m.getAnnotations()) {
                if (a instanceof OnOpen) {
                    if (onOpen == null) {
                        onOpen = m;
                        onOpenParameters = getParameterExtractors(m, unknownParams, collector);
                    } else {
                        collector.addException(new DeploymentException(
                                LocalizationMessages.ENDPOINT_MULTIPLE_METHODS(
                                        OnOpen.class.getSimpleName(), endpointClass.getName(), onOpen.getName(),
                                        m.getName()
                                )
                        ));
                    }
                } else if (a instanceof OnClose) {
                    if (onClose == null) {
                        onClose = m;
                        onCloseParameters = getOnCloseParameterExtractors(m, unknownParams, collector);
                        if (unknownParams.size() == 1 && unknownParams.values().iterator().next() != CloseReason
                                .class) {
                            onCloseParameters[unknownParams.keySet().iterator().next()] = new ParamValue(0);
                        }
                    } else {
                        collector.addException(new DeploymentException(
                                LocalizationMessages.ENDPOINT_MULTIPLE_METHODS(
                                        OnClose.class.getSimpleName(), endpointClass.getName(), onClose.getName(),
                                        m.getName()
                                )
                        ));
                    }
                } else if (a instanceof OnError) {
                    if (onError == null) {
                        onError = m;
                        onErrorParameters = getParameterExtractors(m, unknownParams, collector);
                        if (unknownParams.size() == 1
                                && Throwable.class == unknownParams.values().iterator().next()) {
                            onErrorParameters[unknownParams.keySet().iterator().next()] = new ParamValue(0);
                        } else if (!unknownParams.isEmpty()) {
                            logger.warn(LocalizationMessages.ENDPOINT_UNKNOWN_PARAMS(endpointClass.getName(),
                                    m.getName(), unknownParams));
                            onError = null;
                            onErrorParameters = null;
                        }
                    } else {
                        collector.addException(new DeploymentException(
                                LocalizationMessages.ENDPOINT_MULTIPLE_METHODS(
                                        OnError.class.getSimpleName(), endpointClass.getName(), onError.getName(),
                                        m.getName()
                                )
                        ));
                    }
                } else if (a instanceof OnMessage) {
                    final long maxMessageSize = ((OnMessage) a).maxMessageSize();
                    if (maxMessageSize > incomingBufferSize) {
                        logger.warn(LocalizationMessages.ENDPOINT_MAX_MESSAGE_SIZE_TOO_LONG(
                                maxMessageSize, m.getName(), endpointClass.getName(), incomingBufferSize));
                    }
                    final ParameterExtractor[] extractors = getParameterExtractors(m, unknownParams, collector);
                    MessageHandlerFactory handlerFactory;

                    if (unknownParams.size() == 1) {
                        Map.Entry<Integer, Class<?>> entry = unknownParams.entrySet().iterator().next();
                        extractors[entry.getKey()] = new ParamValue(0);
                        try {
                            handlerFactory = new WholeHandler(
                                    MethodHandles.publicLookup().unreflect(componentProvider.getInvocableMethod(m)),
                                    extractors,
                                    entry.getValue(), maxMessageSize);
                        } catch (IllegalAccessException e) {
                            throw new WebSocketException(e);
                        }
                        messageHandlerFactories.add(handlerFactory);
                    } else if (unknownParams.size() == 2) {
                        Iterator<Map.Entry<Integer, Class<?>>> it = unknownParams.entrySet().iterator();
                        Map.Entry<Integer, Class<?>> message = it.next();
                        Map.Entry<Integer, Class<?>> last;
                        if (message.getValue() == boolean.class || message.getValue() == Boolean.class) {
                            last = message;
                            message = it.next();
                        } else {
                            last = it.next();
                        }
                        extractors[message.getKey()] = new ParamValue(0);
                        extractors[last.getKey()] = new ParamValue(1);
                        if (last.getValue() == boolean.class || last.getValue() == Boolean.class) {
                            try {
                                handlerFactory = new PartialHandler(
                                        MethodHandles.publicLookup().unreflect(componentProvider.getInvocableMethod(m)),
                                        extractors,
                                        message.getValue(), maxMessageSize);
                            } catch (IllegalAccessException e) {
                                throw new WebSocketException(e);
                            }
                            messageHandlerFactories.add(handlerFactory);
                        } else {
                            collector.addException(new DeploymentException(
                                    LocalizationMessages.ENDPOINT_WRONG_PARAMS(endpointClass.getName(), m.getName())));
                        }
                    } else {
                        collector.addException(new DeploymentException(
                                LocalizationMessages.ENDPOINT_WRONG_PARAMS(endpointClass.getName(), m.getName())));
                    }
                }
            }
        }

        try {
            this.onOpenMethodHandle = onOpen == null
                    ? null
                    : MethodHandles.publicLookup().unreflect(componentProvider.getInvocableMethod(onOpen));
        } catch (IllegalAccessException e) {
            throw new WebSocketException(e);
        }
        try {
            this.onErrorMethodHandle = onError == null
                    ? null
                    : MethodHandles.publicLookup().unreflect(componentProvider.getInvocableMethod(onError));
        } catch (IllegalAccessException e) {
            throw new WebSocketException(e);
        }
        try {
            this.onCloseMethodHandle = onClose == null
                    ? null
                    : MethodHandles.publicLookup().unreflect(componentProvider.getInvocableMethod(onClose));
        } catch (IllegalAccessException e) {
            throw new WebSocketException(e);
        }
        this.onOpenParameters = onOpenParameters;
        this.onErrorParameters = onErrorParameters;
        this.onCloseParameters = onCloseParameters;
    }

    public EndpointConfig getEndpointConfig() {
        return configuration;
    }

    @Override
    public Object getEndpoint() {
        try {
            return componentProvider.getEndpointInstance(getEndpointClass());
        } catch (InstantiationException e) {
            throw new WebSocketException(e);
        }
    }

    @Override
    public MethodHandle getOnCloseHandle() {
        return onCloseMethodHandle;
    }

    @Override
    public MethodHandle getOnErrorHandle() {
        return onErrorMethodHandle;
    }

    @Override
    public MethodHandle getOnOpenHandle() {
        return onOpenMethodHandle;
    }

    @Override
    public ParameterExtractor[] getOnOpenParameters() {
        return onOpenParameters;
    }

    @Override
    public ParameterExtractor[] getOnCloseParameters() {
        return onCloseParameters;
    }

    @Override
    public ParameterExtractor[] getOnErrorParameters() {
        return onErrorParameters;
    }

    private EndpointConfig createEndpointConfig(Class<?> annotatedClass,
                                                WebSocket wseAnnotation, ServiceLocator locator) {
        List<Class<? extends Encoder>> encoderClasses = new ArrayList<Class<? extends Encoder>>();
        List<Class<? extends Decoder>> decoderClasses = new ArrayList<Class<? extends Decoder>>();
        String[] subProtocols;

        encoderClasses.addAll(Arrays.asList(wseAnnotation.encoders()));
        decoderClasses.addAll(Arrays.asList(wseAnnotation.decoders()));
        subProtocols = wseAnnotation.subprotocols();

        decoderClasses.addAll(getDefaultDecoders());

        final MaxSessions wseMaxSessionsAnnotation = annotatedClass.getAnnotation(MaxSessions.class);

        String path = wseAnnotation.path();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (wseMaxSessionsAnnotation != null) {
            TyrusServerEndpointConfig.Builder builder =
                    TyrusServerEndpointConfig.Builder
                            .create(annotatedClass, path)
                            .encoders(encoderClasses)
                            .decoders(decoderClasses)
                            .subprotocols(Arrays.asList(subProtocols));
            if (!wseAnnotation.configurator().equals(ServerEndpointConfig.Configurator.class)) {
                builder = builder.configurator(Injections.getOrCreate(locator, wseAnnotation.configurator()));
            }
            builder.maxSessions(wseMaxSessionsAnnotation.value());
            return builder.build();
        } else {
            ServerEndpointConfig.Builder builder =
                    ServerEndpointConfig.Builder
                            .create(annotatedClass, path)
                            .encoders(encoderClasses)
                            .decoders(decoderClasses)
                            .subprotocols(Arrays.asList(subProtocols));
            if (!wseAnnotation.configurator().equals(ServerEndpointConfig.Configurator.class)) {
                builder = builder.configurator(Injections.getOrCreate(locator, wseAnnotation.configurator()));
            }
            return builder.build();
        }
    }

    private List<Class<? extends Decoder>> getDefaultDecoders() {
        final List<Class<? extends Decoder>> classList = new ArrayList<Class<? extends Decoder>>();
        classList.addAll(PrimitiveDecoders.ALL);
        classList.add(NoOpTextCoder.class);
        classList.add(NoOpByteBufferCoder.class);
        classList.add(NoOpByteArrayCoder.class);
        classList.add(ReaderDecoder.class);
        classList.add(InputStreamDecoder.class);
        return classList;
    }

    private ParameterExtractor[] getOnCloseParameterExtractors(final Method method, Map<Integer, Class<?>>
            unknownParams, ErrorCollector collector) {
        return getParameterExtractors(
                method, unknownParams, new HashSet<Class<?>>(Arrays.asList((Class<?>) CloseReason.class)), collector);
    }

    private ParameterExtractor[] getParameterExtractors(final Method method, Map<Integer, Class<?>> unknownParams,
                                                        ErrorCollector collector) {
        return getParameterExtractors(method, unknownParams, Collections.<Class<?>>emptySet(), collector);
    }

    private ParameterExtractor[] getParameterExtractors(final Method method, Map<Integer, Class<?>> unknownParams,
                                                        Set<Class<?>> params, ErrorCollector collector) {
        ParameterExtractor[] result = new ParameterExtractor[method.getParameterTypes().length];
        boolean sessionPresent = false;
        unknownParams.clear();
        final Ref<WebSocketSession> sessionRef = Refs.emptyRef();

        for (int i = 0; i < method.getParameterTypes().length; i++) {
            final Class<?> type = method.getParameterTypes()[i];
            final String pathParamName = getPathParamName(method.getParameterAnnotations()[i]);
            if (pathParamName != null) {
                if (!(Primitives.isWrapperType(type) || type.isPrimitive()
                        || type.equals(String.class))) {
                    collector.addException(new DeploymentException(
                            LocalizationMessages.ENDPOINT_WRONG_PATH_PARAM(method.getName(), type.getName())));
                }

                result[i] = new ParameterExtractor() {

                    final Decoder.Text<?> decoder = PrimitiveDecoders.ALL_INSTANCES
                            .get(Primitives.wrap(type));

                    @Override
                    public Object value(Session session, Object... values) throws DecodeException {
                        Object result = null;

                        if (decoder != null) {
                            result = decoder.decode(session.getPathParameters().get(pathParamName));
                        } else if (type.equals(String.class)) {
                            result = session.getPathParameters().get(pathParamName);
                        }

                        return result;
                    }
                };
            } else if (type == Session.class) {
                if (sessionPresent) {
                    collector.addException(new DeploymentException(
                            LocalizationMessages.ENDPOINT_MULTIPLE_SESSION_PARAM(method.getName())));
                } else {
                    sessionPresent = true;
                }
                result[i] = new ParameterExtractor() {
                    @Override
                    public Object value(Session session, Object... values) {
                        return session;
                    }
                };
            } else if (type == WebSocketSession.class) {
                if (sessionPresent) {
                    collector.addException(new DeploymentException(
                            LocalizationMessages.ENDPOINT_MULTIPLE_SESSION_PARAM(method.getName())));
                } else {
                    sessionPresent = true;
                }
                result[i] = new ParameterExtractor() {
                    @Override
                    public Object value(Session session, Object... values) {
                        if (sessionRef.get() == null) {
                            sessionRef.set(new NativeWebSocketSession(session, Requests.getRequest()));
                        }
                        return sessionRef.get();
                    }
                };
            } else if (type == EndpointConfig.class) {
                result[i] = new ParameterExtractor() {
                    @Override
                    public Object value(Session session, Object... values) {
                        return getEndpointConfig();
                    }
                };
            } else if (params.contains(type)) {
                result[i] = new ParameterExtractor() {
                    @Override
                    public Object value(Session session, Object... values) {
                        for (Object value : values) {
                            if (value != null && type.isAssignableFrom(value.getClass())) {
                                return value;
                            }
                        }

                        return null;
                    }
                };
            } else {
                unknownParams.put(i, type);
            }
        }

        return result;
    }

    private String getPathParamName(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a instanceof PathParam) {
                return ((PathParam) a).value();
            } else if (a instanceof javax.ws.rs.PathParam) {
                return ((javax.ws.rs.PathParam) a).value();
            }
        }
        return null;
    }
}
