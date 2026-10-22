#include <pebble.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// ---------------------------------------------------------------------------
// Wire protocol (AppMessage keys are numeric on purpose: the Android companion
// and the watch app are versioned together, so no generated message_keys header
// is needed).
// ---------------------------------------------------------------------------

enum {
  MK_KIND = 1,
  MK_SEQ = 2,
  MK_FRAME = 3,
  MK_TILE_START = 4,
  MK_TILE_COUNT = 5,
  MK_FLAGS = 6,
  MK_DATA = 7,
  MK_CMD = 8,
  MK_X = 9,
  MK_Y = 10,
  MK_WIDTH = 11,
  MK_HEIGHT = 12,
  MK_MAX_OFF = 13,
  MK_MODE = 14,
  MK_TEXT = 15,
  MK_VERSION = 16,
  MK_MAX_INBOX = 17,
  MK_DURATION = 18,
  MK_POINT_COUNT = 19,
};

enum {
  KIND_CONFIG = 1,
  KIND_FRAME = 2,
  KIND_STATUS = 3,

  KIND_HELLO = 10,
  KIND_TOUCH = 11,
  KIND_PAN = 12,
  KIND_KEY = 13,
  KIND_REQUEST = 14,
  KIND_PATH = 15,
  KIND_MODE = 16,
};

enum {
  FRAME_FINAL = 1 << 0,
  FRAME_KEYFRAME = 1 << 1,
};

enum {
  TOUCH_DOWN = 1,
  TOUCH_MOVE = 2,
  TOUCH_UP = 3,
  TOUCH_TAP = 4,
  TOUCH_LONG = 5,
};

enum {
  KEY_BACK = 1,
  KEY_HOME = 2,
  KEY_RECENTS = 3,
};

enum {
  MODE_VIEWPORT = 0,
  MODE_FIT = 1,
};

#define PROTO_VERSION 1
#define TILE_BYTES 8
#define TILE_RECORD_BYTES 10
#define PAN_STEP 24
#define CONNECTION_TIMEOUT_MS 9000

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

static Window *s_window;
static Layer *s_canvas;

static GBitmap *s_display;
static GBitmap *s_staging;
static int s_width;
static int s_height;
static int s_stride;
static int s_tiles_x;
static int s_tiles_y;

static bool s_have_frame;
static bool s_connected;
static bool s_mirroring;
static int s_max_off;
static int s_view_off;
static int s_mode;

static uint16_t s_frame_id;
static uint16_t s_last_frame_id;
static bool s_frame_open;
static uint32_t s_last_rx_ms;
static char s_status_text[80];

static bool s_touch_enabled;
static bool s_touch_subscribed;
static bool s_touch_active;
static bool s_touch_moved;
static int s_touch_start_x;
static int s_touch_start_y;
static uint32_t s_touch_down_ms;
static bool s_pending_touch;
static uint8_t s_pending_action;
static int s_pending_up_x;
static int s_pending_up_y;
static uint16_t s_pending_duration;
static AppTimer *s_touch_retry_timer;

#define MAX_PATH_POINTS 256
static int16_t s_path_x[MAX_PATH_POINTS];
static int16_t s_path_y[MAX_PATH_POINTS];
static uint16_t s_path_count;
static uint8_t s_path_buffer[MAX_PATH_POINTS * 4];
static uint16_t s_path_duration_ms;
static bool s_pending_path;
static AppTimer *s_path_retry_timer;
static AppTimer *s_timeout_timer;
static int s_heartbeat_ticks;
static uint16_t s_session_id;

static GFont s_font_title;
static GFont s_font_body;
static GFont s_font_small;

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

static int iabs(int v) { return v < 0 ? -v : v; }

