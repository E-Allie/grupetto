# Bike+ and CrossTrainer control

Grupetto uses one controller for local controls and BLE/DirCon FTMS requests.
It combines Peloton Power Zone Auto Follow, table-assisted software ERG, and
cadence/gearing-based road-load simulation with the upstream sensor, transport,
external-power, and overlay features. The motor-service integration builds on
[Douglas Bryant (dwj300)'s PR #50](https://github.com/doudar/grupetto/pull/50).

## Device gating and motor access

Physical motor control requires Peloton hardware with the confirmed `titan`
platform, or a G700/PLTN-ATR CrossTrainer model. Original Bike, Row and known
Tread platforms are excluded; a generic Topaz/PLTN-TTR01 tablet with an unknown
platform is insufficient. Controls become available after valid, fresh telemetry
arrives. Tread support remains telemetry-only: it cannot set belt speed or incline.

The existing Affernet service supplies telemetry and accepts motor commands.
Resistance uses interface token `SERVICE_ACTION`, transaction 7, an integer
level, and a checked remote exception reply. Native Auto Follow uses the existing
service's watt-target and configuration transactions. Rejected transactions and
remote exceptions are failures, not successful motor commands. Native status is
read from the validated 256-byte packet near the beginning of the telemetry
parcel, with a monotonic receipt timestamp; the evolving parcel tail is not parsed.
Stopping the sensor cancels its scope, releases the binding, and rejects late
poll replies and further writes through that connection.

## ERG controller selection

**Modes & tuning** provides ERG, Sim and Manual, plus a saved controller selection:

- **Auto** checks native capability and uses Peloton Auto Follow when supported;
  otherwise it uses software ERG.
- **Native** requires working Peloton Auto Follow. If unavailable, control stays
  stopped until the rider chooses Auto or Software.
- **Software** uses Grupetto's table-assisted feedback loop.

A fresh external power meter forces **Software** in both ERG and Sim, including
when Native is selected: Peloton Auto Follow regulates against the bike's own
power reading. Changing the selected backend or power source during control
requires **Resume control**. The UI shows the controller actually in use.

Local ERG targets and the advertised FTMS target-power range are **25–800 W**.
Keeping one range avoids changing the remote protocol when switching backends.
ERG shifts adjust target power by a saved 1–50 W per press, default 10 W.

Native capability detection checks a fresh controller status and a configuration
round trip while Auto Follow is disabled. Regulation also checks fresh status,
arming acknowledgement and native disable reasons, including a knob override.
The native loop's own ramp configuration remains in effect.

Software ERG retains the fork's power-table prediction, scheduled proportional
gain, derivative-on-measurement correction, smoothing, three-watt deadband and
two-second settling period after a predicted resistance jump. Software gain is
adjustable from 0.001 to 0.030, default 0.007. Feedback updates use each telemetry
timestamp once; repeatedly ticking the controller cannot reuse a cached sample
as fresh power. The feed-forward table covers **25–400 W**. Outside that watt
axis, software uses feedback without table prediction or gain scheduling; it
does not substitute a 400 W prediction for a higher target. Software feedback
pauses below 25 rpm.

## Terrain simulation

**Terrain resistance** is opt-in. Sim converts slope, wind, rolling resistance,
cadence, rider mass and virtual bicycle mass into a target power. Its **24 virtual
gears** determine the simulated road speed. Climbing gearing spans ratios
0.40–4.55; Road spans 1.21–4.55. Keep the training app's gear fixed when using
Grupetto's gears. Grade updates preserve the selected gear.

For cadence `c`, gear ratio `r`, total mass `m`, grade `g` in percent, wind `w`,
rolling coefficient `Crr` and aerodynamic coefficient `Cw`:

```text
v = c / 60 × r × 2.105
θ = atan(g / 100)
P = v × [m × 9.80665 × sin(θ)
       + m × 9.80665 × Crr × cos(θ)
       + 0.5 × Cw × (v + w) × abs(v + w)]
```

Here `v` is metres/second and `Cw` follows the trainer convention of air density
multiplied by drag area. This quasi-static model calculates road load; it does
not model acceleration. The SIM speed is independent of broadcast speed, so
power is not fed back into its own terrain calculation.

Rider and virtual bicycle masses can be entered in kg or lb; defaults are 75 kg
and 8 kg. The bicycle mass means a road bicycle, not the Peloton. A personal SIM
power ceiling defaults to **140 W** and is adjustable from 30–1000 W. Effective
limits also depend on the backend: native requests span 15–800 W, software
requests 25–1000 W. This internal terrain ceiling does not expand FTMS's
25–800 W explicit ERG target range. A ceiling limits requested power; measured
power may overshoot briefly.

Low cadence or load below the backend's minimum releases regulation and requests
minimum resistance. A restart requires fresh, steady cadence, starts at low
power, and ramps up over the initial five seconds. The ordinary SIM target
scheduler, personal ceiling and restart ramp remain; these are separate from
the removed blanket resistance-per-second limit. Lowering the personal ceiling
takes effect immediately in the target scheduler. The UI reports the active
request, limit, cap state and restart preview.

After an established SIM session releases at a pause or low load, explicit
**Resume control** is required by default. **Resume automatically after pauses**
is opt-in and additionally requires the bike to confirm minimum resistance and
fresh, steady cadence. A rider stop, knob override, lost data or power-source
change always requires local Resume, even with automatic resume enabled.

## Manual control, overrides and freshness

Manual provides a 0–100 resistance target. Its shifters adjust that target by
1–10 levels, default 2. Explicit local or FTMS resistance targets are sent directly
without the upstream three-levels-per-second ramp, including a lower recovery
target while cadence is zero. This describes command dispatch, not instantaneous
physical brake travel. Software ERG retains its feedback stability bounds, and
SIM retains its power scheduling limits.

The physical knob takes priority. An unexpected resistance target, native knob
disable, failed write, missing acknowledgement, sensor loss or stale data stops
regulation and latches the stop. **Stop control** does the same. Another remote
target, Request Control, Reset or Start/Resume cannot clear that latch; the rider
must use the local **Resume control** action. ERG and SIM shifters can adjust the
retained watt target or gear while suspended without commanding the brake.

Bike power/cadence/resistance and native status must be no more than 1.5 seconds
old. SIM additionally checks cadence against its one-second freshness window.
External watts expire after three seconds, but never extend the bike telemetry
watchdog. Stale, invalid or future-dated readings cannot keep control running.

The dashboard, trainer dialog and optional ride-overlay controls report the active mode, native or
software backend, and suspension reason. Fixed bottom-corner **− / +** shifters
change watts in ERG, virtual gear in Sim and resistance in Manual. Their saved
visibility and shared spacing control remain independent of overlay dragging.
Settings temporarily hide them; dragging the spacing slider previews their
positions without sending motor commands.

**Overlay → Trainer controls in overlay** shows or hides the extra controller
status and Resume/Stop row independently of the statistics. It is off by default
and the choice is saved. Hiding it removes its height as well; the full statistics
remain visible and trainer control continues unchanged. Resume/Stop remain
available under **Trainer control → Modes & tuning** in settings.

## FTMS ownership and transports

Implemented procedures are Request Control, Reset, Set Target Resistance, Set
Target Power, Start/Resume, Stop/Pause and Indoor Bike Simulation Parameters.
Only one remote client owns an active or paused control session. An idle Request
Control grant can be replaced by another client. Stop or Reset makes ownership
yieldable while telemetry remains connected, allowing a training app to release
control without disconnecting its metric stream. This supports switching control
between apps such as Scatto and MyWhoosh; a replaced owner receives a targeted
permission-loss notification. An all-zero SIM initialization
packet is accepted while idle without engaging the brake or reserving an active
control session.

Remote Pause retains the mode and target for protocol Start/Resume; Stop and
Reset discard them. Protocol Resume cannot bypass a rider/safety latch. Local
targets and shifts do not acquire a remote lease. Otherwise, the current owner's
accepted targets update local mode and UI state. **External control · Bluetooth /
DirCon** appears after an accepted active remote target or resume, not merely
Request Control, and clears on local actions, pauses and stops.

Supported target feature bits are resistance, power and optional simulation.
Power uses SINT16 watts. Target resistance uses SINT16 tenths: 0–1000 in steps of
10 represents Peloton levels 0–100. Indoor Bike Data retains whole-level
resistance telemetry. Malformed lengths, invalid or out-of-range values,
unsupported operations and requests without ownership are rejected. BLE also
rejects prepared and offset writes. DIRCON gives each TCP session its own identity
and routes control responses and permission-loss notifications to the appropriate
subscribed client.

The upstream notification queue, subscription tracking, GATT generation checks,
and Bluetooth lifecycle handling remain. BLE start/restart failures preserve an
existing DIRCON connection when that transport is enabled. Heart-rate service
changes update discovery without disconnecting its TCP clients. DIRCON write
failures close the session and release ownership; late in-flight requests cannot
reacquire it after disconnect, and callbacks run outside client-list locks.

## Speed and external power

Bike speed uses the stock Peloton power-to-speed expression in
`util/PelotonSpeed.kt`; resistance and cadence affect it through measured bike
power. Invalid or near-zero power produces zero speed. FTMS converts the result
from mph to km/h. External power replaces watts, while cadence, resistance and
speed retain their bike sources.

**External Sensors** supports a Bluetooth Cycling Power meter alongside the
heart-rate connection. It discovers service `0x1818` and subscribes to `0x2A63`.
The selected device is saved for reconnection; Disconnect clears automatic
selection, while Forget removes the saved device. Truncated packets do not
refresh their timestamp. Signed negative watts decode correctly and are clamped
to zero for ride metrics; reserved flags and trailing bytes are ignored.

Fresh external watts supply the overlay, BLE, DIRCON and ANT+ and the software
controller used for both ERG and Sim. Native watts remain visible as a smaller
**Peloton … W** comparison. After three seconds without a valid packet, metrics
fall back to native watts; after twelve seconds the meter connection is released
for reconnection. Changing, losing or replacing a power source during ERG or Sim,
including while paused, suspends control until local Resume. A live external
meter cannot hide a stalled bike connection.

Optional ANT+ transmission, the dashboard, paged sensor lists, heart-rate zones,
opt-in boot startup and Tread telemetry remain available as described in the
[README](../README.md).

## Developer emulation and validation

Five taps on the version label opens the model selector. Selecting Bike, Bike+,
CrossTrainer or Tread restarts into synthetic emulation; **Use detected hardware**
restores normal operation. Emulation uses separate preferences, never binds
Affernet or writes a physical motor, and disables BLE/DIRCON/ANT+ transmission
and external sensor discovery.

JVM coverage exercises the shared controller, native service transactions and
status freshness, software feedback, power-table boundaries, road-load maths,
SIM limits/restart policy, rider latches, source changes, ownership and transport
lifecycle. Android protocol and Compose checks use fake actuators. These tests
can verify commands, state and layout; they do not establish physical motor
response, training-app interoperability or external-meter regulation on a ridden
Bike+/CrossTrainer. Current build, lint and device-test results should be reported
with the build being evaluated. No ride recorder, telemetry capture hooks or
diagnostic replay tooling is included in this integration.
