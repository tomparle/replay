package play.mvc;

import org.junit.Before;
import org.junit.Test;
import play.PlayBuilder;
import play.data.binding.CachedBoundActionMethodArgs;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.mvc.Scope.Session;
import play.mvc.results.Forbidden;
import play.mvc.results.Result;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static play.mvc.ActionInvokerTest.TestInterceptor.aftersCounter;
import static play.mvc.ActionInvokerTest.TestInterceptor.beforesCounter;
import static play.mvc.Scope.Session.UA_KEY;

public class ActionInvokerTest {
    private final Object[] noArgs = new Object[0];
    private final Http.Request request = new Http.Request();
    private final Session session = new Session();
    private final SessionStore sessionStore = mock(SessionStore.class);
    private final ActionInvoker invoker = new ActionInvoker(sessionStore);

    @Before
    public void setUp() {
        new PlayBuilder().build();
        Http.Request.removeCurrent();
        CachedBoundActionMethodArgs.init();
        beforesCounter = 0;
        aftersCounter = 0;
    }

    @org.junit.After
    public void tearDown() {
        CachedBoundActionMethodArgs.clear();
    }

    @Test
    public void invokeStaticJavaMethod() throws Exception {
        request.controllerClass = TestController.class;
        assertEquals("static", ActionInvoker.invokeControllerMethod(request, session, TestController.class.getMethod("staticJavaMethod"), noArgs));
    }

    @Test
    public void invokeNonStaticJavaMethod() throws Exception {
        request.controllerClass = TestController.class;
        request.controllerInstance = new TestController();

        assertEquals("non-static-parent", ActionInvoker.invokeControllerMethod(request, session, TestController.class.getMethod("nonStaticJavaMethod"), noArgs));
    }

    @Test
    public void invokeNonStaticJavaMethodInChildController() throws Exception {
        request.controllerClass = TestChildController.class;
        request.controllerInstance = new TestChildController();

        assertEquals("non-static-child", ActionInvoker.invokeControllerMethod(request, session, TestChildController.class.getMethod("nonStaticJavaMethod"), noArgs));
    }

    @Test
    public void invokeNonStaticJavaMethodWithNonStaticWith() throws Exception {
        request.controllerClass = TestControllerWithWith.class;
        request.controllerInstance = new TestControllerWithWith();
        executeMethod("handleBefores", request, session);
        assertEquals("non-static", ActionInvoker.invokeControllerMethod(request, session, TestControllerWithWith.class.getMethod("nonStaticJavaMethod")));
        executeMethod("handleAfters", request, session);
        assertEquals(1, beforesCounter);
        assertEquals(1, aftersCounter);
    }