static uint32_t now_ms(void) {
  // time_ms() only returns the 0..999 millisecond part of the current second;
  // using it directly as a monotonic clock wraps every second and turns a
  // quick gesture into a huge duration. Combine seconds + ms and let uint32
  // arithmetic handle the ~49-day wrap.
  time_t seconds = 0;
  uint16_t millis = time_ms(&seconds, NULL);
  return (uint32_t)seconds * 1000u + millis;
}

static int tuple_int(DictionaryIterator *iter, uint32_t key, int fallback) {
  Tuple *tuple = dict_find(iter, key);
  if (!tuple) {
    return fallback;
  }
  if (tuple->type == TUPLE_UINT) {
    switch (tuple->length) {
      case 1:
        return tuple->value->uint8;
      case 2:
        return tuple->value->uint16;
      case 4:
        return (int)tuple->value->uint32;
      default:
        return fallback;
    }
  }
  if (tuple->type == TUPLE_INT) {
    switch (tuple->length) {
      case 1:
        return tuple->value->int8;
      case 2:
        return tuple->value->int16;
      case 4:
        return tuple->value->int32;
      default:
        return fallback;
    }
  }
  return fallback;
}

static void copy_status_text(const char *text) {
  if (!text) {
    s_status_text[0] = '\0';
    return;
  }
  strncpy(s_status_text, text, sizeof(s_status_text) - 1);
  s_status_text[sizeof(s_status_text) - 1] = '\0';
}

// ---------------------------------------------------------------------------
// Bitmaps and tiles
// ---------------------------------------------------------------------------

static int tile_count_total(void) { return s_tiles_x * s_tiles_y; }

static bool ensure_bitmaps(int width, int height) {
  if (width <= 0 || height <= 0) {
    return false;
  }
  if (s_display && s_staging && s_width == width && s_height == height) {
    return true;
  }

  if (s_display) {
    gbitmap_destroy(s_display);
    s_display = NULL;
  }
  if (s_staging) {
    gbitmap_destroy(s_staging);
    s_staging = NULL;
  }

  s_display = gbitmap_create_blank(GSize(width, height), GBitmapFormat1Bit);
  s_staging = gbitmap_create_blank(GSize(width, height), GBitmapFormat1Bit);
  if (!s_display || !s_staging) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Unable to allocate %dx%d 1-bit bitmaps", width, height);
    if (s_display) {
      gbitmap_destroy(s_display);
      s_display = NULL;
    }
    if (s_staging) {
      gbitmap_destroy(s_staging);
      s_staging = NULL;
    }
    return false;
  }

  s_width = width;
  s_height = height;
  s_stride = gbitmap_get_bytes_per_row(s_display);
  s_tiles_x = (width + TILE_BYTES - 1) / TILE_BYTES;
  s_tiles_y = (height + TILE_BYTES - 1) / TILE_BYTES;
  s_have_frame = false;
  s_frame_open = false;
  s_view_off = 0;
  s_frame_id = 0;
  s_last_frame_id = 0;

  APP_LOG(APP_LOG_LEVEL_INFO, "Bitmap %dx%d stride=%d tiles=%dx%d total=%d",
          width, height, s_stride, s_tiles_x, s_tiles_y, tile_count_total());
  return true;
}

static void apply_tile(uint16_t index, const uint8_t *tile) {
  if (!s_staging || index >= tile_count_total()) {
    return;
  }

  int tx = index % s_tiles_x;
  int ty = index / s_tiles_x;
  uint8_t *base = gbitmap_get_data(s_staging);

  for (int row = 0; row < TILE_BYTES; row++) {
    int y = ty * TILE_BYTES + row;
    if (y >= s_height) {
      break;
    }
    base[y * s_stride + tx] = tile[row];
  }
}

static void begin_frame(uint16_t frame_id, bool keyframe) {
  s_frame_id = frame_id;
  s_frame_open = true;

  uint8_t *staging = gbitmap_get_data(s_staging);
  if (keyframe) {
    memset(staging, 0, (size_t)s_stride * s_height);
  } else {
    memcpy(staging, gbitmap_get_data(s_display), (size_t)s_stride * s_height);
  }
}

