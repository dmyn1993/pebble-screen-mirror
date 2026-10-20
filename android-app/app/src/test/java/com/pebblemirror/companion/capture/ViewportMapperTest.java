package com.pebblemirror.companion.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.pebblemirror.companion.protocol.WatchProtocol;

import org.junit.Test;

public class ViewportMapperTest {
    @Test
    public void viewportUsesFullWidthAndPansVertically() {
        ViewportMapper.Scale geometry = ViewportMapper.compute(
                1080, 2400, 200, 228, WatchProtocol.MODE_VIEWPORT);

        assertEquals(200, geometry.scaledWidth);
        assertEquals(444, geometry.scaledHeight);
        assertEquals(216, geometry.maxOffset);
        assertEquals(0, geometry.letterboxX);

        int[] point = ViewportMapper.mapToPhone(100, 100, geometry, 50);
        assertArrayEquals(new int[]{540, 810}, point);
    }

    @Test
    public void fitModeCentersAndLetterboxes() {
        ViewportMapper.Scale geometry = ViewportMapper.compute(
                1080, 2400, 200, 228, WatchProtocol.MODE_FIT);

        assertEquals(103, geometry.scaledWidth);
        assertEquals(228, geometry.scaledHeight);
        assertEquals(0, geometry.maxOffset);
        assertEquals(48, geometry.letterboxX);

        int[] point = ViewportMapper.mapToPhone(100, 114, geometry, 0);
        assertArrayEquals(new int[]{547, 1200}, point);
    }

    @Test
    public void offsetIsClamped() {
        assertEquals(0, ViewportMapper.clampOffset(-20, 216));
        assertEquals(216, ViewportMapper.clampOffset(999, 216));
        assertEquals(42, ViewportMapper.clampOffset(42, 216));
    }
}
