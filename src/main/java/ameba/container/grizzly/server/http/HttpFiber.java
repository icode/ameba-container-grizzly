package ameba.container.grizzly.server.http;

import ameba.lib.Fibers;
import co.paralleluniverse.common.monitoring.MonitorType;
import co.paralleluniverse.fibers.FiberForkJoinScheduler;
import co.paralleluniverse.fibers.FiberScheduler;
import co.paralleluniverse.strands.SuspendableRunnable;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.tyrus.core.Utils;

/**
 * @author icode
 */
public class HttpFiber {
    private static final String PROPERTY_PARALLELISM = "container.server.worker.fiber.parallelism";
    private static final String PROPERTY_EXCEPTION_HANDLER = "container.server.worker.fiber.exceptionHandler";
    private static final String PROPERTY_MONITOR_TYPE = "container.server.worker.fiber.monitor";
    private static final String PROPERTY_DETAILED_FIBER_INFO = "container.server.worker.fiber.detailedFiberInfo";
    private static final int MAX_CAP = 0x7fff;  // max #workers - 1
    private static final String FIBER_NAME = "ameba-http-fiber";
    private static final String POOL_NAME = FIBER_NAME + "-pool";
    private static FiberScheduler scheduler;

    public static void load(ResourceConfig config) {
        // defaults
        int par = 0;
        Thread.UncaughtExceptionHandler handler = null;
        MonitorType monitorType = Utils.getProperty(
                config.getProperties(), "jmx.enabled", Boolean.class, false
        ) ? MonitorType.JMX : MonitorType.NONE;
        boolean detailedFiberInfo = false;

        // get overrides
        try {
            String pp = (String) config.getProperty(PROPERTY_PARALLELISM);
            String hp = (String) config.getProperty(PROPERTY_EXCEPTION_HANDLER);
            if (hp != null)
                handler = ((Thread.UncaughtExceptionHandler) ClassLoader.getSystemClassLoader().loadClass(hp).newInstance());
            if (pp != null)
                par = Integer.parseInt(pp);
        } catch (Exception ignore) {
        }

        if (par <= 0)
            par = Runtime.getRuntime().availableProcessors() * 2;
        if (par > MAX_CAP)
            par = MAX_CAP;

        monitorType = Utils.getProperty(
                config.getProperties(), PROPERTY_MONITOR_TYPE, MonitorType.class, monitorType
        );

        String dfis = (String) config.getProperty(PROPERTY_DETAILED_FIBER_INFO);
        if (dfis != null)
            detailedFiberInfo = Boolean.valueOf(dfis);

        // build scheduler
        scheduler = new FiberForkJoinScheduler(POOL_NAME, par, handler, monitorType, detailedFiberInfo);
    }

    /**
     * Returns the The default {@link FiberScheduler} scheduler.
     *
     * @return the default {@link FiberScheduler} scheduler.
     */
    public static FiberScheduler scheduler() {
        return scheduler;
    }

    public static void start(SuspendableRunnable target) {
        Fibers.start(FIBER_NAME, scheduler(), -1, target);
    }
}