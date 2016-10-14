package ameba.container.grizzly.server.http.websocket;

import ameba.core.Application;
import ameba.util.ClassUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.tyrus.core.DebugContext;
import org.glassfish.tyrus.core.TyrusWebSocketEngine;
import org.glassfish.tyrus.core.Utils;
import org.glassfish.tyrus.core.cluster.ClusterContext;
import org.glassfish.tyrus.core.frame.TyrusFrame;
import org.glassfish.tyrus.core.monitoring.ApplicationEventListener;
import org.glassfish.tyrus.core.monitoring.EndpointEventListener;
import org.glassfish.tyrus.core.monitoring.MessageEventListener;
import org.glassfish.tyrus.ext.monitoring.jmx.SessionAwareApplicationMonitor;
import org.glassfish.tyrus.server.TyrusServerContainer;
import org.glassfish.tyrus.spi.WebSocketEngine;

import javax.inject.Inject;
import javax.websocket.DeploymentException;
import javax.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author icode
 */
public class WebSocketServerContainer extends TyrusServerContainer {

    /**
     * Maximum size of incoming buffer in bytes.
     * <br>
     * The value must be {@link java.lang.Integer} or its primitive alternative.
     * <br>
     * Default value is 4194315, which means that TyrusWebSocketEngine is by default
     * capable of processing messages up to 4 MB.
     */
    public static final String WEBSOCKET_INCOMING_BUFFER_SIZE = "websocket.incomingBufferSize";

    /**
     * Maximum number of open sessions per server application.
     * <br>
     * The value must be positive {@link java.lang.Integer} or its primitive alternative. Negative values
     * and zero are ignored.
     * <br>
     * The number of open sessions per application is not limited by default.
     */
    public static final String WEBSOCKET_MAX_SESSIONS_PER_APP = "websocket.maxSessionsPerApp";

    /**
     * Maximum number of open sessions per unique remote address.
     * <br>
     * The value must be positive {@link java.lang.Integer} or its primitive alternative. Negative values
     * and zero are ignored.
     * <br>
     * The number of open sessions per remote address is not limited by default.
     */
    public static final String WEBSOCKET_MAX_SESSIONS_PER_REMOTE_ADDR = "websocket.maxSessionsPerRemoteAddr";

    /**
     * Property used for configuring the type of tracing supported by the server.
     * <br>
     * The value is expected to be string value of {@link org.glassfish.tyrus.core.DebugContext.TracingType}.
     * <br>
     * The default value is {@link org.glassfish.tyrus.core.DebugContext.TracingType#OFF}.
     */
    public static final String WEBSOCKET_TRACING_TYPE = "websocket.tracingType";

    /**
     * Property used for configuring tracing threshold.
     * <br>
     * The value is expected to be string value of {@link org.glassfish.tyrus.core.DebugContext.TracingThreshold}.
     * <br>
     * The default value is {@link org.glassfish.tyrus.core.DebugContext.TracingThreshold#SUMMARY}.
     */
    public static final String WEBSOCKET_TRACING_THRESHOLD = "websocket.tracingThreshold";

    /**
     * Parallel broadcast support.
     * <br>
     * {@link org.glassfish.tyrus.core.TyrusSession#broadcast(String)} and {@link org.glassfish.tyrus.core.TyrusSession#broadcast(java.nio.ByteBuffer)}
     * operations are by default executed in parallel. The parallel execution of broadcast can be disabled by setting
     * this server property to {@code false}.
     * <br>
     * Expected value is {@code true} or {@code false} and the default value is {@code true}.
     *
     * @see org.glassfish.tyrus.core.TyrusSession#broadcast(String) .
     * @see org.glassfish.tyrus.core.TyrusSession#broadcast(java.nio.ByteBuffer) .
     */
    public static final String WEBSOCKET_PARALLEL_BROADCAST_ENABLED = "websocket.parallelBroadcastEnabled";


