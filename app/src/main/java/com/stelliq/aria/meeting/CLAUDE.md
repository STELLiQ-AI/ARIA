# Meeting Package — Multi-Device Protocol

## Responsibility
Enables multiple ARIA devices to participate in the same meeting.
Owner device runs a TCP server; participant devices connect as clients.
Transcript segments are exchanged with clock-synchronized timestamps
for chronological aggregation.

## Architecture

```
     OWNER DEVICE                          PARTICIPANT DEVICE
  ┌──────────────────┐                  ┌──────────────────────┐
  │  MeetingManager   │                  │   MeetingManager      │
  │  (role: OWNER)    │                  │   (role: PARTICIPANT) │
  │                   │                  │                       │
  │  MeetingServer    │◄────TCP─────────►│   MeetingClient       │
  │  (port: auto)     │                  │                       │
  │                   │                  │                       │
  │  NSD Registration │  ◄──discovery──  │   NSD Discovery       │
  └──────────────────┘                  └──────────────────────┘
```

## Files

| File | Responsibility |
|------|---------------|
| `MeetingProtocol.java` | Wire protocol definition. Message types, JSON serialization, NSD constants. |
| `MeetingServer.java` | TCP server on owner device. Accepts connections, handles JOIN/SEGMENT/LEAVE. CachedThreadPool for client handlers. Executor shutdown in `stop()`. |
| `MeetingClient.java` | TCP client on participant device. Connects to owner, sends segments, receives SYNC/END. |
| `MeetingManager.java` | High-level coordinator. Manages roles (OWNER/PARTICIPANT/NONE), NSD discovery/registration, segment aggregation, clock sync. |

## Wire Protocol

Newline-delimited JSON over TCP. Each message is one JSON object followed by `\n`.

| Type | Direction | Fields | Purpose |
|------|-----------|--------|---------|
| `JOIN` | Participant → Owner | speaker | Register with name |
| `SYNC` | Owner → Participant | t0EpochMs | Clock reference for alignment |
| `SEGMENT` | Participant → Owner | speaker, text, segmentIndex, timestamp | Transcript chunk |
| `TRANSCRIPT` | Owner → All | text | Broadcast aggregated transcript |
| `LEAVE` | Participant → Owner | speaker | Graceful disconnect |
| `END` | Owner → All | (none) | Session complete |

## Clock Synchronization (T0 Protocol)

**Problem:** Different devices have different `System.currentTimeMillis()` values.

**Solution:**
1. Owner records `t0EpochMs = System.currentTimeMillis()` at session start
2. Owner sends `SYNC(t0EpochMs)` to each participant on JOIN
3. Participant computes `clockOffset = localTime - t0EpochMs`
4. Participant sends segments with `timestamp = segmentTime - clockOffset` (owner-aligned)
5. Owner stores all segments in `TreeMap<Long, SpeakerSegment>` — naturally sorted by time

**Accuracy:** ~10-50ms on same WiFi network (sufficient for transcript ordering).

## NSD (Network Service Discovery)

- Service type: `_ariameeting._tcp.`
- Owner registers service on server start
- Participant discovers and resolves to IP:port
- Used for zero-configuration LAN discovery

## Key Contracts

### MeetingManager
- `startAsOwner(speakerName)` — Starts server + NSD registration
- `startAsParticipant(speakerName)` — Starts NSD discovery, connects when found
- `stop()` — Closes connections, unregisters NSD, shuts down executor
- `sendSegment(text, index)` — Sends transcript segment to owner
- `getAggregatedTranscript()` — Returns chronologically merged transcript from all devices
- `setT0EpochMs(long)` — Must be called before `startAsOwner()`

### MeetingServer
- `start()` → int — Returns assigned port, or -1 on failure
- `stop()` — Broadcasts END, closes all connections, shuts down executor
- `broadcast(String)` — Sends message to all connected clients
- Executor: `CachedThreadPool` — shut down via `shutdownNow()` in `stop()`

## Important Notes
- **Not used in single-device demo mode** — meeting package is for Phase I multi-device capability
- **Air-gapped:** All communication is local TCP over WiFi LAN, no internet required
- **Thread safety:** `CopyOnWriteArrayList` for client connections; callbacks posted to main thread via Handler
