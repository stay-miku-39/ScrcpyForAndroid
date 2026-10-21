package org.scrcpy.android;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class TouchContinuityTest {
    private static byte[] touch(int action,long pointer,int x) { return ControlProtocol.touch(action,pointer,x,10,200,400,action==1?0:1); }
    @Test public void staleMovementMustNotCancelTwoAlreadyHeldFingers() {
        AtomicLong now=new AtomicLong(); ControlQueue queue=new ControlQueue(64,512*1024,250,now::get); ControlInputState state=new ControlInputState();
        state.sent(touch(0,1,20)); state.sent(touch(0,2,30)); queue.offer(touch(2,2,40)); now.set(350);
        assertNull("An obsolete MOVE must not request release of all held inputs",queue.poll());
        assertEquals(32,state.prepare(touch(2,1,50)).length); assertEquals(32,state.prepare(touch(2,2,60)).length);
    }
    @Test public void delayedFingerUpIsStillDeliveredWithoutReleasingTheOtherFinger() {
        AtomicLong now=new AtomicLong(); ControlQueue queue=new ControlQueue(64,512*1024,250,now::get); ControlInputState state=new ControlInputState();
        state.sent(touch(0,1,20)); state.sent(touch(0,2,30)); byte[] up=touch(1,2,30); queue.offer(up); now.set(350);
        assertArrayEquals("A release must survive temporary missing ADB credit",up,queue.poll()); state.sent(up);
        assertEquals(32,state.prepare(touch(2,1,40)).length); assertEquals(32,state.releases().length);
    }
    @Test public void unsentStaleSecondDownDoesNotCancelFirstFinger() {
        AtomicLong now=new AtomicLong(); ControlQueue queue=new ControlQueue(64,512*1024,250,now::get); ControlInputState state=new ControlInputState();
        state.sent(touch(0,1,20)); queue.offer(touch(0,2,30)); now.set(350);
        assertNull(queue.poll()); assertEquals(32,state.prepare(touch(2,1,40)).length); assertEquals(0,state.prepare(touch(2,2,40)).length);
    }
}
