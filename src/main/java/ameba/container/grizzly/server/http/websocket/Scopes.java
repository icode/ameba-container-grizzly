package ameba.container.grizzly.server.http.websocket;

import ameba.websocket.WebSocketException;
import org.glassfish.jersey.internal.util.Producer;
import org.glassfish.jersey.process.internal.RequestContext;
import org.glassfish.jersey.process.internal.RequestScope;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * @author icode
 */
public class Scopes {
    private Scopes() {
    }

    public static Proxy wrap(RequestScope scope) {
        return new Proxy(scope);
    }

    public static class Proxy extends RequestScope {
        private static MethodHandle retrieveCurrent;
        private static MethodHandle activate;
        private static MethodHandle resume;
        private static MethodHandle release;
        private static MethodHandle suspend;

        static {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            try {
                // retrieveCurrent
                Method mRetrieveCurrent = RequestScope.class.getDeclaredMethod("retrieveCurrent");
                mRetrieveCurrent.setAccessible(true);
                retrieveCurrent = lookup.unreflect(mRetrieveCurrent);

                // activate
                Method mActivate = RequestScope.class.getDeclaredMethod(
                        "activate", RequestContext.class, RequestContext.class);
                mActivate.setAccessible(true);
                activate = lookup.unreflect(mActivate);

                // resume
                Method mResume = RequestScope.class.getDeclaredMethod("resume", RequestContext.class);
                mResume.setAccessible(true);
                resume = lookup.unreflect(mResume);

                // release
                Method mRelease = RequestScope.class.getDeclaredMethod("release", RequestContext.class);
                mRelease.setAccessible(true);
                release = lookup.unreflect(mRelease);

                // suspend
                Method mSuspend = RequestScope.class.getDeclaredMethod("suspend", RequestContext.class);
                mSuspend.setAccessible(true);
                suspend = lookup.unreflect(mSuspend);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new WebSocketException("can not call method", e);
            }
        }

        private RequestScope scope;

        public Proxy(RequestScope scope) {
            this.scope = scope;
        }

        @Override
        public RequestContext createContext() {
            return scope.createContext();
        }

        public RequestContext retrieveCurrent() {
            try {
                return (RequestContext) retrieveCurrent.invoke(scope);
            } catch (Throwable throwable) {
                throw new WebSocketException(throwable);
            }
        }

        @Override
        public void activate(RequestContext context, RequestContext oldContext) {
            try {
                activate.invoke(scope, context, oldContext);
            } catch (Throwable throwable) {
                throw new WebSocketException(throwable);
            }
        }

        @Override
        public void resume(RequestContext context) {
            try {
                resume.invoke(scope, context);
            } catch (Throwable throwable) {
                throw new WebSocketException(throwable);
            }
        }

        @Override
        public void release(RequestContext context) {
            try {
                release.invoke(scope, context);
            } catch (Throwable throwable) {
                throw new WebSocketException(throwable);
            }
        }

        @Override
        public void suspend(RequestContext context) {
            try {
                suspend.invoke(scope, context);
            } catch (Throwable throwable) {
                throw new WebSocketException(throwable);
            }
        }

        @Override
        public void runInScope(RequestContext context, Runnable task) {
            scope.runInScope(context, task);
        }

        @Override
        public void runInScope(Runnable task) {
            scope.runInScope(task);
        }

        @Override
        public <T> T runInScope(RequestContext context, Callable<T> task) throws Exception {
            return scope.runInScope(context, task);
        }

        @Override
        public <T> T runInScope(Callable<T> task) throws Exception {
            return scope.runInScope(task);
        }

        @Override
        public <T> T runInScope(RequestContext context, Producer<T> task) {
            return scope.runInScope(context, task);
        }

        @Override
        public <T> T runInScope(Producer<T> task) {
            return scope.runInScope(task);
        }

        @Override
        public boolean isActive() {
            return scope.isActive();
        }

        @Override
        public void shutdown() {
            scope.shutdown();
        }

        @Override
        public RequestContext referenceCurrent() throws IllegalStateException {
            return scope.referenceCurrent();
        }

        @Override
        public RequestContext current() {
            return scope.current();
        }

        @Override
        public RequestContext suspendCurrent() {
            return scope.suspendCurrent();
        }
    }
}
