package ameba.container.grizzly.server.http.websocket;

import ameba.container.Container;
import ameba.container.server.Request;
import ameba.websocket.WebSocketException;
import com.google.common.collect.Maps;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CloseListener;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.http.*;
import org.glassfish.grizzly.http.util.Parameters;
import org.glassfish.grizzly.memory.ByteBufferArray;
import org.glassfish.grizzly.threadpool.Threads;
import org.glassfish.grizzly.utils.Charsets;
import org.glassfish.jersey.internal.PropertiesDelegate;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.internal.monitoring.EmptyRequestEventBuilder;
import org.glassfish.jersey.server.internal.monitoring.RequestEventBuilder;
import org.glassfish.jersey.server.internal.process.ReferencesInitializer;
import org.glassfish.jersey.server.internal.process.RequestProcessingContext;
import org.glassfish.jersey.server.internal.process.RequestProcessingContextReference;
import org.glassfish.jersey.server.internal.routing.UriRoutingContext;
import org.glassfish.tyrus.container.grizzly.client.GrizzlyWriter;
import org.glassfish.tyrus.container.grizzly.client.TaskProcessor;
import org.glassfish.tyrus.core.CloseReasons;
import org.glassfish.tyrus.core.RequestContext;
import org.glassfish.tyrus.core.TyrusUpgradeResponse;
import org.glassfish.tyrus.core.Utils;
import org.glassfish.tyrus.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * WebSocket {@link BaseFilter} implementation, which supposed to be placed into a {@link FilterChain} right after HTTP
 * Filter: {@link HttpServerFilter}, {@link HttpClientFilter}; depending whether it's server or client side. The
 * <tt>WebSocketFilter</tt> handles websocket connection, handshake phases and, when receives a websocket frame -
 * redirects it to appropriate connection ({@link org.glassfish.tyrus.core.TyrusEndpointWrapper}, {@link org.glassfish.tyrus.core.TyrusWebSocket}) for processing.
 *
 * @author Alexey Stashok
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 * @author icode
 */
public class GrizzlyServerFilter extends BaseFilter {

    private static final Logger logger = LoggerFactory.getLogger(GrizzlyServerFilter.class);

    private static final Attribute<org.glassfish.tyrus.spi.Connection> TYRUS_CONNECTION =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(GrizzlyServerFilter.class.getName() + ".Connection");
    private static final Attribute<TaskProcessor> TASK_PROCESSOR = Grizzly.DEFAULT_ATTRIBUTE_BUILDER
            .createAttribute(TaskProcessor.class.getName() + ".TaskProcessor");
    private static final Attribute<Scope> REQ_SCOPE = Grizzly.DEFAULT_ATTRIBUTE_BUILDER
            .createAttribute(Scope.class.getName() + ".Scope");
    public final String ATTR_NAME = "org.glassfish.tyrus.container.grizzly.WebSocketFilter.HANDSHAKE_PROCESSED";
    private final Container container;
    private final String contextPath;

    // ------------------------------------------------------------ Constructors

    /**
     * Constructs a new {@link GrizzlyServerFilter}.
     *
     * @param container   server container provider.
     * @param contextPath the context path of the deployed application. If the value is "" or "/", a request URI "/a"
     *                    will be divided into context path "" and url-pattern "/a".
     */
    public GrizzlyServerFilter(Container container, String contextPath) {
        this.container = container;
        this.contextPath = contextPath.endsWith("/") ? contextPath : contextPath + "/";
    }

    // ----------------------------------------------------- Methods from Filter

    private static UpgradeRequest createWebSocketRequest(final HttpContent content) {

        final HttpRequestPacket requestPacket = (HttpRequestPacket) content.getHttpHeader();

        Parameters parameters = new Parameters();

        parameters.setQuery(requestPacket.getQueryStringDC());
        parameters.setQueryStringEncoding(Charsets.UTF8_CHARSET);

        Map<String, String[]> parameterMap = Maps.newHashMap();

        for (String paramName : parameters.getParameterNames()) {
            parameterMap.put(paramName, parameters.getParameterValues(paramName));
        }

        final RequestContext requestContext = RequestContext.Builder.create()
                .requestURI(URI.create(requestPacket.getRequestURI()))
                .queryString(requestPacket.getQueryString())
                .parameterMap(parameterMap)
                .secure(requestPacket.isSecure())
                .remoteAddr(requestPacket.getRemoteAddress())
                .build();

        for (String name : requestPacket.getHeaders().names()) {
            for (String headerValue : requestPacket.getHeaders().values(name)) {

                final List<String> values = requestContext.getHeaders().get(name);
                if (values == null) {
                    requestContext.getHeaders().put(name, Utils.parseHeaderValue(headerValue.trim()));
                } else {
                    values.addAll(Utils.parseHeaderValue(headerValue.trim()));
                }
            }
        }

        return requestContext;
    }

