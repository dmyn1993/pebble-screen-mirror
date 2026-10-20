package com.pebblemirror.companion.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pebblemirror.companion.protocol.WatchProtocol;
import com.pebblemirror.companion.protocol.WatchValue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Verifies that a full emery keyframe fits in one 8K AppMessage and chunks safely. */
public class FrameMessagesTest {
    private static FrameEncoder.TileFrame frame(int tiles, boolean keyframe) {
        List<FrameEncoder.TileRecord> records = new ArrayList<>();
        for (int i = 0; i < tiles; i++) {
            records.add(new FrameEncoder.TileRecord(i, new byte[8]));
        }
        return new FrameEncoder.TileFrame(7, 200, 228, 206, 0, keyframe, records);
    }

    @Test
    public void fullFrameFitsOneEightKilobyteMessage() {
        List<Map<Integer, WatchValue>> messages =
                WatchProtocol.frameMessages(frame(725, true), 8200);
        assertEquals(1, messages.size());
        Map<Integer, WatchValue> message = messages.get(0);
        assertEquals(0, WatchProtocol.intValue(message, WatchProtocol.KEY_TILE_START, -1));
        assertEquals(725, WatchProtocol.intValue(message, WatchProtocol.KEY_TILE_COUNT, -1));
        assertEquals(WatchProtocol.FRAME_FINAL | WatchProtocol.FRAME_KEYFRAME,
                WatchProtocol.intValue(message, WatchProtocol.KEY_FLAGS, -1));
        assertEquals(7250, message.get(WatchProtocol.KEY_DATA).bytes.length);
    }

    @Test
    public void smallInboxChunksInOrderWithFinalAndKeyframeBits() {
        List<Map<Integer, WatchValue>> messages =
                WatchProtocol.frameMessages(frame(725, true), 3000);
        assertTrue(messages.size() > 1);
        int expectedStart = 0;
        int totalTiles = 0;
        for (int i = 0; i < messages.size(); i++) {
            Map<Integer, WatchValue> message = messages.get(i);
            int start = WatchProtocol.intValue(message, WatchProtocol.KEY_TILE_START, -1);
            int count = WatchProtocol.intValue(message, WatchProtocol.KEY_TILE_COUNT, -1);
            int flags = WatchProtocol.intValue(message, WatchProtocol.KEY_FLAGS, -1);
            assertEquals(expectedStart, start);
            assertEquals(i == 0, (flags & WatchProtocol.FRAME_KEYFRAME) != 0);
            assertEquals(i == messages.size() - 1, (flags & WatchProtocol.FRAME_FINAL) != 0);
            expectedStart += count;
            totalTiles += count;
        }
        assertEquals(725, totalTiles);
    }

    @Test
    public void deltaFrameDoesNotSetKeyframeBit() {
        List<Map<Integer, WatchValue>> messages =
                WatchProtocol.frameMessages(frame(3, false), 8200);
        assertEquals(1, messages.size());
        int flags = WatchProtocol.intValue(messages.get(0), WatchProtocol.KEY_FLAGS, -1);
        assertEquals(WatchProtocol.FRAME_FINAL, flags);
    }
}