// ---------------------------------------------------------------------------
// Outgoing messages
// ---------------------------------------------------------------------------

static void send_hello(void) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }

  dict_write_uint8(iter, MK_KIND, KIND_HELLO);
  dict_write_uint16(iter, MK_SEQ, s_session_id);
  dict_write_uint16(iter, MK_WIDTH, (uint16_t)s_width);
  dict_write_uint16(iter, MK_HEIGHT, (uint16_t)s_height);
  dict_write_uint16(iter, MK_VERSION, PROTO_VERSION);
  dict_write_uint32(iter, MK_MAX_INBOX, app_message_inbox_size_maximum());

  if (app_message_outbox_send() != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "HELLO send failed");
  }
}

static bool send_touch_ex(uint8_t action, int x, int y, uint16_t duration_ms) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return false;
  }

  dict_write_uint8(iter, MK_KIND, KIND_TOUCH);
  dict_write_uint8(iter, MK_CMD, action);
  dict_write_uint16(iter, MK_X, (uint16_t)x);
  dict_write_uint16(iter, MK_Y, (uint16_t)y);
  if (duration_ms > 0) {
    dict_write_uint16(iter, MK_DURATION, duration_ms);
  }
  return app_message_outbox_send() == APP_MSG_OK;
}

static bool send_touch(uint8_t action, int x, int y) {
  return send_touch_ex(action, x, y, 0);
}

static void path_reset(void) {
  s_path_count = 0;
}

static void path_add(int x, int y) {
  if (s_path_count >= MAX_PATH_POINTS) {
    return;
  }
  if (s_path_count > 0
      && s_path_x[s_path_count - 1] == x
      && s_path_y[s_path_count - 1] == y) {
    return;
  }
  s_path_x[s_path_count] = (int16_t)x;
  s_path_y[s_path_count] = (int16_t)y;
  s_path_count++;
}

static bool send_path(void) {
  if (s_path_count < 2) {
    return false;
  }
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return false;
  }

  for (uint16_t i = 0; i < s_path_count; i++) {
    s_path_buffer[i * 4 + 0] = (uint8_t)(s_path_x[i] & 0xff);
    s_path_buffer[i * 4 + 1] = (uint8_t)((s_path_x[i] >> 8) & 0xff);
    s_path_buffer[i * 4 + 2] = (uint8_t)(s_path_y[i] & 0xff);
    s_path_buffer[i * 4 + 3] = (uint8_t)((s_path_y[i] >> 8) & 0xff);
  }
  dict_write_uint8(iter, MK_KIND, KIND_PATH);
  dict_write_uint16(iter, MK_POINT_COUNT, s_path_count);
  dict_write_uint16(iter, MK_DURATION, s_path_duration_ms);
  dict_write_data(iter, MK_DATA, s_path_buffer, (uint16_t)(s_path_count * 4));
  return app_message_outbox_send() == APP_MSG_OK;
}

static void path_retry_callback(void *data) {
  s_path_retry_timer = NULL;
  if (s_pending_path) {
    if (send_path()) {
      s_pending_path = false;
    } else {
      s_path_retry_timer = app_timer_register(120, path_retry_callback, NULL);
    }
  }
}

static void send_pan(int offset) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }

  dict_write_uint8(iter, MK_KIND, KIND_PAN);
  dict_write_uint16(iter, MK_Y, (uint16_t)offset);
  app_message_outbox_send();
}

static void send_key(uint8_t key) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }

  dict_write_uint8(iter, MK_KIND, KIND_KEY);
  dict_write_uint8(iter, MK_CMD, key);
  app_message_outbox_send();
}

static void send_mode(uint8_t mode) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }

  dict_write_uint8(iter, MK_KIND, KIND_MODE);
  dict_write_uint8(iter, MK_CMD, mode);
  app_message_outbox_send();
}

