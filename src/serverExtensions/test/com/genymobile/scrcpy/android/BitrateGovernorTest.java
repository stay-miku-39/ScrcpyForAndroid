package com.genymobile.scrcpy.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class BitrateGovernorTest {
    @Test public void excessOutputLowersTargetWithoutWaitingOrDroppingFrames() {
        BitrateGovernor governor=new BitrateGovernor(1000000,1000000,0);
        for(int ms=0;ms<1000;ms+=100) assertEquals(0,governor.frame(25000,ms));
        int lower=governor.frame(25000,1000);
        assertEquals(425000,lower); assertEquals(2000000,governor.measuredBitrate());
        assertEquals(0,governor.frame(100000,1100)); // updates bounded to once/second
    }
    @Test public void staticSceneRecoversSlowlyAndNeverExceedsOriginalTarget() {
        BitrateGovernor governor=new BitrateGovernor(1000000,1000000,0);
        for(int ms=0;ms<1000;ms+=100) governor.frame(25000,ms);
        int last=governor.frame(25000,1000); assertTrue(last<1000000);
        for(int ms=1100;ms<65000;ms+=100) {
            int next=governor.frame(0,ms); if(next!=0) { assertTrue(next>=last); assertTrue(next<=1000000); last=next; }
        }
        assertEquals(1000000,last);
    }
    @Test public void staleBucketsDoNotPenalizeNextSceneAndArithmeticDoesNotOverflow() {
        BitrateGovernor governor=new BitrateGovernor(100000000,100000000,0);
        governor.frame(Integer.MAX_VALUE,0);
        assertEquals(0,governor.frame(100,10000)); assertEquals(800,governor.measuredBitrate());
        int next=governor.frame(Integer.MAX_VALUE,11000); assertTrue(next>=100000 && next<1000000);
    }
}
