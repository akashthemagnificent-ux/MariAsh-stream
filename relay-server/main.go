package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// ─────────────────────────────────────────────────
// Agon Relay Server — intercontinental sync & HLS relay
//
// Routes:
//   GET  /healthz                           — health check
//   WS   /sync/{roomId}?role=host|client   — bi-directional sync WebSocket
//   PUT  /upload/{roomId}/{filename}        — host uploads HLS segments
//   GET  /hls/{roomId}/{filename}           — client downloads HLS segments
// ─────────────────────────────────────────────────

var upgrader = websocket.Upgrader{
	CheckOrigin:     func(r *http.Request) bool { return true },
	ReadBufferSize:  65536,
	WriteBufferSize: 65536,
}

var expectedRelayToken = os.Getenv("RELAY_TOKEN")
var segmentNamePattern = regexp.MustCompile(`^[a-zA-Z0-9._-]+$`)

func authorize(r *http.Request) bool {
	if expectedRelayToken == "" {
		return true
	}
	provided := r.Header.Get("X-Relay-Token")
	if provided == "" {
		provided = r.URL.Query().Get("token")
	}
	return provided == expectedRelayToken
}

func validSegmentName(name string) bool {
	if !segmentNamePattern.MatchString(name) {
		return false
	}
	// Segments are MP4 (changed from .ts in Bug 7 fix).
	// .m3u8 = HLS playlist, .vtt = subtitle track, .ts kept for compatibility.
	return strings.HasSuffix(name, ".mp4") ||
		strings.HasSuffix(name, ".m3u8") ||
		strings.HasSuffix(name, ".vtt") ||
		strings.HasSuffix(name, ".ts")
}

func setCommonHeaders(w http.ResponseWriter) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Relay-Token")
	w.Header().Set("Access-Control-Allow-Methods", "GET, PUT, OPTIONS")
}

func handlePreflight(w http.ResponseWriter, r *http.Request) bool {
	if r.Method == http.MethodOptions {
		setCommonHeaders(w)
		w.WriteHeader(http.StatusNoContent)
		return true
	}
	return false
}

// A room holds the host and client connections and their HLS segments
type Room struct {
	mu       sync.RWMutex
	host     *websocket.Conn
	client   *websocket.Conn
	segments map[string][]byte // name → bytes
	order    []string
	bytes    int64
	lastSeen time.Time
	pendingHost   bool
	pendingClient bool
}

func (r *Room) segmentCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.segments)
}

func (r *Room) setSegment(name string, data []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if old, existed := r.segments[name]; existed {
		r.bytes -= int64(len(old))
	} else {
		r.order = append(r.order, name)
	}
	r.segments[name] = data
	r.bytes += int64(len(data))

	// Evict by segment count first
	for len(r.order) > maxSegmentsPerRoom {
		old := r.order[0]
		r.order = r.order[1:]
		if oldData, ok := r.segments[old]; ok {
			r.bytes -= int64(len(oldData))
		}
		delete(r.segments, old)
	}

	// Evict by total room bytes if configured
	for maxRoomBytes > 0 && r.bytes > maxRoomBytes && len(r.order) > 0 {
		old := r.order[0]
		r.order = r.order[1:]
		if oldData, ok := r.segments[old]; ok {
			r.bytes -= int64(len(oldData))
		}
		delete(r.segments, old)
	}
}

func (r *Room) getSegment(name string) ([]byte, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	d, ok := r.segments[name]
	return d, ok
}

var (
	rooms              = map[string]*Room{}
	roomsMu            sync.Mutex
	maxSegmentsPerRoom = envInt("MAX_SEGMENTS_PER_ROOM", 1800)
	maxRoomBytes       = int64(envInt("MAX_ROOM_BYTES_MB", 1024)) * 1024 * 1024
	uploadsPerMinute   = envInt("UPLOADS_PER_MINUTE", 600)
	uploadLimiter      = newRateLimiter(time.Minute, uploadsPerMinute)
)