static void request_keyframe(void) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }

  dict_write_uint8(iter, MK_KIND, KIND_REQUEST);
  dict_write_uint8(iter, MK_CMD, 1);
  app_message_outbox_send();
}

static void touch_retry_callback(void *data) {
  s_touch_retry_timer = NULL;
  if (s_pending_touch) {
    if (send_touch_ex(s_pending_action, s_pending_up_x, s_pending_up_y,
                      s_pending_duration)) {
      s_pending_touch = false;
    } else {
      s_touch_retry_timer = app_timer_register(120, touch_retry_callback, NULL);
    }
  }
}

// ---------------------------------------------------------------------------
// Incoming messages
// ---------------------------------------------------------------------------

static void reset_to_splash(void) {
  s_have_frame = false;
  s_frame_open = false;
  s_frame_id = 0;
  s_last_frame_id = 0;
  s_view_off = 0;
  s_touch_active = false;
  s_pending_touch = false;
  s_pending_path = false;
  s_path_count = 0;

  if (s_touch_retry_timer) {
    app_timer_cancel(s_touch_retry_timer);
    s_touch_retry_timer = NULL;
  }
  if (s_path_retry_timer) {
    app_timer_cancel(s_path_retry_timer);
    s_path_retry_timer = NULL;
  }

  if (s_canvas) {
    layer_mark_dirty(s_canvas);
  }
}

static void handle_config(DictionaryIterator *iter) {
  int width = tuple_int(iter, MK_WIDTH, s_width);
  int height = tuple_int(iter, MK_HEIGHT, s_height);
  int max_off = tuple_int(iter, MK_MAX_OFF, 0);
  int mode = tuple_int(iter, MK_MODE, MODE_VIEWPORT);
  int flags = tuple_int(iter, MK_FLAGS, 0);

  if (width > 0 && height > 0 && (width != s_width || height != s_height)) {
    if (!ensure_bitmaps(width, height)) {
      return;
    }
  }

  s_max_off = max_off > 0 ? max_off : 0;
  s_mode = mode == MODE_FIT ? MODE_FIT : MODE_VIEWPORT;
  if (s_view_off > s_max_off) {
    s_view_off = s_max_off;
  }
  s_mirroring = (flags & 1) != 0;
  s_connected = true;
  s_last_rx_ms = now_ms();

  // A config starts a new stream. The phone always follows it with a keyframe.
  s_frame_open = false;
  s_frame_id = 0;
  s_last_frame_id = 0;

  Tuple *text = dict_find(iter, MK_TEXT);
  copy_status_text(text ? text->value->cstring : NULL);

  APP_LOG(APP_LOG_LEVEL_INFO, "CONFIG %dx%d max_off=%d mode=%d flags=%d",
          s_width, s_height, s_max_off, s_mode, flags);
  if (!s_mirroring && s_have_frame) {
    reset_to_splash();
  } else {
    layer_mark_dirty(s_canvas);
  }

  if (s_mirroring) {
    request_keyframe();
  }
}

static void handle_status(DictionaryIterator *iter) {
  int flags = tuple_int(iter, MK_FLAGS, 0);
  Tuple *text = dict_find(iter, MK_TEXT);

  bool was_connected = s_connected;
  bool was_mirroring = s_mirroring;
  char old_status[sizeof(s_status_text)];
  strcpy(old_status, s_status_text);

  s_connected = true;
  s_last_rx_ms = now_ms();
  s_mirroring = (flags & 1) != 0;
  copy_status_text(text ? text->value->cstring : NULL);

  if (!s_mirroring && s_have_frame) {
    reset_to_splash();
  } else if (!was_connected || was_mirroring != s_mirroring
      || strcmp(old_status, s_status_text) != 0) {
    layer_mark_dirty(s_canvas);
  }
}