    private void executeMethod(String methodName, Http.Request request, Session session) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Method method = ActionInvoker.class.getDeclaredMethod(methodName, Http.Request.class, Session.class);
        method.setAccessible(true);
        method.invoke(null, request, session);
    }

    @Test
    public void controllerInstanceIsPreservedForAllControllerMethodInvocations() throws Exception {
        request.controllerClass = FullCycleTestController.class;
        request.controllerInstance = new FullCycleTestController();

        Controller controllerInstance = (Controller) ActionInvoker.invokeControllerMethod(request, session, FullCycleTestController.class.getMethod("before"), noArgs);
        assertSame(controllerInstance, request.controllerInstance);

        controllerInstance = (Controller) ActionInvoker.invokeControllerMethod(request, session, FullCycleTestController.class.getMethod("action"), noArgs);
        assertSame(controllerInstance, request.controllerInstance);

        controllerInstance = (Controller) ActionInvoker.invokeControllerMethod(request, session, FullCycleTestController.class.getMethod("after"), noArgs);
        assertSame(controllerInstance, request.controllerInstance);
    }

    @Test
    public void invocationUnwrapsPlayException() throws Exception {
        final UnexpectedException exception = new UnexpectedException("unexpected");

        class AController extends Controller {
            public void action() {
                throw exception;
            }
        }

        try {
            ActionInvoker.invoke(AController.class.getMethod("action"), new AController());
            fail();
        }
        catch (PlayException e) {
            assertEquals(exception, e);
        }
    }

    @Test
    public void invocationUnwrapsResult() throws Exception {
        final Result result = new Forbidden("unexpected");

        class AController extends Controller {
            public void action() {
                throw result;
            }
        }

        try {
            ActionInvoker.invoke(AController.class.getMethod("action"), new AController());
            fail();
        }
        catch (Result e) {
            assertEquals(result, e);
        }
    }

    @Test
    public void testFindActionMethod() throws Exception {
        assertNull(ActionInvoker.findActionMethod("notExistingMethod", ActionClass.class));

        ensureNotActionMethod("privateMethod");
        ensureNotActionMethod("beforeMethod");
        ensureNotActionMethod("afterMethod");
        ensureNotActionMethod("utilMethod");
        ensureNotActionMethod("catchMethod");
        ensureNotActionMethod("finallyMethod");

        Method m = ActionInvoker.findActionMethod("actionMethod", ActionClass.class);
        assertNotNull(m);
        assertEquals("actionMethod", m.invoke( new ActionClass()));

        //test that it works with subclassing
        m = ActionInvoker.findActionMethod("actionMethod", ActionClassChild.class);
        assertNotNull(m);
        assertEquals("actionMethod", m.invoke( new ActionClassChild()));
    }

    @Test
    public void sessionSave_storesUserAgentInSession() {
        Session session = new Session();
        session.put("hello", "world");
        request.setHeader("User-Agent", "Android; Windows Phone");

        invoker.saveSession(session, request, new Http.Response());

        assertEquals("Android; Windows Phone", session.get(UA_KEY));
    }

    @Test
    public void sessionSave_doesNotStoreUserAgent_ifSessionIsEmpty() {
        Session session = new Session();
        request.setHeader("User-Agent", "Android; Windows Phone");

        invoker.saveSession(session, request, new Http.Response());

        assertThat(session.get(UA_KEY)).isNull();
    }

    @Test
    public void sessionSave_withoutUserAgent() {
        Session session = new Session();
        session.put("hello", "world");

        invoker.saveSession(session, request, new Http.Response());

        assertEquals("n/a", session.get(UA_KEY));
    }

    @Test
    public void sessionSave_doesNotAddUserAgentIfAlreadyPresent() {
        Session session = spy(new Session());
        session.put(UA_KEY, "Chrome;");
        request.setHeader("User-Agent", "Chrome;");

        invoker.saveSession(session, request, new Http.Response());

        verify(session, times(1)).put(eq(UA_KEY), anyString());
    }

    @Test
    public void restore() {
        Http.Response response = new Http.Response();
        Session session = spy(new Session());
        session.put(UA_KEY, "Chrome;");
        request.setHeader("User-Agent", "Chrome;");
        when(sessionStore.restore(request)).thenReturn(session);

        assertThat(invoker.restoreSession(request)).isSameAs(session);

        verify(session, never()).clear();
        verify(sessionStore, never()).save(session, request, response);
    }

    @Test
    public void restore_skipsCheckForUserAgent_ifUserAgentNotStoredYet() {
        Session session = new Session();
        request.setHeader("User-Agent", "Chrome;");
        when(sessionStore.restore(request)).thenReturn(session);

        assertThat(invoker.restoreSession(request)).isSameAs(session);
    }

    private void ensureNotActionMethod(String name) throws NoSuchMethodException {
        assertNull(ActionInvoker.findActionMethod(ActionClass.class.getDeclaredMethod(name).getName(), ActionClass.class));
    }

    public static class TestController extends Controller {
        public static String staticJavaMethod() {
            return "static";
        }

        public String nonStaticJavaMethod() {
            return "non-static-" + getPrefix();
        }

        protected String getPrefix() {
            return "parent";
        }
    }

    public static class TestChildController extends TestController {
        @Override
        protected String getPrefix() {
            return "child";
        }
    }

    public static class FullCycleTestController extends Controller {
        @play.mvc.Before  public Controller before() {
            return this;
        }

        public Controller action() {
            return this;
        }

        @play.mvc.After public Controller after() {
            return this;
        }
    }

    private static class ActionClass {

        private static String privateMethod() {
            return "private";
        }


        public static String actionMethod() {
            return "actionMethod";
        }

        @play.mvc.Before
        public static String beforeMethod() {
            return "before";
        }

        @After
        public static String afterMethod() {
            return "after";
        }

        @Util
        public static void utilMethod() {
        }

        @Catch
        public static void catchMethod() {
        }

        @Finally
        public static String finallyMethod() {
            return "finally";
        }

    }

    private static class ActionClassChild extends ActionClass {
    }

    @With(TestInterceptor.class)
    public static class TestControllerWithWith extends Controller {
        public String nonStaticJavaMethod() {
            return "non-static";
        }
    }
    
    public static class TestInterceptor extends Controller {
        static int beforesCounter, aftersCounter;
        
        @play.mvc.Before
        public void beforeMethod() {beforesCounter++;}

        @After
        public void afterMethod() {aftersCounter++;}
    }
}