func envInt(key string, fallback int) int {
	val := os.Getenv(key)
	if val == "" {
		return fallback
	}
	n, err := strconv.Atoi(val)
	if err != nil || n < 1 {
		return fallback
	}
	return n
}

type rateLimiter struct {
	mu      sync.Mutex
	window  time.Duration
	limit   int
	buckets map[string][]time.Time
}

func newRateLimiter(window time.Duration, limit int) *rateLimiter {
	return &rateLimiter{
		window:  window,
		limit:   limit,
		buckets: map[string][]time.Time{},
	}
}

func (r *rateLimiter) allow(key string) bool {
	now := time.Now()
	cutoff := now.Add(-r.window)

	r.mu.Lock()
	defer r.mu.Unlock()

	entries := r.buckets[key]
	pruned := entries[:0]
	for _, ts := range entries {
		if ts.After(cutoff) {
			pruned = append(pruned, ts)
		}
	}
	if len(pruned) >= r.limit {
		r.buckets[key] = pruned
		return false
	}
	pruned = append(pruned, now)
	r.buckets[key] = pruned
	return true
}

func (r *rateLimiter) pruneIdle() {
	now := time.Now()
	cutoff := now.Add(-r.window)
	r.mu.Lock()
	defer r.mu.Unlock()
	for key, entries := range r.buckets {
		pruned := entries[:0]
		for _, ts := range entries {
			if ts.After(cutoff) {
				pruned = append(pruned, ts)
			}
		}
		if len(pruned) == 0 {
			delete(r.buckets, key)
		} else {
			r.buckets[key] = pruned
		}
	}
}

func getOrCreateRoom(id string) *Room {
	roomsMu.Lock()
	defer roomsMu.Unlock()
	if r, ok := rooms[id]; ok {
		return r
	}
	r := &Room{segments: make(map[string][]byte)}
	r.lastSeen = time.Now()
	rooms[id] = r
	return r
}

func (r *Room) touch() {
	r.mu.Lock()
	r.lastSeen = time.Now()
	r.mu.Unlock()
}

func (r *Room) reserve(role string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if role == "host" {
		if r.host != nil || r.pendingHost {
			return false
		}
		r.pendingHost = true
		return true
	}
	if r.client != nil || r.pendingClient {
		return false
	}
	r.pendingClient = true
	return true
}

func (r *Room) commit(role string, conn *websocket.Conn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if role == "host" {
		r.pendingHost = false
		r.host = conn
	} else {
		r.pendingClient = false
		r.client = conn
	}
}

func (r *Room) releaseReservation(role string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if role == "host" {
		r.pendingHost = false
	} else {
		r.pendingClient = false
	}
}

// ─────────────────────────────────────────────────
// WebSocket sync handler
// ─────────────────────────────────────────────────
func syncHandler(w http.ResponseWriter, r *http.Request) {
	if !authorize(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	// Path: /sync/{roomId}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/sync/"), "/")
	if len(parts) == 0 || parts[0] == "" {
		http.Error(w, "missing roomId", http.StatusBadRequest)
		return
	}
	roomId := parts[0]
	role := r.URL.Query().Get("role")
	if role != "host" && role != "client" {
		http.Error(w, "invalid role", http.StatusBadRequest)
		return
	}

	room := getOrCreateRoom(roomId)
	if !room.reserve(role) {
	room.mu.RLock()
	roleOccupied := (role == "host" && room.host != nil) || (role == "client" && room.client != nil)
	room.mu.RUnlock()
	if roleOccupied {
		http.Error(w, "role already connected", http.StatusConflict)
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		room.releaseReservation(role)
		log.Printf("WebSocket upgrade error [%s/%s]: %v", roomId, role, err)
		return
	}
	defer conn.Close()

	room.touch()
	room.commit(role, conn)
	room.mu.Lock()
	if role == "host" {
		room.host = conn
	} else {
		room.client = conn
	}
	room.mu.Unlock()

	log.Printf("[%s] %s connected", roomId, role)

	// Ping keepalive
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		return nil
	})
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			if err := conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)); err != nil {
				return
			}
		}
	}()

	for {
		conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		msgType, data, err := conn.ReadMessage()
		if err != nil {
			log.Printf("[%s] %s disconnected: %v", roomId, role, err)
			break
		}

		room.mu.RLock()
		var target *websocket.Conn
		if role == "host" {
			target = room.client
		} else {
			target = room.host
		}
		room.mu.RUnlock()

		if target != nil {
			target.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := target.WriteMessage(msgType, data); err != nil {
				log.Printf("[%s] relay write error: %v", roomId, err)
			}
		}
		room.touch()
	}

	// Clean up connection reference
	room.mu.Lock()
	if role == "host" && room.host == conn {
		room.host = nil
	} else if role == "client" && room.client == conn {
		room.client = nil
	}
	room.mu.Unlock()
	room.touch()
}