    /**
     * Method handles Grizzly {@link Connection} close phase. Check if the {@link Connection} is a {@link org.glassfish.tyrus.core.TyrusWebSocket}, if
     * yes - tries to close the websocket gracefully (sending close frame) and calls {@link
     * org.glassfish.tyrus.core.TyrusWebSocket#onClose(org.glassfish.tyrus.core.frame.CloseFrame)}. If the Grizzly {@link Connection} is not websocket - passes processing to the next
     * filter in the chain.
     *
     * @param ctx {@link FilterChainContext}
     * @return {@link NextAction} instruction for {@link FilterChain}, how it should continue the execution
     * @throws IOException error
     */
    @Override
    public NextAction handleClose(FilterChainContext ctx) throws IOException {
        if (getConnection(ctx) != null) {
            return ctx.getStopAction();
        }
        return ctx.getInvokeAction();
    }

    /**
     * Handle Grizzly {@link Connection} read phase. If the {@link Connection} has associated {@link org.glassfish.tyrus.core.TyrusWebSocket} object
     * (websocket connection), we check if websocket handshake has been completed for this connection, if not -
     * initiate/validate handshake. If handshake has been completed - parse websocket {@link org.glassfish.tyrus.core.frame.Frame}s one by one and
     * pass processing to appropriate {@link org.glassfish.tyrus.core.TyrusWebSocket}: {@link org.glassfish.tyrus.core.TyrusEndpointWrapper} for server- and client- side
     * connections.
     *
     * @param ctx {@link FilterChainContext}
     * @return {@link NextAction} instruction for {@link FilterChain}, how it should continue the execution
     * @throws IOException error
     */
    @Override
    @SuppressWarnings("unchecked")
    public NextAction handleRead(FilterChainContext ctx) throws IOException {

        // Get the parsed HttpContent (we assume prev. filter was HTTP)
        final HttpContent message = ctx.getMessage();

        HttpRequestPacket requestPacket = (HttpRequestPacket) message.getHttpHeader();

        if (requestPacket != null && !requestPacket.getRequestURI().startsWith(contextPath)) {
            // the request is not for the deployed application
            return ctx.getInvokeAction();
        }

        final org.glassfish.tyrus.spi.Connection tyrusConnection = getConnection(ctx);

        if (tyrusConnection == null) {
            // Get the HTTP header
            final HttpHeader header = message.getHttpHeader();

            // If websocket is null - it means either non-websocket Connection
            if (!UpgradeRequest.WEBSOCKET.equalsIgnoreCase(header.getUpgrade()) && message.getHttpHeader().isRequest()) {
                // if it's not a websocket connection - pass the processing to the next filter
                return ctx.getInvokeAction();
            }

            final AttributeHolder attributeHolder = ctx.getAttributes();
            if (attributeHolder != null) {
                final Object attribute = attributeHolder.getAttribute(ATTR_NAME);
                if (attribute != null) {
                    // handshake was already performed on this context.
                    return ctx.getInvokeAction();
                } else {
                    attributeHolder.setAttribute(ATTR_NAME, true);
                }
            }
            // Handle handshake
            if (logger.isTraceEnabled()) {
                logger.trace("handleHandshake websocket: {} content-size={} headers=\n{}",
                        message.getContent().remaining(), message.getHttpHeader());
            }
            return handleHandshake(ctx, message);
        }

        if (logger.isTraceEnabled()) {
            logger.trace("handleRead websocket: {} content-size={} headers=\n{}",
                    tyrusConnection, message.getContent().remaining(), message.getHttpHeader());
        }

        // tyrusConnection is not null
        // this is websocket with the completed handshake
        if (message.getContent().hasRemaining()) {
            if (execute(ctx, () -> {
                // get the frame(s) content
                Buffer buffer = message.getContent();
                message.recycle();
                final ReadHandler readHandler = tyrusConnection.getReadHandler();
                TaskProcessor taskProcessor = getTaskProcessor(ctx);
                if (!buffer.isComposite()) {
                    taskProcessor.processTask(new ProcessTask(buffer.toByteBuffer(), readHandler));
                } else {
                    final ByteBufferArray byteBufferArray = buffer.toByteBufferArray();
                    final ByteBuffer[] array = byteBufferArray.getArray();

                    for (int i = 0; i < byteBufferArray.size(); i++) {
                        taskProcessor.processTask(new ProcessTask(array[i], readHandler));
                    }

                    byteBufferArray.recycle();
                }
            }))
                return ctx.getSuspendAction();
        }
        return ctx.getStopAction();
    }

