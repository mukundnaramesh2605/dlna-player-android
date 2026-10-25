package com.dlnaplayer.android.model

/**
 * Model representing a discovered or saved UPnP / DLNA MediaRenderer device on the LAN.
 */
data class DlnaDevice(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val locationUrl: String,
    val ipAddress: String,
    val avTransportControlUrl: String,
    val renderingControlUrl: String? = null,
    val iconUrl: String? = null,
    val isSaved: Boolean = false,
    val customName: String? = null
) {
    /**
     * Constant user-facing name. If the user set a custom name, it takes precedence.
     */
    val displayName: String
        get() = customName?.takeIf { it.isNotBlank() } ?: friendlyName

    val subtitle: String
        get() = buildString {
            if (manufacturer.isNotBlank()) append(manufacturer)
            if (modelName.isNotBlank() && modelName != displayName) {
                if (isNotEmpty()) append(" • ")
                append(modelName)
            }
            if (isNotEmpty()) append(" • ")
            append(ipAddress)
        }
}
