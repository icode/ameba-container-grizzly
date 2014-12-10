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

    public static Localizable localizableEXCEPTION_SENDING_ERROR_RESPONSE(Object arg0, Object arg1) {
        return messageFactory.getMessage("exception.sending.error.response", arg0, arg1);
    }

    /**
     * I/O exception occurred while sending "{0}/{1}" error response.
     *
     */
    public static String EXCEPTION_SENDING_ERROR_RESPONSE(Object arg0, Object arg1) {
        return localizer.localize(localizableEXCEPTION_SENDING_ERROR_RESPONSE(arg0, arg1));
    }

    public static Localizable localizableFAILED_TO_START_SERVER(Object arg0) {
        return messageFactory.getMessage("failed.to.start.server", arg0);
    }

    /**
     * Failed to start Grizzly HTTP server: {0}
     *
     */
    public static String FAILED_TO_START_SERVER(Object arg0) {
        return localizer.localize(localizableFAILED_TO_START_SERVER(arg0));
    }

}
