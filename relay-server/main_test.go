package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestAuthorizeWithoutTokenConfigured(t *testing.T) {
	expectedRelayToken = ""
	req := httptest.NewRequest("GET", "/healthz", nil)
	if !authorize(req) {
		t.Fatal("expected authorize=true when RELAY_TOKEN is not configured")
	}
}

func TestAuthorizeWithHeaderToken(t *testing.T) {
	expectedRelayToken = "secret"
	req := httptest.NewRequest("GET", "/healthz", nil)
	req.Header.Set("X-Relay-Token", "secret")
	if !authorize(req) {
		t.Fatal("expected authorize=true with matching header token")
	}
}

func TestAuthorizeWithQueryToken(t *testing.T) {
	expectedRelayToken = "secret"
	req := httptest.NewRequest("GET", "/healthz?token=secret", nil)
	if !authorize(req) {
		t.Fatal("expected authorize=true with matching query token")
	}
}

func TestAuthorizeFailure(t *testing.T) {
	expectedRelayToken = "secret"
	req := httptest.NewRequest("GET", "/healthz?token=wrong", nil)
	if authorize(req) {
		t.Fatal("expected authorize=false with mismatched token")
	}
}

func TestRoomTouchUpdatesLastSeen(t *testing.T) {
	r := &Room{lastSeen: time.Now().Add(-time.Hour)}
	before := r.lastSeen
	r.touch()
	if !r.lastSeen.After(before) {
		t.Fatalf("expected touch() to update lastSeen, before=%v after=%v", before, r.lastSeen)
	}
}

func TestRoomSegmentCount(t *testing.T) {
	r := &Room{segments: map[string][]byte{}}
	r.setSegment("a.ts", []byte{1, 2, 3})
	r.setSegment("b.ts", []byte{4, 5, 6})
	if got := r.segmentCount(); got != 2 {
		t.Fatalf("expected segmentCount=2, got=%d", got)
	}
}

func TestRoomSegmentCapEvictsOldest(t *testing.T) {
	prev := maxSegmentsPerRoom
	maxSegmentsPerRoom = 2
	defer func() { maxSegmentsPerRoom = prev }()

	r := &Room{segments: map[string][]byte{}}
	r.setSegment("a.ts", []byte{1})
	r.setSegment("b.ts", []byte{2})
	r.setSegment("c.ts", []byte{3})

	if got := r.segmentCount(); got != 2 {
		t.Fatalf("expected segmentCount=2 after cap eviction, got=%d", got)
	}
	if _, ok := r.getSegment("a.ts"); ok {
		t.Fatal("expected oldest segment a.ts to be evicted")
	}
	if _, ok := r.getSegment("c.ts"); !ok {
		t.Fatal("expected newest segment c.ts to remain")
	}
}

func TestRateLimiterBlocksAfterLimit(t *testing.T) {
	rl := newRateLimiter(time.Minute, 2)
	if !rl.allow("1.2.3.4") {
		t.Fatal("expected first request allowed")
	}
	if !rl.allow("1.2.3.4") {
		t.Fatal("expected second request allowed")
	}
	if rl.allow("1.2.3.4") {
		t.Fatal("expected third request blocked")
	}
}

func TestRateLimiterPruneIdleRemovesOldKeys(t *testing.T) {
	rl := newRateLimiter(time.Second, 5)
	rl.buckets["old"] = []time.Time{time.Now().Add(-2 * time.Second)}
	rl.buckets["new"] = []time.Time{time.Now()}

	rl.pruneIdle()

	if _, ok := rl.buckets["old"]; ok {
		t.Fatal("expected old bucket key to be pruned")
	}
	if _, ok := rl.buckets["new"]; !ok {
		t.Fatal("expected recent bucket key to remain")
	}
}

func TestRoomByteCapEvictsOldest(t *testing.T) {
	prevSegments := maxSegmentsPerRoom
	prevBytes := maxRoomBytes
	maxSegmentsPerRoom = 10
	maxRoomBytes = 5 // bytes
	defer func() {
		maxSegmentsPerRoom = prevSegments
		maxRoomBytes = prevBytes
	}()

	r := &Room{segments: map[string][]byte{}}
	r.setSegment("a.ts", []byte{1, 2, 3}) // 3 bytes
	r.setSegment("b.ts", []byte{4, 5, 6}) // pushes to 6 bytes, should evict a.ts

	if _, ok := r.getSegment("a.ts"); ok {
		t.Fatal("expected oldest segment to be evicted by byte cap")
	}
	if _, ok := r.getSegment("b.ts"); !ok {
		t.Fatal("expected newest segment to remain")
	}
}

func TestValidSegmentName(t *testing.T) {
	valid := []string{"seg_00001.ts", "playlist.m3u8", "sub_en.vtt"}
	for _, name := range valid {
		if !validSegmentName(name) {
			t.Fatalf("expected valid segment name: %s", name)
		}
	}

	invalid := []string{"../seg.ts", "seg?.ts", "video.mp4", "sub.srt"}
	for _, name := range invalid {
		if validSegmentName(name) {
			t.Fatalf("expected invalid segment name: %s", name)
		}
	}
}

func TestHandlePreflight(t *testing.T) {
	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodOptions, "/upload/ROOM/seg_0001.ts", nil)
	if !handlePreflight(w, r) {
		t.Fatal("expected OPTIONS request to be handled as preflight")
	}
	if w.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", w.Code)
	}
	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Fatalf("expected CORS origin header, got %q", got)
	}
}

func TestConfigHandlerAuth(t *testing.T) {
	prev := expectedRelayToken
	expectedRelayToken = "secret"
	defer func() { expectedRelayToken = prev }()

	unauthReq := httptest.NewRequest(http.MethodGet, "/config", nil)
	unauthW := httptest.NewRecorder()
	configHandler(unauthW, unauthReq)
	if unauthW.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for unauth request, got %d", unauthW.Code)
	}

	authReq := httptest.NewRequest(http.MethodGet, "/config?token=secret", nil)
	authW := httptest.NewRecorder()
	configHandler(authW, authReq)
	if authW.Code != http.StatusOK {
		t.Fatalf("expected 200 for authorized config request, got %d", authW.Code)
	}
}

func TestSyncHandlerRejectsInvalidRole(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/sync/ROOM1?role=badrole", nil)
	w := httptest.NewRecorder()
	syncHandler(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid role, got %d", w.Code)
	}
}

func TestSyncHandlerRejectsDuplicateRole(t *testing.T) {
	room := getOrCreateRoom("ROOM_DUP")
	room.mu.Lock()
	room.host = &websocket.Conn{}
	room.mu.Unlock()

	req := httptest.NewRequest(http.MethodGet, "/sync/ROOM_DUP?role=host", nil)
	w := httptest.NewRecorder()
	syncHandler(w, req)
	if w.Code != http.StatusConflict {
		t.Fatalf("expected 409 for duplicate role, got %d", w.Code)
	}
}
