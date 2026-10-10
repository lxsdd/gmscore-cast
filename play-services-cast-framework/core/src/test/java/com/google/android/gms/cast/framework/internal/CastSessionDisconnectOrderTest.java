package com.google.android.gms.cast.framework.internal;

import android.os.IBinder;
import android.os.RemoteException;

import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.ICastConnectionController;
import com.google.android.gms.dynamic.ObjectWrapper;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CastSessionDisconnectOrderTest {
    @Test
    public void stopCastingStopsReceiverBeforeTransportCloseExactlyOnce() throws Exception {
        List<String> calls = new ArrayList<>();
        CastSessionImpl session = castSession(controller(calls, false));
        setReceiverSessionId(session, "receiver-session");

        session.disconnectFromDevice(true, 2);

        assertEquals(2, calls.size());
        assertEquals("stop", calls.get(0));
        assertEquals("close:2", calls.get(1));
    }

    @Test
    public void leaveClosesTransportWithoutStoppingReceiver() throws Exception {
        List<String> calls = new ArrayList<>();
        CastSessionImpl session = castSession(controller(calls, false));
        setReceiverSessionId(session, "receiver-session");

        session.disconnectFromDevice(false, 0);

        assertEquals(1, calls.size());
        assertEquals("close:0", calls.get(0));
    }

    @Test
    public void transportStillClosesWhenStopApplicationFails() throws Exception {
        List<String> calls = new ArrayList<>();
        CastSessionImpl session = castSession(controller(calls, true));
        setReceiverSessionId(session, "receiver-session");

        try {
            session.disconnectFromDevice(true, 2);
        } catch (RemoteException expected) {
            // The ordering contract still requires the finally block to close the transport.
        }

        assertEquals(2, calls.size());
        assertEquals("stop", calls.get(0));
        assertEquals("close:2", calls.get(1));
    }

    private static CastSessionImpl castSession(ICastConnectionController controller)
            throws Exception {
        SessionImpl wrapped = new SessionImpl("category", null, null);
        return new CastSessionImpl(new CastOptions(), ObjectWrapper.wrap(wrapped), controller);
    }

    private static void setReceiverSessionId(CastSessionImpl session, String value)
            throws Exception {
        Field field = CastSessionImpl.class.getDeclaredField("receiverSessionId");
        field.setAccessible(true);
        field.set(session, value);
    }

    private static ICastConnectionController controller(List<String> calls, boolean failStop) {
        return (ICastConnectionController) Proxy.newProxyInstance(
                CastSessionDisconnectOrderTest.class.getClassLoader(),
                new Class<?>[]{ICastConnectionController.class},
                (proxy, method, args) -> {
                    if ("stopApplication".equals(method.getName())) {
                        calls.add("stop");
                        if (failStop) throw new RemoteException("fixture stop failure");
                    }
                    if ("closeConnection".equals(method.getName())) {
                        calls.add("close:" + args[0]);
                    }
                    if ("getSupportedVersion".equals(method.getName())) return 0;
                    if ("asBinder".equals(method.getName())) return (IBinder) null;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
    }
}