    /**
     * ClusterContext registration property.
     * <br>
     * ClusterContext is registered to the Server container via properties passed to {@link org.glassfish.tyrus.spi.ServerContainerFactory#createServerContainer(java.util.Map)}.
     */
    public static final String WEBSOCKET_CLUSTER_CONTEXT = "websocket.cluster.ClusterContext";


    /**
     * A key used for registering a application event listener implementation.
     * <br>
     * For monitoring in Grizzly server an instance should be passed to the server in server properties:
     * <pre>
     *     serverProperties.put(ApplicationEventListener.APPLICATION_EVENT_LISTENER, new MyApplicationEventListener());
     * </pre>
     * For use in servlet container the class name should be passed as a context parameter in web.xml:
     * <pre>{@code
     *     <context-param>
     *         <param-name>org.glassfish.tyrus.core.monitoring.ApplicationEventListener</param-name>
     *         <param-value>com.acme.MyApplicationEventListener</param-value>
     *     </context-param>}</pre>
     */
    public static final String WEBSOCKET_APPLICATION_EVENT_LISTENER = "websocket.monitoring.ApplicationEventListener";
    private Integer incomingBufferSize;
    private Integer maxSessionsPerApp;
    private Integer maxSessionsPerRemoteAddr;
    private Boolean parallelBroadcastEnabled;
    private DebugContext.TracingType tracingType;
    private DebugContext.TracingThreshold tracingThreshold;
    private ClusterContext clusterContext;
    private ApplicationEventListener applicationEventListener;
    private WebSocketEngine engine;
    private int port;
    private String contextPath;

