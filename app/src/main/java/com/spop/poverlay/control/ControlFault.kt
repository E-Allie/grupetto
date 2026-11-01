package com.spop.poverlay.control

/** Stable protocol codes. Only an unacknowledged adjustment permits remote, explicit recovery. */
enum class ControlFault(val code: Int, val remoteRecoverable: Boolean = false) {
    ResistanceTimeout(1, true), RiderOverride(2), Safety(3), TelemetryLost(4),
    CommandFailed(5), ConfigurationChanged(6), RiderStop(7);
}