static void handle_frame(DictionaryIterator *iter) {
  if (!s_display || !s_staging) {
    return;
  }

  int frame_id = tuple_int(iter, MK_FRAME, -1);
  int start = tuple_int(iter, MK_TILE_START, -1);
  int count = tuple_int(iter, MK_TILE_COUNT, 0);
  int flags = tuple_int(iter, MK_FLAGS, 0);
  Tuple *data = dict_find(iter, MK_DATA);

  if (frame_id < 0 || start < 0 || count <= 0 || !data
      || data->type != TUPLE_BYTE_ARRAY) {
    return;
  }
  if (data->length < (uint32_t)(count * TILE_RECORD_BYTES)) {
    APP_LOG(APP_LOG_LEVEL_WARNING, "Short frame payload: %d bytes for %d tiles",
            (int)data->length, count);
    return;
  }

  uint16_t frame = (uint16_t)frame_id;
  s_connected = true;
  s_last_rx_ms = now_ms();
  if (frame != s_frame_id || !s_frame_open) {
    if (start != 0) {
      // The first chunk of this frame was lost; applying the rest would leave
      // stale tiles on screen. Ask the phone for a fresh keyframe.
      request_keyframe();
      return;
    }
    if (s_last_frame_id != 0 && (int16_t)(frame - s_last_frame_id) <= 0) {
      return;
    }
    begin_frame(frame, (flags & FRAME_KEYFRAME) != 0);
  }

  const uint8_t *p = data->value->data;
  for (int i = 0; i < count; i++) {
    uint16_t index = (uint16_t)(p[0] | (p[1] << 8));
    apply_tile(index, p + 2);
    p += TILE_RECORD_BYTES;
  }

  if (flags & FRAME_FINAL) {
    memcpy(gbitmap_get_data(s_display), gbitmap_get_data(s_staging),
           (size_t)s_stride * s_height);
    s_have_frame = true;
    s_connected = true;
    s_last_rx_ms = now_ms();
    s_frame_open = false;
    s_last_frame_id = frame;
    layer_mark_dirty(s_canvas);
    APP_LOG(APP_LOG_LEVEL_DEBUG, "Frame %u applied (%d tiles, flags=%d)",
            frame, count, flags);
  }
}

static void inbox_received_callback(DictionaryIterator *iter, void *context) {
  int kind = tuple_int(iter, MK_KIND, -1);
  if (kind < 0) {
    return;
  }

  switch (kind) {
    case KIND_CONFIG:
      handle_config(iter);
      break;
    case KIND_FRAME:
      handle_frame(iter);
      break;
    case KIND_STATUS:
      handle_status(iter);
      break;
    default:
      break;
  }
}

static void inbox_dropped_callback(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "Inbox dropped: %d", (int)reason);
}

static void outbox_failed_callback(DictionaryIterator *iter, AppMessageResult reason,
                                   void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "Outbox failed: %d", (int)reason);
}

// ---------------------------------------------------------------------------
// Touch input
// ---------------------------------------------------------------------------

#define TAP_MAX_MS 480
#define TAP_MOVE_THRESHOLD 8