    @Inject
    public WebSocketServerContainer(Application application) {
        super((Set<Class<?>>) null);
        Map<String, Object> properties = application.getProperties();
        final Map<String, Object> localProperties;
        // defensive copy
        if (properties == null) {
            localProperties = Collections.emptyMap();
        } else {
            localProperties = new HashMap<>(properties);
        }

        incomingBufferSize = Utils.getProperty(localProperties, WEBSOCKET_INCOMING_BUFFER_SIZE, Integer.class);
        maxSessionsPerApp = Utils.getProperty(localProperties, WEBSOCKET_MAX_SESSIONS_PER_APP, Integer.class);
        maxSessionsPerRemoteAddr = Utils.getProperty(localProperties, WEBSOCKET_MAX_SESSIONS_PER_REMOTE_ADDR, Integer.class);
        parallelBroadcastEnabled = Utils.getProperty(localProperties, WEBSOCKET_PARALLEL_BROADCAST_ENABLED, Boolean.class);


        String clusterContextClass = Utils.getProperty(localProperties, WEBSOCKET_CLUSTER_CONTEXT, String.class);
        if (StringUtils.isNotBlank(clusterContextClass)) {
            clusterContext = ClassUtils.newInstance(clusterContextClass);
        }

        final String applicationEventListenerClass = Utils.getProperty(localProperties, WEBSOCKET_APPLICATION_EVENT_LISTENER, String.class);
        if (StringUtils.isNotBlank(applicationEventListenerClass)) {
            applicationEventListener = ClassUtils.newInstance(applicationEventListenerClass);
        }
        if ("true".equals(localProperties.get("jmx.enabled"))) {
            if (applicationEventListener == null) {
                applicationEventListener = new SessionAwareApplicationMonitor();
            } else {
                applicationEventListener = new ApplicationEventListener() {
                    private ApplicationEventListener jmx = new SessionAwareApplicationMonitor();
                    private ApplicationEventListener src = ClassUtils.newInstance(applicationEventListenerClass);

                    @Override
                    public void onApplicationInitialized(String applicationName) {
                        try {
                            src.onApplicationInitialized(applicationName);
                        } finally {
                            jmx.onApplicationInitialized(applicationName);
                        }
                    }

                    @Override
                    public void onApplicationDestroyed() {
                        try {
                            src.onApplicationDestroyed();
                        } finally {
                            jmx.onApplicationDestroyed();
                        }
                    }

                    @Override
                    public EndpointEventListener onEndpointRegistered(String endpointPath, Class<?> endpointClass) {
                        final EndpointEventListener srcL;
                        final EndpointEventListener jmxL;
                        try {
                            srcL = src.onEndpointRegistered(endpointPath, endpointClass);
                        } finally {
                            jmxL = jmx.onEndpointRegistered(endpointPath, endpointClass);
                        }
                        return new EndpointEventListener() {
                            @Override
                            public MessageEventListener onSessionOpened(final String sessionId) {
                                final MessageEventListener srcM;
                                final MessageEventListener jmxM;
                                try {
                                    srcM = srcL.onSessionOpened(sessionId);
                                } finally {
                                    jmxM = jmxL.onSessionOpened(sessionId);
                                }
                                return new MessageEventListener() {
                                    @Override
                                    public void onFrameSent(TyrusFrame.FrameType frameType, long payloadLength) {
                                        try {
                                            srcM.onFrameSent(frameType, payloadLength);
                                        } finally {
                                            jmxM.onFrameSent(frameType, payloadLength);
                                        }
                                    }

                                    @Override
                                    public void onFrameReceived(TyrusFrame.FrameType frameType, long payloadLength) {
                                        try {
                                            srcM.onFrameReceived(frameType, payloadLength);
                                        } finally {
                                            jmxM.onFrameReceived(frameType, payloadLength);
                                        }
                                    }
                                };
                            }

                            @Override
                            public void onSessionClosed(String sessionId) {
                                try {
                                    srcL.onSessionClosed(sessionId);
                                } finally {
                                    jmxL.onSessionClosed(sessionId);
                                }
                            }

                            @Override
                            public void onError(String sessionId, Throwable t) {
                                try {
                                    srcL.onError(sessionId, t);
                                } finally {
                                    jmxL.onError(sessionId, t);
                                }
                            }
                        };
                    }

                    @Override
                    public void onEndpointUnregistered(String endpointPath) {
                        try {
                            src.onEndpointUnregistered(endpointPath);
                        } finally {
                            jmx.onEndpointUnregistered(endpointPath);
                        }
                    }
                };
            }
        }

        tracingType = Utils.getProperty(localProperties, WEBSOCKET_TRACING_TYPE, DebugContext.TracingType.class, DebugContext.TracingType.OFF);
        tracingThreshold = Utils.getProperty(localProperties, WEBSOCKET_TRACING_THRESHOLD, DebugContext.TracingThreshold.class, DebugContext.TracingThreshold.TRACE);
        buildEngine();
    }

    private void buildEngine() {
        engine = TyrusWebSocketEngine.builder(this)
                .incomingBufferSize(incomingBufferSize)
                .clusterContext(clusterContext)
                .applicationEventListener(applicationEventListener)
                .maxSessionsPerApp(maxSessionsPerApp)
                .maxSessionsPerRemoteAddr(maxSessionsPerRemoteAddr)
                .parallelBroadcastEnabled(BooleanUtils.isTrue(parallelBroadcastEnabled))
                .tracingType(tracingType)
                .tracingThreshold(tracingThreshold)
                .build();
    }

    @Override
    public void register(Class<?> endpointClass) throws DeploymentException {
        engine.register(endpointClass, contextPath);
    }

    @Override
    public void register(ServerEndpointConfig serverEndpointConfig) throws DeploymentException {
        engine.register(serverEndpointConfig, contextPath);
    }

    @Override
    public WebSocketEngine getWebSocketEngine() {
        return engine;
    }

    public Integer getIncomingBufferSize() {
        return incomingBufferSize;
    }

    @Override
    public void start(final String rootPath, int port) throws IOException, DeploymentException {
        contextPath = rootPath;
        this.port = port;
        super.start(rootPath, port);
    }

    public int getPort() {
        return port;
    }

    public String getContextPath() {
        return contextPath;
    }

    @Override
    public void stop() {
        super.stop();
        if (applicationEventListener != null) {
            applicationEventListener.onApplicationDestroyed();
        }
    }
}
