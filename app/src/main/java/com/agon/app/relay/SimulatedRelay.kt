package com.agon.app.relay

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// ─────────────────────────────────────────────────
// Network Profiles — choose which "continent gap" to simulate
// ─────────────────────────────────────────────────
data class NetworkProfile(
    val id: String,
    val label: String,
    val emoji: String,
    val oneWayLatencyMs: Long,
    val jitterMs: Long,
    val bandwidthKbps: Int,
    val packetLossPercent: Int,
    val description: String
)

object NetworkProfiles {
    val SAME_ROOM = NetworkProfile(
        "same_room", "Same Room", "🏠",
        oneWayLatencyMs = 2, jitterMs = 1,
        bandwidthKbps = 1_000_000, packetLossPercent = 0,
        description = "Ideal LAN conditions"
    )
    val SAME_CITY = NetworkProfile(
        "same_city", "Same City", "🏙️",
        oneWayLatencyMs = 25, jitterMs = 5,
        bandwidthKbps = 50_000, packetLossPercent = 0,
        description = "25ms one-way, 50 Mbps"
    )
    val INDIA_EUROPE = NetworkProfile(
        "india_europe", "India → Europe", "🌍",
        oneWayLatencyMs = 180, jitterMs = 40,
        bandwidthKbps = 15_000, packetLossPercent = 1,
        description = "180ms one-way, 15 Mbps, 1% loss"
    )
    val INDIA_USA = NetworkProfile(
        "india_usa", "India → USA", "🌎",
        oneWayLatencyMs = 260, jitterMs = 60,
        bandwidthKbps = 10_000, packetLossPercent = 2,
        description = "260ms one-way, 10 Mbps, 2% loss"
    )
    val WORST_CASE = NetworkProfile(
        "worst_case", "Worst Case Mobile", "📶",
        oneWayLatencyMs = 400, jitterMs = 120,
        bandwidthKbps = 2_500, packetLossPercent = 5,
        description = "400ms one-way, 2.5 Mbps, 5% loss"
    )

    val all = listOf(SAME_ROOM, SAME_CITY, INDIA_EUROPE, INDIA_USA, WORST_CASE)
}

// ─────────────────────────────────────────────────
// SimulatedRelay — honest in-process continent simulation
// ─────────────────────────────────────────────────
class SimulatedRelay(private val port: Int = 9191) {

    private val segments = ConcurrentHashMap<String, ByteArray>()
    private var playlist: String = ""

    @Volatile private var profile: NetworkProfile = NetworkProfiles.INDIA_USA
    @Volatile private var running = false

    private val _uploadedBytes = MutableStateFlow(0L)
    val uploadedBytes: StateFlow<Long> = _uploadedBytes

    private val _downloadedBytes = MutableStateFlow(0L)
    val downloadedBytes: StateFlow<Long> = _downloadedBytes

    private val _segmentCount = MutableStateFlow(0)
    val segmentCount: StateFlow<Int> = _segmentCount

    private val _droppedPackets = MutableStateFlow(0)
    val droppedPackets: StateFlow<Int> = _droppedPackets

    // Callbacks: relay delivers messages to each side
    var onSyncToClient: ((json: String) -> Unit)? = null
    var onSyncToHost: ((json: String) -> Unit)? = null

    val hlsBaseUrl: String get() = "http://127.0.0.1:$port/hls"
    val hlsPlaylistUrl: String get() = "$hlsBaseUrl/playlist.m3u8"

    // ── NanoHTTPD serving HLS to the "client" ExoPlayer ──────────
    private val httpServer = object : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return when {
                session.uri == "/hls/playlist.m3u8" -> servePlaylist()
                session.uri.startsWith("/hls/") && session.uri.endsWith(".ts") -> {
                    val name = session.uri.removePrefix("/hls/")
                    serveSegment(name)
                }
                session.uri == "/ping" -> {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "pong")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }

        private fun servePlaylist(): Response {
            simulateNetworkDelay(isLargePayload = false)
            if (playlist.isEmpty()) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain", "Playlist not ready yet")
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.apple.mpegurl",
                playlist
            ).also {
                it.addHeader("Cache-Control", "no-cache")
                it.addHeader("Access-Control-Allow-Origin", "*")
            }
        }

        private fun serveSegment(name: String): Response {
            val data = segments[name]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain",
                    "Segment $name not available yet")

            simulateNetworkDelay(isLargePayload = true, byteCount = data.size)
            _downloadedBytes.value += data.size

            return newFixedLengthResponse(
                Response.Status.OK,
                "video/MP2T",
                ByteArrayInputStream(data),
                data.size.toLong()
            ).also {
                it.addHeader("Cache-Control", "no-cache")
                it.addHeader("Access-Control-Allow-Origin", "*")
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────
    fun start(initialProfile: NetworkProfile = NetworkProfiles.INDIA_USA) {
        profile = initialProfile
        running = true
        segments.clear()
        playlist = ""
        _uploadedBytes.value = 0L
        _downloadedBytes.value = 0L
        _segmentCount.value = 0
        _droppedPackets.value = 0
        httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        Log.d("SimulatedRelay", "Started on port $port — profile: ${profile.label}")
    }

    fun stop() {
        running = false
        httpServer.stop()
        segments.clear()
        playlist = ""
        Log.d("SimulatedRelay", "Stopped")
    }

    fun setProfile(newProfile: NetworkProfile) {
        profile = newProfile
        Log.d("SimulatedRelay", "Profile changed to: ${newProfile.label}")
    }

    // ── Segment management ────────────────────────────────────────
    fun addSegment(name: String, data: ByteArray) {
        segments[name] = data
        _uploadedBytes.value += data.size
        _segmentCount.value = segments.filter { it.key.endsWith(".ts") }.size
        Log.d("SimulatedRelay", "Segment stored: $name (${data.size / 1024}KB), total: ${_segmentCount.value}")
    }

    fun updatePlaylist(content: String) {
        playlist = content
    }

    // ── Sync relay with artificial delay ─────────────────────────
    fun sendSyncFromHost(json: String) {
        if (shouldDrop("host→client")) return
        val delay = delayMs()
        Thread {
            try { Thread.sleep(delay) } catch (_: InterruptedException) { }
            onSyncToClient?.invoke(json)
        }.also { it.isDaemon = true }.start()
    }

    fun sendSyncFromClient(json: String) {
        if (shouldDrop("client→host")) return
        val delay = delayMs()
        Thread {
            try { Thread.sleep(delay) } catch (_: InterruptedException) { }
            onSyncToHost?.invoke(json)
        }.also { it.isDaemon = true }.start()
    }

    // ── Network simulation helpers ────────────────────────────────
    private fun simulateNetworkDelay(isLargePayload: Boolean, byteCount: Int = 0) {
        var totalDelayMs = profile.oneWayLatencyMs

        // Jitter
        if (profile.jitterMs > 0) {
            totalDelayMs += (Random.nextLong(profile.jitterMs * 2) - profile.jitterMs)
            totalDelayMs = totalDelayMs.coerceAtLeast(0)
        }

        // Bandwidth throttle for large payloads (segments)
        if (isLargePayload && byteCount > 0 && profile.bandwidthKbps < 1_000_000) {
            val transferMs = (byteCount.toLong() * 8L * 1000L) / (profile.bandwidthKbps.toLong() * 1000L)
            totalDelayMs += transferMs
        }

        if (totalDelayMs > 0) {
            try { Thread.sleep(totalDelayMs) } catch (_: InterruptedException) { }
        }
    }

    private fun delayMs(): Long {
        var d = profile.oneWayLatencyMs
        if (profile.jitterMs > 0) {
            d += (Random.nextLong(profile.jitterMs * 2) - profile.jitterMs)
        }
        return d.coerceAtLeast(0)
    }

    private fun shouldDrop(direction: String): Boolean {
        if (profile.packetLossPercent == 0) return false
        val dropped = Random.nextInt(100) < profile.packetLossPercent
        if (dropped) {
            _droppedPackets.value++
            Log.w("SimulatedRelay", "Packet dropped ($direction) — simulating ${profile.packetLossPercent}% loss")
        }
        return dropped
    }
}
