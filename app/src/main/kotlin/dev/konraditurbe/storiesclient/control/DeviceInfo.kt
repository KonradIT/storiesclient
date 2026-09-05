package dev.konraditurbe.storiesclient.control

/** Device details for the status pill, accumulated from get_system_info, device_state and MCU settings. */
class DeviceInfo {
    // get_system_info
    var socBuildInfo: String? = null
    var mcuBuildInfo: String? = null
    var serial: String? = null
    var model: String? = null
    var deviceUuid: String? = null
    /** Cloud-OTA version strings; lastOtaVersion is the human-readable firmware version. */
    var lastOtaVersion: String? = null
    var pendingOtaVersion: String? = null

    // device_state BatteryStateTable (glasses)
    var glassesBatteryPct = -1
    var glassesChargingStatus = -1
    var glassesChargerType = -1
    var glassesHealth = -1
    var glassesTempDeciC = Int.MIN_VALUE
    var glassesVoltageMv = -1

    // device_state TitanStateTable (case)
    var caseBatteryPct = -1
    var caseSerial: String? = null

    var lowStorage = false
    var zeroStorage = false

    /** MCU setting VideoCaptureDurationMs (0x8004); -1 = unknown. */
    var videoDurationMs = -1
    /** MCU setting UserEarconVolume (0x8036), 0-100; -1 = unknown. */
    var earconVolume = -1
    /** MCU setting UserCaptureEarconDisable (0x8035): 0 = shutter sound on, 1 = off, -1 = unknown. */
    var captureEarconDisable = -1

    val isCharging: Boolean get() = glassesChargingStatus == 2 || glassesChargingStatus == 5
}
