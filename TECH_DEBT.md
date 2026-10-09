# BCCE Server Stability Cross-Version Matrix

| Issue | 1.19.2 | 1.20.1 | 1.21.1 | 1.21.11 | 26.1.2 |
|---|:---:|:---:|:---:|:---:|:---:|
| Wire manager runs on both START and END tick phases | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| Idle wire scan happens before the dirty-state check | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| Wire endpoint accesses an unloaded chunk | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| `NeighbourTileCache` force-loads neighboring chunks | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| `ChunkUtil(force=false)` still loads the chunk | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| Wire sync has a >4096 systems limit with no batching | 🟠 | 🟠 | 🟠 | 🟠 | 🟠 |
| 32-bit hash is used as the wire system ID | 🟠 | 🟠 | 🟠 | 🟠 | 🟠 |
| Zone Planner request storm | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| Zone Planner oversized 32/64 KiB sync | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| Zone Planner rejected requests remain permanently pending | 🟠 | 🟠 | 🟠 | 🟠 | 🟠 |
| Marker snapshot >8192 positions has no batching | 🟠 | 🟠 | 🟠 | 🟠 | 🟠 |
| Full marker sync is sent 4 times on player join | 🟠 | 🟠 | 🟠 | 🟠 | 🟠 |
| Marker cache is keyed by `DimensionType` | 🟠 | 🟠 | 🟠 | 🟠 | 🟠 |
| Unsafe Wire SavedData enum/index decoding | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| Empty/corrupt `ZoneChunk` crash path | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| Expensive oil advancement scan | 🟠 | 🟠 | 🟠 | 🟠 | 🟠 |
| Fluid pipes contain hard `IllegalStateException` paths | 🟠 | 🟠 | 🟠 | 🟠 | 🟠 |
| Robot A* has a very large per-robot search budget | 🟠 | 🟠 | 🟠 | 🟠 | 🟠 |
| Client API references inside `TileHeatExchange` | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 |
| Debugger exceptions can escape the packet handler | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 |
| Guide recipe index is sent as one oversized packet | — | — | — | 🔴 | 🔴 |
| Stirling crafting-remainder crash | ✅ | ✅ | ✅ | ✅ | ✅ fixed |
| 26.1.2 facade dedicated-server classloading crash | — | — | — | — | ✅ fixed |
| `chunkLoading=AUTO` disables chunk loading on dedicated servers | ℹ️ | ℹ️ | ℹ️ | ℹ️ | ℹ️ |

## Legend

- 🔴 Confirmed high-impact server stability issue
- 🟠 Confirmed edge-case / scalability issue
- 🟡 Confirmed lower-risk hardening issue
- ✅ Fixed / not affected
- ℹ️ Informational / intentional behavior
- — Not applicable
