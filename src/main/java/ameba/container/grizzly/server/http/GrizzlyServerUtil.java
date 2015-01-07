package ameba.container.grizzly.server.http;

import ameba.container.Container;
import ameba.container.grizzly.server.http.websocket.WebSocketAddOn;
import ameba.container.server.Connector;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.grizzly.http.CompressionConfig;
import org.glassfish.grizzly.http.ajp.AjpAddOn;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.spdy.SpdyAddOn;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * @author icode
 */
public class GrizzlyServerUtil {
    public static final Logger logger = LoggerFactory.getLogger(GrizzlyServerUtil.class);
    public static final String DEFAULT_NETWORK_LISTENER_NAME = "ameba";


    @SuppressWarnings("unchecked")
    public static List<NetworkListener> createListeners(List<Connector> connectors, CompressionConfig compression) {
        List<NetworkListener> listeners = Lists.newArrayList();

        for (Connector connector : connectors) {
            final String host = (connector.getHost() == null) ? NetworkListener.DEFAULT_NETWORK_HOST
                    : connector.getHost();
            final int port = (connector.getPort() == -1) ? 80 : connector.getPort();
            final NetworkListener listener = new NetworkListener(
                    StringUtils.defaultString(connector.getName(), DEFAULT_NETWORK_LISTENER_NAME),
                    host,
                    port);
            listener.setSecure(connector.isSecureEnabled());
            SSLEngineConfigurator sslEngineConfigurator = createSslEngineConfigurator(connector);
            if (sslEngineConfigurator != null) {
                listener.setSSLEngineConfig(sslEngineConfigurator);

                if (connector.isSecureEnabled() && !connector.isAjpEnabled()) {
                    SpdyAddOn spdyAddon = new SpdyAddOn();
                    listener.registerAddOn(spdyAddon);
                } else if (connector.isSecureEnabled()) {
                    logger.warn("AJP模式开启，不启动SPDY支持");
                }
            }

            if (connector.isAjpEnabled()) {
                AjpAddOn ajpAddon = new AjpAddOn();
                listener.registerAddOn(ajpAddon);
            }

            CompressionConfig compressionConfig = listener.getCompressionConfig();
            CompressionConfig compressionCfg = createCompressionConfig((Map) connector.getRawProperties());

            if (compressionCfg == null) {
                compressionCfg = compression;
            }

            if (compressionCfg != null) {
                compressionConfig.set(compressionCfg);
            }
            listeners.add(listener);
        }

        return listeners;
    }

    public static CompressionConfig createCompressionConfig(Map<String, Object> properties) {
        CompressionConfig compressionConfig = null;
        String modeStr = (String) properties.get("http.compression.mode");
        if (StringUtils.isNotBlank(modeStr) && ((modeStr = modeStr.toUpperCase()).equals("ON") || modeStr.equals("FORCE"))) {

            String minSizeStr = (String) properties.get("http.compression.minSize");
            String mimeTypesStr = (String) properties.get("http.compression.mimeTypes");
            String userAgentsStr = (String) properties.get("http.compression.ignore.userAgents");

            compressionConfig = new CompressionConfig();
            compressionConfig.setCompressionMode(CompressionConfig.CompressionMode.fromString(modeStr)); // the mode
            if (StringUtils.isNotBlank(minSizeStr))
                try {
                    compressionConfig.setCompressionMinSize(Integer.parseInt(minSizeStr)); // the min amount of bytes to compress
                } catch (Exception e) {
                    logger.error("parse http.compression.minSize error", e);
                }
            if (StringUtils.isNotBlank(mimeTypesStr))
                compressionConfig.setCompressableMimeTypes(mimeTypesStr.split(",")); // the mime types to compress
            if (StringUtils.isNotBlank(userAgentsStr))
                compressionConfig.setNoCompressionUserAgents(userAgentsStr.split(","));
        }
        return compressionConfig;
    }

    public static SSLEngineConfigurator createSslEngineConfigurator(Connector connector) {
        SSLEngineConfigurator sslEngineConfigurator = null;
        if (connector.isSslConfigReady()) {
            SSLContextConfigurator sslContextConfiguration = new SSLContextConfigurator();
            sslContextConfiguration.setKeyPass(connector.getSslKeyPassword());
            sslContextConfiguration.setSecurityProtocol(connector.getSslProtocol());

            sslContextConfiguration.setKeyStoreBytes(connector.getSslKeyStoreFile());
            sslContextConfiguration.setKeyStorePass(connector.getSslKeyStorePassword());
            sslContextConfiguration.setKeyStoreProvider(connector.getSslKeyStoreProvider());
            sslContextConfiguration.setKeyStoreType(connector.getSslKeyStoreType());
            sslContextConfiguration.setKeyManagerFactoryAlgorithm(connector.getSslKeyManagerFactoryAlgorithm());

            sslContextConfiguration.setTrustStoreBytes(connector.getSslTrustStoreFile());
            if (StringUtils.isNotBlank(connector.getSslTrustStorePassword()))
                sslContextConfiguration.setTrustStorePass(connector.getSslTrustStorePassword());
            sslContextConfiguration.setTrustStoreType(connector.getSslTrustStoreType());
            sslContextConfiguration.setTrustStoreProvider(connector.getSslTrustStoreProvider());
            sslContextConfiguration.setTrustManagerFactoryAlgorithm(connector.getSslTrustManagerFactoryAlgorithm());

            sslEngineConfigurator = new SSLEngineConfigurator(
                    sslContextConfiguration,
                    connector.isSslClientMode(),
                    connector.isSslNeedClientAuth(),
                    connector.isSslWantClientAuth());
        }
        return sslEngineConfigurator;
    }

    public static void bindWebSocket(String contextPath, Container.WebSocketContainerProvider provider, final List<NetworkListener> listeners) {
        org.glassfish.grizzly.http.server.AddOn addOn = new WebSocketAddOn(provider, contextPath);

        for (NetworkListener listener : listeners) {
            // idle timeout set to indefinite.
            listener.getKeepAlive().setIdleTimeoutInSeconds(-1);
            listener.registerAddOn(addOn);
        }

    }
}