static void touch_handler(const TouchEvent *event, void *context) {
  if (!s_touch_enabled || !s_connected) {
    return;
  }

  switch (event->type) {
    case TouchEvent_Touchdown:
      s_touch_active = true;
      s_touch_moved = false;
      s_touch_down_ms = now_ms();
      s_touch_start_x = event->x;
      s_touch_start_y = event->y;
      s_pending_path = false;
      path_reset();
      path_add(event->x, event->y);
      break;

    case TouchEvent_PositionUpdate: {
      int dx = event->x - s_touch_start_x;
      int dy = event->y - s_touch_start_y;
      if (iabs(dx) + iabs(dy) >= TAP_MOVE_THRESHOLD) {
        s_touch_moved = true;
      }
      path_add(event->x, event->y);
      break;
    }

    case TouchEvent_Liftoff: {
      uint32_t duration = now_ms() - s_touch_down_ms;
      if (duration > 60000) {
        duration = 60000;
      }
      path_add(event->x, event->y);

      if (s_touch_active && !s_touch_moved) {
        // Quick taps and long presses are classified on the watch. Waiting for
        // the phone to compare DOWN/UP arrival times makes a tap look like a
        // long press when BLE/AppMessage is busy with a frame.
        uint8_t action;
        int x = event->x;
        int y = event->y;
        if (duration <= TAP_MAX_MS) {
          action = TOUCH_TAP;
        } else {
          action = TOUCH_LONG;
          x = s_touch_start_x;
          y = s_touch_start_y;
        }
        s_pending_action = action;
        s_pending_up_x = x;
        s_pending_up_y = y;
        s_pending_duration = 0;
        if (!send_touch_ex(action, x, y, 0)) {
          s_pending_touch = true;
          if (!s_touch_retry_timer) {
            s_touch_retry_timer = app_timer_register(120, touch_retry_callback, NULL);
          }
        }
      } else if (s_touch_active) {
        // Replay the complete polyline on the phone in one AppMessage. This
        // preserves circles/S-curves instead of reducing them to a straight
        // first-to-last swipe.
        s_path_duration_ms = (uint16_t)duration;
        if (!send_path()) {
          s_pending_path = true;
          if (!s_path_retry_timer) {
            s_path_retry_timer = app_timer_register(120, path_retry_callback, NULL);
          }
        }
      }
      s_touch_active = false;
      break;
    }
  }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

static void pan_to(int offset) {
  if (!s_have_frame || s_mode != MODE_VIEWPORT || s_max_off <= 0) {
    return;
  }

  int old_offset = s_view_off;
  if (offset < 0) {
    offset = 0;
  }
  if (offset > s_max_off) {
    offset = s_max_off;
  }
  s_view_off = offset;

  if (s_view_off != old_offset) {
    send_pan(s_view_off);
    layer_mark_dirty(s_canvas);
  }
}

static void pan_by(int delta) {
  pan_to(s_view_off + delta);
}

static void up_click_handler(ClickRecognizerRef recognizer, void *context) {
  pan_by(-PAN_STEP);
}

static void down_click_handler(ClickRecognizerRef recognizer, void *context) {
  pan_by(PAN_STEP);
}

static void up_long_click_handler(ClickRecognizerRef recognizer, void *context) {
  APP_LOG(APP_LOG_LEVEL_DEBUG, "long UP: viewport top");
  pan_to(0);
}

static void down_long_click_handler(ClickRecognizerRef recognizer, void *context) {
  APP_LOG(APP_LOG_LEVEL_DEBUG, "long DOWN: viewport bottom");
  pan_to(s_max_off);
}

static void select_click_handler(ClickRecognizerRef recognizer, void *context) {
  APP_LOG(APP_LOG_LEVEL_DEBUG, "SELECT: home");
  if (!s_connected || !s_have_frame) {
    send_hello();
    return;
  }
  send_key(KEY_HOME);
}

static void select_double_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_connected) {
    send_key(KEY_RECENTS);
  }
}

static void select_long_click_handler(ClickRecognizerRef recognizer, void *context) {
  APP_LOG(APP_LOG_LEVEL_DEBUG, "long SELECT: toggle mode");
  if (s_connected) {
    send_mode(s_mode == MODE_VIEWPORT ? MODE_FIT : MODE_VIEWPORT);
  }
}

static void back_click_handler(ClickRecognizerRef recognizer, void *context) {
  if (s_connected) {
    send_key(KEY_BACK);
  } else {
    window_stack_pop(true);
  }
}

