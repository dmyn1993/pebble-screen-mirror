package com.pebblemirror.companion.inject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TouchGestureTest {
    @Test
    public void shortStillTouchIsATap() {
        TouchGesture gesture = new TouchGesture();
        gesture.down(100, 100, 1000);
        gesture.move(101, 101);
        TouchGesture.Result result = gesture.up(101, 101, 1100);

        assertEquals(TouchGesture.TYPE_TAP, result.type);
        assertEquals(100, result.x1);
    }

    @Test
    public void stillLongTouchIsALongPress() {
        TouchGesture gesture = new TouchGesture();
        gesture.down(50, 60, 1000);
        TouchGesture.Result result = gesture.up(50, 60, 1800);

        assertEquals(TouchGesture.TYPE_LONG_PRESS, result.type);
        assertEquals(800, result.durationMs);
    }

    @Test
    public void movingTouchIsASwipe() {
        TouchGesture gesture = new TouchGesture();
        gesture.down(20, 200, 1000);
        gesture.move(20, 150);
        TouchGesture.Result result = gesture.up(20, 100, 1300);

        assertEquals(TouchGesture.TYPE_SWIPE, result.type);
        assertEquals(20, result.x2);
        assertEquals(100, result.y2);
    }

    @Test
    public void upWithoutDownIsIgnored() {
        assertNull(new TouchGesture().up(1, 2, 3));
    }

    @Test
    public void watchDurationOverridesSlowAppMessageTransport() {
        TouchGesture gesture = new TouchGesture();
        gesture.down(100, 100, 1000);
        // The DOWN/UP messages can arrive seconds apart when a frame is on the
        // wire; the watch says the finger was only down for 120 ms.
        TouchGesture.Result result = gesture.up(101, 101, 5000, 120);

        assertEquals(TouchGesture.TYPE_TAP, result.type);
        assertEquals(120, result.durationMs);
    }

    @Test
    public void watchDurationDrivesLongPress() {
        TouchGesture gesture = new TouchGesture();
        gesture.down(50, 60, 1000);
        TouchGesture.Result result = gesture.up(50, 60, 5000, 800);

        assertEquals(TouchGesture.TYPE_LONG_PRESS, result.type);
        assertEquals(800, result.durationMs);
    }

    @Test
    public void resetDiscardsPendingDown() {
        TouchGesture gesture = new TouchGesture();
        gesture.down(10, 10, 1000);
        gesture.reset();
        assertNull(gesture.up(10, 10, 1100));
    }
}
