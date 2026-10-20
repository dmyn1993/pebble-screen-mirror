package com.pebblemirror.companion.pebble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pebblemirror.companion.protocol.WatchValue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class LegacyPebbleLinkTest {
    @Test
    public void jsonUsesPebbleKitClassicShape() throws Exception {
        Map<Integer, WatchValue> message = new HashMap<>();
        message.put(1, WatchValue.uint(1, 2));
        message.put(7, WatchValue.bytes(new byte[]{0x01, 0x02, (byte) 0xff}));
        message.put(15, WatchValue.text("hello"));

        JSONArray array = new JSONArray(LegacyPebbleLink.encodeJson(message));
        assertEquals(3, array.length());

        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            int key = object.getInt("key");
            if (key == 1) {
                assertEquals("uint", object.getString("type"));
                assertEquals(1, object.getInt("length"));
                assertEquals(2, object.getInt("value"));
            } else if (key == 7) {
                assertEquals("bytes", object.getString("type"));
                assertEquals(0, object.getInt("length"));
            } else if (key == 15) {
                assertEquals("string", object.getString("type"));
                assertEquals("hello", object.getString("value"));
            }
        }
    }

    @Test
    public void decodeRoundTripsAllSupportedTypes() throws Exception {
        Map<Integer, WatchValue> message = new HashMap<>();
        message.put(1, WatchValue.uint(1, 7));
        message.put(2, WatchValue.uint(2, 513));
        message.put(3, WatchValue.sint(4, -12));
        message.put(4, WatchValue.bytes(new byte[]{1, 2, 3, 4}));
        message.put(5, WatchValue.text("watch"));

        String json = LegacyPebbleLink.encodeJson(message);
        Map<Integer, WatchValue> decoded = LegacyPebbleLink.decodeJson(json);

        assertEquals(7, decoded.get(1).number);
        assertEquals(513, decoded.get(2).number);
        assertEquals(-12, decoded.get(3).number);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded.get(4).bytes);
        assertEquals("watch", decoded.get(5).text);
        assertTrue(decoded.get(1).type == WatchValue.TYPE_UINT);
    }
}