static void click_config_provider(void *context) {
  window_single_repeating_click_subscribe(BUTTON_ID_UP, 90, up_click_handler);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, 90, down_click_handler);
  window_long_click_subscribe(BUTTON_ID_UP, 600, up_long_click_handler, NULL);
  window_long_click_subscribe(BUTTON_ID_DOWN, 600, down_long_click_handler, NULL);

  window_single_click_subscribe(BUTTON_ID_SELECT, select_click_handler);
  window_multi_click_subscribe(BUTTON_ID_SELECT, 2, 2, 300, true,
                               select_double_click_handler);
  window_long_click_subscribe(BUTTON_ID_SELECT, 650, select_long_click_handler, NULL);

  window_single_click_subscribe(BUTTON_ID_BACK, back_click_handler);
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

static void draw_centered(GContext *ctx, const char *text, GRect box, GFont font,
                          GTextOverflowMode overflow) {
  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, text, font, box, overflow, GTextAlignmentCenter, NULL);
}

static void draw_splash(GContext *ctx, GRect bounds) {
  int w = bounds.size.w;
  int h = bounds.size.h;

  draw_centered(ctx, "PEBBLE\nMIRROR", GRect(8, 12, w - 16, 66),
                s_font_title, GTextOverflowModeWordWrap);

  const char *status = !s_connected ? "Waiting for phone"
                      : (s_mirroring ? "Mirroring" : "Phone app idle");
  draw_centered(ctx, status, GRect(8, 82, w - 16, 24),
                s_font_body, GTextOverflowModeTrailingEllipsis);

  if (s_status_text[0]) {
    draw_centered(ctx, s_status_text, GRect(8, 106, w - 16, 24),
                  s_font_small, GTextOverflowModeTrailingEllipsis);
  }

  draw_centered(ctx, "Open Pebble Mirror on your phone,\nthen start mirroring.",
                GRect(10, 128, w - 20, 36), s_font_small,
                GTextOverflowModeWordWrap);

  draw_centered(ctx, "UP/DOWN: pan\nhold: top/bottom\nSELECT: home\nhold: mode",
                GRect(8, 164, w - 16, 58), s_font_small,
                GTextOverflowModeWordWrap);

  (void)h;
}

static void draw_overlays(GContext *ctx, GRect bounds) {
  int w = bounds.size.w;
  int h = bounds.size.h;

  // Connection dot: a white square with a black outline is visible on both
  // black and white content. A hollow square means connected but not mirroring.
  GRect dot = GRect(w - 10, 2, 7, 7);
  graphics_context_set_fill_color(ctx, s_connected ? GColorWhite : GColorBlack);
  graphics_fill_rect(ctx, dot, 0, GCornerNone);
  graphics_context_set_stroke_color(ctx, s_connected ? GColorBlack : GColorWhite);
  graphics_context_set_stroke_width(ctx, 1);
  graphics_draw_rect(ctx, dot);
  if (s_connected && !s_mirroring) {
    graphics_context_set_fill_color(ctx, GColorBlack);
    graphics_fill_rect(ctx, GRect(w - 8, 4, 3, 3), 0, GCornerNone);
  }

  // Viewport scrollbar. Only shown when there is something to pan.
  if (s_have_frame && s_mode == MODE_VIEWPORT && s_max_off > 0 && h > 20) {
    int track_y = 14;
    int track_h = h - track_y - 4;
    int thumb_h = (track_h * h) / (h + s_max_off);
    if (thumb_h < 10) {
      thumb_h = 10;
    }
    int thumb_y = track_y + ((track_h - thumb_h) * s_view_off) / s_max_off;

    graphics_context_set_fill_color(ctx, GColorBlack);
    graphics_fill_rect(ctx, GRect(w - 3, track_y, 3, track_h), 0, GCornerNone);
    graphics_context_set_fill_color(ctx, GColorWhite);
    graphics_fill_rect(ctx, GRect(w - 3, thumb_y, 3, thumb_h), 0, GCornerNone);
  }
}

static void canvas_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);

  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);

  if (s_have_frame && s_display) {
    graphics_draw_bitmap_in_rect(ctx, s_display, GRect(0, 0, s_width, s_height));
  } else {
    draw_splash(ctx, bounds);
  }

  draw_overlays(ctx, bounds);
}

