package ameba.container.grizzly.server.http.internal;

import org.glassfish.jersey.internal.l10n.Localizable;
import org.glassfish.jersey.internal.l10n.LocalizableMessageFactory;
import org.glassfish.jersey.internal.l10n.Localizer;

/**
 * @author icode
 * @since 14-12-11
 */
public final class LocalizationMessages {

    private final static LocalizableMessageFactory messageFactory = new LocalizableMessageFactory("org.glassfish.jersey.grizzly2.httpserver.internal.localization");
    private final static Localizer localizer = new Localizer();

    /**
     * @param key   key
     * @param value value
     * @return Localizable
     */
    public static Localizable localizableEXCEPTION_SENDING_ERROR_RESPONSE(Object key, Object value) {
        return messageFactory.getMessage("exception.sending.error.response", key, value);
    }

    /**
     * I/O exception occurred while sending "{0}/{1}" error response.
     *
     * @param key   key
     * @param value value
     * @return value
     */
    public static String EXCEPTION_SENDING_ERROR_RESPONSE(Object key, Object value) {
        return localizer.localize(localizableEXCEPTION_SENDING_ERROR_RESPONSE(key, value));
    }

    /**
     * @param key key
     * @return Localizable
     */
    public static Localizable localizableFAILED_TO_START_SERVER(Object key) {
        return messageFactory.getMessage("failed.to.start.server", key);
    }

    /**
     * Failed to start Grizzly HTTP server: {0}
     *
     * @param key key
     * @return value
     */
    public static String FAILED_TO_START_SERVER(Object key) {
        return localizer.localize(localizableFAILED_TO_START_SERVER(key));
    }

}
