package com.dlnaplayer.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.dlnaplayer.android.model.DlnaDevice
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages persisting paired/saved DLNA devices so device names remain constant
 * and devices are stored for future casting sessions without requiring rescans.
 */
class DeviceRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Cleans transient prefixes such as "[AV] " or "[TV] " added by Samsung/LG UPnP services,
     * producing a clean, constant friendly name.
     */
    fun cleanFriendlyName(rawName: String): String {
        return rawName
            .replace(Regex("^\\[(AV|TV|Speaker|Audio)\\]\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { rawName }
    }

    /**
     * Retrieves all saved devices, ordered with the most recently used first.
     */
    fun getSavedDevices(): List<DlnaDevice> {
        val jsonStr = prefs.getString(KEY_SAVED_DEVICES, null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<DlnaDevice>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DlnaDevice(
                        udn = obj.optString("udn"),
                        friendlyName = obj.optString("friendlyName"),
                        manufacturer = obj.optString("manufacturer"),
                        modelName = obj.optString("modelName"),
                        locationUrl = obj.optString("locationUrl"),
                        ipAddress = obj.optString("ipAddress"),
                        avTransportControlUrl = obj.optString("avTransportControlUrl"),
                        renderingControlUrl = obj.optString("renderingControlUrl").takeIf { it.isNotBlank() },
                        iconUrl = obj.optString("iconUrl").takeIf { it.isNotBlank() },
                        isSaved = true,
                        customName = obj.optString("customName").takeIf { it.isNotBlank() }
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing saved devices: ${e.message}")
            emptyList()
        }
    }

    /**
     * Finds a saved device by IP address or UDN.
     */
    fun getSavedDevice(ip: String, udn: String? = null): DlnaDevice? {
        val devices = getSavedDevices()
        return devices.firstOrNull { it.ipAddress == ip }
            ?: (if (!udn.isNullOrBlank()) devices.firstOrNull { it.udn == udn } else null)
    }

    /**
     * Saves or updates a device in persistent storage.
     * Retains any user-set customName unless an updated customName is explicitly given.
     */
    fun saveDevice(device: DlnaDevice, customName: String? = null): DlnaDevice {
        val devices = getSavedDevices().toMutableList()
        val existingIndex = devices.indexOfFirst { it.ipAddress == device.ipAddress || (it.udn.isNotBlank() && it.udn == device.udn) }

        val finalCustomName = customName?.takeIf { it.isNotBlank() }
            ?: existingIndex.takeIf { it >= 0 }?.let { devices[it].customName }
            ?: device.customName

        val cleanedName = cleanFriendlyName(device.friendlyName)

        val updatedDevice = device.copy(
            friendlyName = cleanedName,
            customName = finalCustomName,
            isSaved = true
        )

        if (existingIndex >= 0) {
            devices[existingIndex] = updatedDevice
        } else {
            devices.add(0, updatedDevice)
        }

        saveDeviceList(devices)
        setLastConnectedDevice(updatedDevice)
        Log.i(TAG, "Persisted device: ${updatedDevice.displayName} (${updatedDevice.ipAddress})")
        return updatedDevice
    }

    /**
     * Updates the constant name of a device (user customization).
     */
    fun updateDeviceName(ip: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return

        val devices = getSavedDevices().toMutableList()
        val index = devices.indexOfFirst { it.ipAddress == ip }
        if (index >= 0) {
            val updated = devices[index].copy(customName = trimmed)
            devices[index] = updated
            saveDeviceList(devices)

            val lastConnected = getLastConnectedDevice()
            if (lastConnected?.ipAddress == ip) {
                setLastConnectedDevice(updated)
            }
        }
    }

    /**
     * Removes a device from saved storage.
     */
    fun removeSavedDevice(ip: String) {
        val devices = getSavedDevices().toMutableList()
        devices.removeAll { it.ipAddress == ip }
        saveDeviceList(devices)

        val lastConnected = getLastConnectedDevice()
        if (lastConnected?.ipAddress == ip) {
            prefs.edit().remove(KEY_LAST_CONNECTED_DEVICE).apply()
        }
    }

    /**
     * Returns the last connected device for instant auto-selection on app launch.
     */
    fun getLastConnectedDevice(): DlnaDevice? {
        val jsonStr = prefs.getString(KEY_LAST_CONNECTED_DEVICE, null) ?: return null
        return try {
            val obj = JSONObject(jsonStr)
            DlnaDevice(
                udn = obj.optString("udn"),
                friendlyName = obj.optString("friendlyName"),
                manufacturer = obj.optString("manufacturer"),
                modelName = obj.optString("modelName"),
                locationUrl = obj.optString("locationUrl"),
                ipAddress = obj.optString("ipAddress"),
                avTransportControlUrl = obj.optString("avTransportControlUrl"),
                renderingControlUrl = obj.optString("renderingControlUrl").takeIf { it.isNotBlank() },
                iconUrl = obj.optString("iconUrl").takeIf { it.isNotBlank() },
                isSaved = true,
                customName = obj.optString("customName").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Records the last connected device.
     */
    fun setLastConnectedDevice(device: DlnaDevice) {
        try {
            val obj = JSONObject().apply {
                put("udn", device.udn)
                put("friendlyName", cleanFriendlyName(device.friendlyName))
                put("manufacturer", device.manufacturer)
                put("modelName", device.modelName)
                put("locationUrl", device.locationUrl)
                put("ipAddress", device.ipAddress)
                put("avTransportControlUrl", device.avTransportControlUrl)
                put("renderingControlUrl", device.renderingControlUrl ?: "")
                put("iconUrl", device.iconUrl ?: "")
                put("customName", device.customName ?: "")
            }
            prefs.edit().putString(KEY_LAST_CONNECTED_DEVICE, obj.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error saving last connected device: ${e.message}")
        }
    }

    private fun saveDeviceList(devices: List<DlnaDevice>) {
        try {
            val array = JSONArray()
            for (dev in devices) {
                val obj = JSONObject().apply {
                    put("udn", dev.udn)
                    put("friendlyName", dev.friendlyName)
                    put("manufacturer", dev.manufacturer)
                    put("modelName", dev.modelName)
                    put("locationUrl", dev.locationUrl)
                    put("ipAddress", dev.ipAddress)
                    put("avTransportControlUrl", dev.avTransportControlUrl)
                    put("renderingControlUrl", dev.renderingControlUrl ?: "")
                    put("iconUrl", dev.iconUrl ?: "")
                    put("customName", dev.customName ?: "")
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_SAVED_DEVICES, array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving device list", e)
        }
    }

    companion object {
        private const val TAG = "DeviceRepository"
        private const val PREFS_NAME = "dlna_saved_devices_pref"
        private const val KEY_SAVED_DEVICES = "saved_devices_json"
        private const val KEY_LAST_CONNECTED_DEVICE = "last_connected_device_json"
    }
}
