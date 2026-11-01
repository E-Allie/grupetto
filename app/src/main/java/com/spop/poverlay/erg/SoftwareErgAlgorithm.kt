package com.spop.poverlay.erg

enum class SoftwareErgAlgorithm(val label: String) {
    FirmwareTable("Firmware table"), UpstreamModel("Upstream learned model");
    companion object {
        fun read(value: String?) = entries.firstOrNull { it.name == value } ?: FirmwareTable
    }
}
