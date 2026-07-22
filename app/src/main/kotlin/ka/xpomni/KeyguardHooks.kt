@file:android.annotation.SuppressLint("PrivateApi", "BlockedPrivateApi")

package ka.xpomni

import android.content.Intent
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.util.Locale

private const val KEYGUARD_INDICATION_CONTROLLER_GOOGLE =
    "com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle"
private const val KEYGUARD_INDICATION_CONTROLLER =
    "com.android.systemui.statusbar.KeyguardIndicationController"
private const val BATTERY_STATUS = "com.android.settingslib.fuelgauge.BatteryStatus"

private const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
private const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"
private const val EXTRA_TEMPERATURE = "temperature"
private const val MICRO_UNITS = 1_000_000f
private const val TENTHS = 10f
private const val KEYGUARD_BATTERY_HOOK_ID = "keyguard.battery"
private const val KEYGUARD_POWER_HOOK_ID = "keyguard.power"

@Volatile
private var maxChargingCurrentAmps = 0f

@Volatile
private var maxChargingVoltageVolts = 0f

@Volatile
private var batteryTemperatureCelsius = 0f

internal fun XpOmniModule.hookKeyguardChargingInfo(classLoader: ClassLoader) {
    val indicationClass = classLoader.loadFirstClass(
        KEYGUARD_INDICATION_CONTROLLER_GOOGLE,
        KEYGUARD_INDICATION_CONTROLLER,
    )
    val batteryStatusClass = classLoader.loadClass(BATTERY_STATUS)

    hookConstructors(batteryStatusClass, KEYGUARD_BATTERY_HOOK_ID) {
        handleBatteryStatus(this)
    }

    hookMethods(indicationClass, KEYGUARD_POWER_HOOK_ID, "computePowerIndication") {
        handlePowerIndication(this)
    }
}

internal fun XpOmniModule.resolveKeyguardHotReloadHook(
    hookId: String?,
    executable: Executable,
): Hooker? {
    val className = executable.declaringClass.name
    val legacyBattery = executable is Constructor<*> && className == BATTERY_STATUS
    val legacyPower =
        (className == KEYGUARD_INDICATION_CONTROLLER_GOOGLE ||
            className == KEYGUARD_INDICATION_CONTROLLER) &&
            executable.name == "computePowerIndication"
    return when {
        hookId == KEYGUARD_BATTERY_HOOK_ID || legacyBattery ->
            Hooker { chain -> handleBatteryStatus(chain) }

        hookId == KEYGUARD_POWER_HOOK_ID || legacyPower ->
            Hooker { chain -> handlePowerIndication(chain) }

        else -> null
    }
}

private fun handleBatteryStatus(chain: Chain): Any? =
    with(chain) {
        proceed().also {
            (args.firstOrNull() as? Intent)?.cacheChargingInfo()
        }
    }

private fun handlePowerIndication(chain: Chain): Any? =
    with(chain) {
        val result = proceed()
        if (result is String) appendChargingInfo(result) else result
    }

private fun Intent.cacheChargingInfo() {
    maxChargingCurrentAmps = getIntExtra(EXTRA_MAX_CHARGING_CURRENT, 0) / MICRO_UNITS
    maxChargingVoltageVolts = getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, 0) / MICRO_UNITS
    batteryTemperatureCelsius = getIntExtra(EXTRA_TEMPERATURE, 0) / TENTHS
}

private fun appendChargingInfo(text: String): String {
    val current = maxChargingCurrentAmps
    val voltage = maxChargingVoltageVolts
    val temperature = batteryTemperatureCelsius

    if (current <= 0f || voltage <= 0f) return text

    return String.format(
        Locale.US,
        "%s\n%.1fW (%.1fV, %.1fA) - %.0f\u00b0C",
        text,
        current * voltage,
        voltage,
        current,
        temperature,
    )
}

private fun ClassLoader.loadFirstClass(vararg names: String): Class<*> {
    for (name in names) {
        val clazz = runCatching { loadClass(name) }.getOrNull()
        if (clazz != null) return clazz
    }
    throw ClassNotFoundException(names.joinToString())
}