// ---------------------------------------------------------------------------
// Timers and lifecycle
// ---------------------------------------------------------------------------

static void timeout_callback(void *data) {
  s_timeout_timer = NULL;

  if (s_connected && now_ms() - s_last_rx_ms > CONNECTION_TIMEOUT_MS) {
    s_connected = false;
    s_mirroring = false;
    if (s_have_frame) {
      reset_to_splash();
    } else {
      layer_mark_dirty(s_canvas);
    }
  }

  // Announce ourselves periodically so a phone app started after the watchapp
  // can discover us, and so the phone can detect when the watchapp closes.
  if (++s_heartbeat_ticks >= 2) {
    s_heartbeat_ticks = 0;
    send_hello();
  }

  s_timeout_timer = app_timer_register(3000, timeout_callback, NULL);
}

static void window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);

  ensure_bitmaps(bounds.size.w, bounds.size.h);

  s_canvas = layer_create(bounds);
  layer_set_update_proc(s_canvas, canvas_update_proc);
  layer_add_child(root, s_canvas);
}

static void window_unload(Window *window) {
  if (s_canvas) {
    layer_destroy(s_canvas);
    s_canvas = NULL;
  }
}

static void window_appear(Window *window) {
#ifdef _PBL_API_EXISTS_touch_service_subscribe
  if (!s_touch_subscribed) {
    touch_service_subscribe(touch_handler, NULL);
    s_touch_subscribed = true;
  }
  s_touch_enabled = true;
#else
  s_touch_enabled = false;
#endif

  send_hello();
  layer_mark_dirty(s_canvas);
}

static void window_disappear(Window *window) {
#ifdef _PBL_API_EXISTS_touch_service_subscribe
  if (s_touch_subscribed) {
    touch_service_unsubscribe();
    s_touch_subscribed = false;
  }
#endif
  s_touch_enabled = false;
}

static void init(void) {
  s_font_title = fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD);
  s_font_body = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  s_font_small = fonts_get_system_font(FONT_KEY_GOTHIC_14);
  s_last_rx_ms = now_ms();
  s_session_id = (uint16_t)(now_ms() & 0xffff);
  if (s_session_id == 0) {
    s_session_id = 1;
  }

  app_message_register_inbox_received(inbox_received_callback);
  app_message_register_inbox_dropped(inbox_dropped_callback);
  app_message_register_outbox_failed(outbox_failed_callback);

  uint32_t inbox = app_message_inbox_size_maximum();
  uint32_t outbox = app_message_outbox_size_maximum();
  AppMessageResult result = app_message_open(inbox, outbox);
  APP_LOG(APP_LOG_LEVEL_INFO, "AppMessage open: %d (inbox=%u outbox=%u)",
          (int)result, (unsigned)inbox, (unsigned)outbox);

  s_window = window_create();
  window_set_background_color(s_window, GColorBlack);
  window_set_click_config_provider(s_window, click_config_provider);
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = window_load,
    .unload = window_unload,
    .appear = window_appear,
    .disappear = window_disappear,
  });
  window_stack_push(s_window, true);

  s_timeout_timer = app_timer_register(3000, timeout_callback, NULL);
}

static void deinit(void) {
  if (s_timeout_timer) {
    app_timer_cancel(s_timeout_timer);
    s_timeout_timer = NULL;
  }
  if (s_touch_retry_timer) {
    app_timer_cancel(s_touch_retry_timer);
    s_touch_retry_timer = NULL;
  }
#ifdef _PBL_API_EXISTS_touch_service_subscribe
  if (s_touch_subscribed) {
    touch_service_unsubscribe();
    s_touch_subscribed = false;
  }
#endif
  if (s_display) {
    gbitmap_destroy(s_display);
    s_display = NULL;
  }
  if (s_staging) {
    gbitmap_destroy(s_staging);
    s_staging = NULL;
  }
  window_destroy(s_window);
  s_window = NULL;
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
