package com.pebblemirror.companion.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;



import com.pebblemirror.companion.protocol.WatchProtocol;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import com.pebblemirror.companion.protocol.WatchValue;

public class FrameEncoderTest {
    private static int[] solid(int width, int height, int color) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = color;
        }
        return pixels;
    }

    @Test
    public void viewportUsesLsbFirstTileBits() {
        int[] pixels = solid(200, 444, 0xFFFFFFFF);
        pixels[0] = 0xFF000000;

        FrameEncoder encoder = new FrameEncoder();
        FrameEncoder.TileFrame frame = encoder.encode(pixels, 200, 444,
                200, 228, WatchProtocol.MODE_VIEWPORT, 0, 216, true,
                FrameEncoder.Dither.THRESHOLD);

        assertTrue(frame.keyframe);
        assertEquals(725, frame.tiles.size());
        FrameEncoder.TileRecord first = frame.tiles.get(0);
        assertEquals(0, first.index);
        assertEquals((byte) 0xFE, first.data[0]);
        assertEquals((byte) 0xFF, first.data[1]);
    }

    @Test
    public void unchangedFrameProducesNoTiles() {
        int[] pixels = solid(200, 444, 0xFFFFFFFF);
        FrameEncoder encoder = new FrameEncoder();
        encoder.encode(pixels, 200, 444, 200, 228,
                WatchProtocol.MODE_VIEWPORT, 0, 216, true,
                FrameEncoder.Dither.THRESHOLD);

        FrameEncoder.TileFrame second = encoder.encode(pixels, 200, 444,
                200, 228, WatchProtocol.MODE_VIEWPORT, 0, 216, false,
                FrameEncoder.Dither.THRESHOLD);
        assertFalse(second.keyframe);
        assertTrue(second.tiles.isEmpty());
    }

    @Test
    public void changedTileIsSentAsDelta() {
        int[] pixels = solid(200, 444, 0xFFFFFFFF);
        FrameEncoder encoder = new FrameEncoder();
        encoder.encode(pixels, 200, 444, 200, 228,
                WatchProtocol.MODE_VIEWPORT, 0, 216, true,
                FrameEncoder.Dither.THRESHOLD);

        pixels[5] = 0xFF000000;
        FrameEncoder.TileFrame delta = encoder.encode(pixels, 200, 444,
                200, 228, WatchProtocol.MODE_VIEWPORT, 0, 216, false,
                FrameEncoder.Dither.THRESHOLD);

        assertFalse(delta.keyframe);
        assertEquals(1, delta.tiles.size());
        assertEquals(0, delta.tiles.get(0).index);
        assertEquals((byte) 0xDF, delta.tiles.get(0).data[0]);
    }

    @Test
    public void fitModeLetterboxes() {
        int[] pixels = solid(100, 100, 0xFFFFFFFF);
        FrameEncoder encoder = new FrameEncoder();
        FrameEncoder.TileFrame frame = encoder.encode(pixels, 100, 100,
                200, 228, WatchProtocol.MODE_FIT, 0, 0, true,
                FrameEncoder.Dither.THRESHOLD);

        assertEquals(0, frame.tiles.get(0).data[0] & 0xff);
        int centerTile = 14 * 25 + 12;
        for (FrameEncoder.TileRecord record : frame.tiles) {
            if (record.index == centerTile) {
                for (byte value : record.data) {
                    assertEquals((byte) 0xFF, value);
                }
                return;
            }
        }
        throw new AssertionError("center tile missing");
    }

    @Test
    public void fullFrameFitsInOneEightKilobyteMessage() {
        int[] pixels = solid(200, 444, 0xFFFFFFFF);
        FrameEncoder encoder = new FrameEncoder();
        FrameEncoder.TileFrame frame = encoder.encode(pixels, 200, 444,
                200, 228, WatchProtocol.MODE_VIEWPORT, 0, 216, true,
                FrameEncoder.Dither.THRESHOLD);

        List<Map<Integer, WatchValue>> messages =
                WatchProtocol.frameMessages(frame, 8192);
        assertEquals(1, messages.size());

        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            Map<Integer, WatchValue> message = messages.get(i);
            int count = WatchProtocol.intValue(message, WatchProtocol.KEY_TILE_COUNT, 0);
            int flags = WatchProtocol.intValue(message, WatchProtocol.KEY_FLAGS, 0);
            byte[] data = WatchProtocol.bytesValue(message, WatchProtocol.KEY_DATA);
            total += count;
            assertEquals(count * 10, data.length);
            if (i == 0) {
                assertTrue((flags & WatchProtocol.FRAME_KEYFRAME) != 0);
            }
            if (i == messages.size() - 1) {
                assertTrue((flags & WatchProtocol.FRAME_FINAL) != 0);
            }
        }
        assertEquals(725, total);
    }
}
