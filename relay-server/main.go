package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
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
	CheckOrigin: func(r *http.Request) bool { return true },
	ReadBufferSize:  65536,
	WriteBufferSize: 65536,
}

// A room holds the host and client connections and their HLS segments
type Room struct {
	mu       sync.RWMutex
	host     *websocket.Conn
	client   *websocket.Conn
	segments map[string][]byte // name → bytes
}

func (r *Room) setSegment(name string, data []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.segments[name] = data
}

func (r *Room) getSegment(name string) ([]byte, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	d, ok := r.segments[name]
	return d, ok
}

var (
	rooms   = map[string]*Room{}
	roomsMu sync.Mutex
)

func getOrCreateRoom(id string) *Room {
	roomsMu.Lock()
	defer roomsMu.Unlock()
	if r, ok := rooms[id]; ok {
		return r
	}
	r := &Room{segments: make(map[string][]byte)}
	rooms[id] = r
	return r
}

// ─────────────────────────────────────────────────
// WebSocket sync handler
// ─────────────────────────────────────────────────
func syncHandler(w http.ResponseWriter, r *http.Request) {
	// Path: /sync/{roomId}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/sync/"), "/")
	if len(parts) == 0 || parts[0] == "" {
		http.Error(w, "missing roomId", http.StatusBadRequest)
		return
	}
	roomId := parts[0]
	role := r.URL.Query().Get("role")

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket upgrade error [%s/%s]: %v", roomId, role, err)
		return
	}
	defer conn.Close()

	room := getOrCreateRoom(roomId)
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
	}

	// Clean up connection reference
	room.mu.Lock()
	if role == "host" && room.host == conn {
		room.host = nil
	} else if role == "client" && room.client == conn {
		room.client = nil
	}
	room.mu.Unlock()
}

// ─────────────────────────────────────────────────
// Segment upload handler (host PUT → relay stores in memory)
// ─────────────────────────────────────────────────
func uploadHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, "PUT required", http.StatusMethodNotAllowed)
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

	data, err := io.ReadAll(io.LimitReader(r.Body, 50*1024*1024)) // 50 MB per segment max
	if err != nil {
		http.Error(w, "read error", http.StatusInternalServerError)
		return
	}

	room := getOrCreateRoom(roomId)
	room.setSegment(filename, data)
	log.Printf("[%s] stored %s (%d KB)", roomId, filename, len(data)/1024)
	w.WriteHeader(http.StatusNoContent)
}

// ─────────────────────────────────────────────────
// Segment download handler (client GET)
// ─────────────────────────────────────────────────
func hlsHandler(w http.ResponseWriter, r *http.Request) {
	// Path: /hls/{roomId}/{filename}
	trimmed := strings.TrimPrefix(r.URL.Path, "/hls/")
	slashIdx := strings.Index(trimmed, "/")
	if slashIdx < 0 {
		http.Error(w, "missing filename", http.StatusBadRequest)
		return
	}
	roomId := trimmed[:slashIdx]
	filename := trimmed[slashIdx+1:]

	room := getOrCreateRoom(roomId)

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
	case strings.HasSuffix(filename, ".ts"):
		contentType = "video/MP2T"
	default:
		contentType = "application/octet-stream"
	}

	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Length", fmt.Sprintf("%d", len(data)))
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Write(data)
}

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	})
	mux.HandleFunc("/sync/", syncHandler)
	mux.HandleFunc("/upload/", uploadHandler)
	mux.HandleFunc("/hls/", hlsHandler)

	log.Printf("Agon relay server starting on :%s", port)
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