// ─────────────────────────────────────────────────
// Segment upload handler (host PUT → relay stores in memory)
// ─────────────────────────────────────────────────
func uploadHandler(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}
	setCommonHeaders(w)
	if !authorize(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if r.Method != http.MethodPut {
		http.Error(w, "PUT required", http.StatusMethodNotAllowed)
		return
	}
	ip := r.RemoteAddr
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		ip = host
	}
	if !uploadLimiter.allow(ip) {
		http.Error(w, "rate limit exceeded", http.StatusTooManyRequests)
		return
	}
	// Path: /upload/{roomId}/{filename}
	trimmed := strings.TrimPrefix(r.URL.Path, "/upload/")
	slashIdx := strings.Index(trimmed, "/")
	if slashIdx < 0 {
		http.Error(w, "missing filename", http.StatusBadRequest)
		return
	}
	roomId := trimmed[:slashIdx]
	filename := trimmed[slashIdx+1:]
	if !validSegmentName(filename) {
		http.Error(w, "invalid filename", http.StatusBadRequest)
		return
	}

	if cl := r.Header.Get("Content-Length"); cl != "" {
		if n, err := strconv.ParseInt(cl, 10, 64); err == nil && n > 50*1024*1024 {
			http.Error(w, "payload too large", http.StatusRequestEntityTooLarge)
			return
		}
	}
	limited := &io.LimitedReader{R: r.Body, N: 50*1024*1024 + 1}
	data, err := io.ReadAll(limited)
	if err != nil {
		http.Error(w, "read error", http.StatusInternalServerError)
		return
	}
	if int64(len(data)) > 50*1024*1024 {
		http.Error(w, "payload too large", http.StatusRequestEntityTooLarge)
		return
	}

	room := getOrCreateRoom(roomId)
	room.setSegment(filename, data)
	room.touch()
	log.Printf("[%s] stored %s (%d KB)", roomId, filename, len(data)/1024)
	w.WriteHeader(http.StatusNoContent)
}

// ─────────────────────────────────────────────────
// Segment download handler (client GET)
// ─────────────────────────────────────────────────
func hlsHandler(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}
	setCommonHeaders(w)
	if !authorize(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	// Path: /hls/{roomId}/{filename}
	trimmed := strings.TrimPrefix(r.URL.Path, "/hls/")
	slashIdx := strings.Index(trimmed, "/")
	if slashIdx < 0 {
		http.Error(w, "missing filename", http.StatusBadRequest)
		return
	}
	roomId := trimmed[:slashIdx]
	filename := trimmed[slashIdx+1:]
	if !validSegmentName(filename) {
		http.Error(w, "invalid filename", http.StatusBadRequest)
		return
	}

	room := getOrCreateRoom(roomId)
	room.touch()

	// Retry for up to 30 seconds (playlist / segments may still be uploading)
	var data []byte
	for attempts := 0; attempts < 60; attempts++ {
		d, ok := room.getSegment(filename)
		if ok {
			data = d
			break
		}
		time.Sleep(500 * time.Millisecond)
	}

	if data == nil {
		http.Error(w, fmt.Sprintf("segment %s not found", filename), http.StatusNotFound)
		return
	}

	var contentType string
	switch {
	case strings.HasSuffix(filename, ".m3u8"):
		contentType = "application/vnd.apple.mpegurl"
	case strings.HasSuffix(filename, ".mp4"):
		contentType = "video/mp4"
	case strings.HasSuffix(filename, ".ts"):
		contentType = "video/MP2T"
	case strings.HasSuffix(filename, ".vtt"):
		contentType = "text/vtt"
	default:
		contentType = "application/octet-stream"
	}

	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Length", fmt.Sprintf("%d", len(data)))
	w.Header().Set("Cache-Control", "no-cache")
	w.Write(data)
	room.touch()
}

