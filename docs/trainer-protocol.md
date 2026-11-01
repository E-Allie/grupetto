# Optional trainer status and explicit recovery

Two Grupetto-specific characteristics extend the existing FTMS service. Standard
FTMS clients can ignore them. They use the same BLE/DirCon session identities and
control ownership checks as the standard control point.

| Characteristic | UUID | Properties |
|---|---|---|
| Status/capabilities | `d7b74e01-42ce-4e68-9db0-80cae6f10a01` | Read, Notify |
| Explicit recovery | `d7b74e02-42ce-4e68-9db0-80cae6f10a01` | Write, controllable bikes only |

Status is exactly 12 bytes:

| Offset | Meaning |
|---|---|
| 0–1 | ASCII `GR` |
| 2 | Protocol version, currently 1 |
| 3 | Power encoding: 1 = selected Grupetto power, no additional EMA to invert |
| 4 | Cadence EMA alpha as integer percent, currently 70 |
| 5 | Fault code: 0 none, 1 resistance timeout, 2 rider override, 3 safety/backend fault, 4 telemetry loss, 5 command failure, 6 configuration change, 7 rider Stop |
| 6 | Flags: bit 0 connected, bit 1 suspended, bit 2 remotely recoverable |
| 7–10 | 32-bit big-endian fault-generation token |
| 11 | Reserved, zero |

The token changes when a fault changes or clears. Status changes are sent before
the corresponding FTMS control-permission-loss notification. A client should
subscribe and read once during its handshake. Filtering is negotiated per metric;
fault changes do not reset filtering history. A reconnect resets negotiation.

An explicit recovery write is exactly five bytes: version 1, followed by the
four-byte generation token from a fresh status read. The controller checks the
session's current lease, fresh bike feedback, the token and recoverable fault
type under the actuator lock. The only remotely recoverable code is 1.
Malformed, stale, foreign-client, rider-stop and safety-stop requests fail.

Successful recovery releases regulation, clears the fault, and waits for a new
target. It never replays the old target. It is not an ordinary target retry and
must not be sent automatically by clients. A failed/ambiguous request leaves the
client paused; a later explicit rider action may read status and try again.
Clients must also honor a local Stop while recovery is in flight.

Scatto maintains legacy behavior when these characteristics are absent. With
version 1 present, it passes selected power through, retains cadence compensation,
and exposes explicit recovery for the supported fault. Unknown advertised protocol
versions fail the handshake rather than silently applying an incorrect filter.

Calibration capture, backups and service discovery are upstream product features.
Developer ride logs, replay harnesses and their recording hooks are not part of
this protocol or the production integration.