    private org.glassfish.tyrus.spi.Connection getConnection(FilterChainContext ctx) {
        return TYRUS_CONNECTION.get(ctx.getConnection());
    }

    private TaskProcessor getTaskProcessor(FilterChainContext ctx) {
        return TASK_PROCESSOR.get(ctx.getConnection());
    }

    private Scope getScope(FilterChainContext ctx) {
        return REQ_SCOPE.get(ctx.getConnection());
    }

    private SecurityContext getSecurityContext(final UpgradeRequest request) {
        return new SecurityContext() {

            @Override
            public boolean isUserInRole(final String role) {
                return request.isUserInRole(role);
            }

            @Override
            public boolean isSecure() {
                return request.isSecure();
            }

            @Override
            public Principal getUserPrincipal() {
                return request.getUserPrincipal();
            }

            @Override
            public String getAuthenticationScheme() {
                return null;
            }
        };
    }

    private void initJerseyInjection(UpgradeRequest upgradeRequest, final Connection grizzlyConnection) {
        RequestEventBuilder monitoringEventBuilder = EmptyRequestEventBuilder.INSTANCE;
        InjectionManager manager = container.getInjectionManager();
        final Map<String, Object> attrs = Maps.newLinkedHashMap();
        final URI uri = upgradeRequest.getRequestURI();
        final ContainerRequest _request;
        try {
            _request = new Request(
                    new URI(uri.getScheme(), null, uri.getHost(),
                            uri.getPort(), contextPath, null, null),
                    upgradeRequest.getRequestURI(),
                    "GET",
                    getSecurityContext(upgradeRequest),
                    new PropertiesDelegate() {

                        @Override
                        public Object getProperty(String name) {
                            return attrs.get(name);
                        }

                        @Override
                        public Collection<String> getPropertyNames() {
                            return attrs.keySet();
                        }

                        @Override
                        public void setProperty(String name, Object object) {
                            attrs.put(name, object);
                        }

                        @Override
                        public void removeProperty(String name) {
                            attrs.remove(name);
                        }
                    }
            ) {
                @Override
                public String getRemoteAddr() {
                    return ((InetSocketAddress) grizzlyConnection.getPeerAddress())
                            .getAddress().getHostAddress();
                }

                @Override
                public String getRemoteHost() {
                    return ((InetSocketAddress) grizzlyConnection.getPeerAddress())
                            .getHostName();
                }

                @Override
                public int getRemotePort() {
                    return ((InetSocketAddress) grizzlyConnection.getLocalAddress())
                            .getPort();
                }

                @Override
                public String getLocalAddr() {
                    return ((InetSocketAddress) grizzlyConnection.getLocalAddress())
                            .getAddress().getHostAddress();
                }

                @Override
                public String getLocalName() {
                    return ((InetSocketAddress) grizzlyConnection.getLocalAddress())
                            .getHostName();
                }

                @Override
                public int getLocalPort() {
                    return ((InetSocketAddress) grizzlyConnection.getLocalAddress())
                            .getPort();
                }

                @Override
                public URI getRawReqeustUri() {
                    return UriBuilder.fromUri(uri).build();
                }
            };
        } catch (URISyntaxException e) {
            throw new WebSocketException(e);
        }

        for (Map.Entry<String, List<String>> header : upgradeRequest.getHeaders().entrySet()) {
            _request.getHeaders().addAll(header.getKey(), header.getValue());
        }
        final UriRoutingContext routingContext = (UriRoutingContext) _request.getUriInfo();

        final RequestProcessingContext reqContext = new RequestProcessingContext(
                manager,
                _request,
                routingContext,
                monitoringEventBuilder,
                null);
        final ReferencesInitializer referencesInitializer = new ReferencesInitializer(manager,
                () -> manager.getInstance(RequestProcessingContextReference.class));
        referencesInitializer.apply(reqContext);
    }