func cleanupRoomsLoop() {
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		uploadLimiter.pruneIdle()
		now := time.Now()
		roomsMu.Lock()
		for id, room := range rooms {
			room.mu.RLock()
			idle := now.Sub(room.lastSeen)
			hostNil := room.host == nil
			clientNil := room.client == nil
			room.mu.RUnlock()

			// Evict rooms idle for >= 4 hours once both peers are disconnected
			if hostNil && clientNil && idle >= 4*time.Hour {
				delete(rooms, id)
				log.Printf("[cleanup] evicted room=%s idle=%s", id, idle.String())
			}
		}
		roomsMu.Unlock()
	}
}

type relayStats struct {
	Rooms            int `json:"rooms"`
	ConnectedHosts   int `json:"connected_hosts"`
	ConnectedClients int `json:"connected_clients"`
	TotalSegments    int `json:"total_segments"`
	TotalBytes       int64 `json:"total_bytes"`
}

type relayConfig struct {
	MaxSegmentsPerRoom int   `json:"max_segments_per_room"`
	MaxRoomBytes       int64 `json:"max_room_bytes"`
	UploadsPerMinute   int   `json:"uploads_per_minute"`
	RelayTokenEnabled  bool  `json:"relay_token_enabled"`
}

func statsHandler(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}
	setCommonHeaders(w)
	if !authorize(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	stats := relayStats{}
	roomsMu.Lock()
	stats.Rooms = len(rooms)
	for _, room := range rooms {
		room.mu.RLock()
		if room.host != nil {
			stats.ConnectedHosts++
		}
		if room.client != nil {
			stats.ConnectedClients++
		}
		roomBytes := room.bytes
		room.mu.RUnlock()
		stats.TotalSegments += room.segmentCount()
		stats.TotalBytes += roomBytes
	}
	roomsMu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(stats)
}

func configHandler(w http.ResponseWriter, r *http.Request) {
	if handlePreflight(w, r) {
		return
	}
	setCommonHeaders(w)
	if !authorize(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	cfg := relayConfig{
		MaxSegmentsPerRoom: maxSegmentsPerRoom,
		MaxRoomBytes:       maxRoomBytes,
		UploadsPerMinute:   uploadsPerMinute,
		RelayTokenEnabled:  expectedRelayToken != "",
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(cfg)
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		if handlePreflight(w, r) {
			return
		}
		setCommonHeaders(w)
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	})
	mux.HandleFunc("/sync/", syncHandler)
	mux.HandleFunc("/upload/", uploadHandler)
	mux.HandleFunc("/hls/", hlsHandler)
	mux.HandleFunc("/stats", statsHandler)
	mux.HandleFunc("/config", configHandler)
	go cleanupRoomsLoop()

	log.Printf("Agon relay server starting on :%s", port)
	log.Printf("Relay config: token_enabled=%t max_segments_per_room=%d max_room_bytes=%d uploads_per_minute=%d",
		expectedRelayToken != "", maxSegmentsPerRoom, maxRoomBytes, uploadsPerMinute)
	srv := &http.Server{
		Addr:         ":" + port,
		Handler:      mux,
		ReadTimeout:  5 * time.Minute,
		WriteTimeout: 5 * time.Minute,
		IdleTimeout:  2 * time.Minute,
	}
	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("Server error: %v", err)
	}
}
