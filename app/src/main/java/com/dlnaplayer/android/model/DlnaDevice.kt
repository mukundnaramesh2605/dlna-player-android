package com.dlnaplayer.android.model

/**
 * Model representing a discovered UPnP / DLNA MediaRenderer device on the LAN.
 */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val locationUrl: String,
    val ipAddress: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String?,
    val iconUrl: String? = null
) {
    val subtitle: String
        get() = buildString {
            if (manufacturer.isNotBlank()) append(manufacturer)
            if (modelName.isNotBlank() && modelName != friendlyName) {
                if (isNotEmpty()) append(" • ")
                append(modelName)
            }
            if (isNotEmpty()) append(" • ")
            append(ipAddress)
        }
}