    /**
     * Handle websocket handshake
     *
     * @param ctx     {@link FilterChainContext}
     * @param content HTTP message
     * @return {@link NextAction} instruction for {@link org.glassfish.grizzly.filterchain.FilterChain}, how it should continue the execution
     */
    private NextAction handleHandshake(final FilterChainContext ctx, final HttpContent content) {

        final org.glassfish.grizzly.Connection grizzlyConnection = ctx.getConnection();

        final UpgradeRequest upgradeRequest = createWebSocketRequest(content);
        final UpgradeResponse upgradeResponse = new TyrusUpgradeResponse();
        final WebSocketEngine.UpgradeInfo upgradeInfo = ((ServerContainer) container.getWebSocketContainer())
                .getWebSocketEngine().upgrade(upgradeRequest, upgradeResponse);

        switch (upgradeInfo.getStatus()) {
            case SUCCESS:
                write(ctx, upgradeResponse);

                Scope scope = Scope.create(container.getInjectionManager());
                org.glassfish.jersey.process.internal.RequestContext old = scope.activate();
                initJerseyInjection(upgradeRequest, grizzlyConnection);
                org.glassfish.tyrus.spi.Connection connection;
                try {
                    connection = upgradeInfo.createConnection(
                            new GrizzlyWriter(ctx.getConnection()),
                            reason -> grizzlyConnection.close()
                    );
                } finally {
                    scope.resume(old);
                }
                REQ_SCOPE.set(grizzlyConnection, scope);
                TYRUS_CONNECTION.set(grizzlyConnection, connection);
                TASK_PROCESSOR.set(grizzlyConnection, new TaskProcessor());

                grizzlyConnection.addCloseListener((CloseListener) (closeable, type) -> execute(ctx, () -> {
                    try {
                        if (logger.isTraceEnabled()) {
                            logger.trace("websocket closing: {}", connection);
                        }
                        // close detected on connection
                        connection.close(CloseReasons.GOING_AWAY.getCloseReason());
                        // might not be necessary, connection is going to be recycled/freed anyway
                        REQ_SCOPE.remove(grizzlyConnection);
                        TYRUS_CONNECTION.remove(grizzlyConnection);
                        TASK_PROCESSOR.remove(grizzlyConnection);
                        if (logger.isTraceEnabled()) {
                            logger.trace("websocket closed: {}", connection);
                        }
                    } finally {
                        scope.release();
                    }
                }));

                if (logger.isTraceEnabled()) {
                    logger.trace("handleHandshake websocket success: {} content-size={} headers=\n{}",
                            grizzlyConnection, content.getContent().remaining(), content.getHttpHeader());
                }

                return ctx.getStopAction();

            case HANDSHAKE_FAILED:
                write(ctx, upgradeResponse);
                content.recycle();
                if (logger.isTraceEnabled()) {
                    logger.trace("handleHandshake websocket failed: content-size={} headers=\n{}",
                            content.getContent().remaining(), content.getHttpHeader());
                }
                return ctx.getStopAction();

            case NOT_APPLICABLE:
                upgradeResponse.setStatus(404);
                write(ctx, upgradeResponse);
                content.recycle();
                if (logger.isTraceEnabled()) {
                    logger.trace("not found websocket handler: content-size={} headers=\n{}",
                            content.getContent().remaining(), content.getHttpHeader());
                }
                return ctx.getStopAction();
        }

        return ctx.getStopAction();
    }

    private void write(FilterChainContext ctx, UpgradeResponse response) {
        final HttpResponsePacket responsePacket =
                ((HttpRequestPacket) ((HttpContent) ctx.getMessage()).getHttpHeader()).getResponse();
        responsePacket.setProtocol(Protocol.HTTP_1_1);
        responsePacket.setStatus(response.getStatus());

        for (Map.Entry<String, List<String>> entry : response.getHeaders().entrySet()) {
            responsePacket.setHeader(entry.getKey(), Utils.getHeaderFromList(entry.getValue()));
        }

        ctx.write(HttpContent.builder(responsePacket).build());
    }

    protected Executor getExecutor(FilterChainContext ctx) {
        if (!Threads.isService()) {
            return null; // Execute in the current thread
        }

        return ctx.getConnection().getTransport().getWorkerThreadPool();
    }

    protected boolean execute(FilterChainContext ctx, Runnable runnable) {
        return execute(ctx, getScope(ctx), runnable);
    }

    protected boolean execute(FilterChainContext ctx, final Scope scope, Runnable runnable) {
        final Executor threadPool = getExecutor(ctx);

        if (threadPool != null) {
            ctx.suspend();
            threadPool.execute(() -> {
                org.glassfish.jersey.process.internal.RequestContext old = scope.activate();
                try {
                    runnable.run();
                } finally {
                    scope.resume(old);
                    ctx.resume(ctx.getStopAction());
                }
            });
            return true;
        } else {
            org.glassfish.jersey.process.internal.RequestContext old = scope.activate();
            try {
                runnable.run();
            } finally {
                scope.resume(old);
            }
        }
        return false;
    }

    private class ProcessTask extends TaskProcessor.Task {
        private final ByteBuffer buffer;
        private final ReadHandler readHandler;

        private ProcessTask(ByteBuffer buffer, ReadHandler readHandler) {
            this.buffer = buffer;
            this.readHandler = readHandler;
        }

        @Override
        public void execute() {
            readHandler.handle(buffer);
        }
    }
}