package ka.xpomni

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

@SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedApi")
class XpOmniModule : XposedModule() {
    private var processName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        return runCatching {
            if (!isFludTrackerUpdaterIdle()) {
                log(Log.WARN, TAG, "hot reload rejected: Flud tracker update is running")
                return false
            }

            val pixelState = pixelLauncherHotReloadState()
            if (pixelState != null) {
                runCatching {
                    param.setSavedInstanceState(pixelState)
                }.onFailure { error ->
                    log(Log.ERROR, TAG, "hot reload rejected: failed to save Pixel Launcher state", error)
                    return false
                }
            }

            clearGitHubHotReloadState()
            if (!releasePixelLauncherHotReloadState()) {
                log(Log.ERROR, TAG, "hot reload rejected: failed to release Pixel Launcher state", null)
                return false
            }
            true
        }.getOrElse { error ->
            log(Log.ERROR, TAG, "hot reload preparation failed", error)
            false
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        runCatching {
            restorePixelLauncherHotReloadState(param.savedInstanceState)
            replaceHooksForHotReload(param.oldHookHandles)
        }.onFailure { error ->
            log(Log.ERROR, TAG, "hot reload finalization failed", error)
            param.oldHookHandles.forEach { handle ->
                runCatching { handle.unhook() }
            }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader

        runHook("deoptimize system server") {
            deoptimizeSystemServer(classLoader)
        }

        runHook("hook launcher sleep receiver") {
            hookLauncherSleepReceiver(classLoader)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runHook("hook WindowManagerService") {
                hookWindowManagerService(classLoader)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runHook("hook ActivityTaskManagerService") {
                hookActivityTaskManagerService(classLoader)
            }
            runOptionalHook("hook HyperOS") {
                hookHyperOS(classLoader)
            }
        }

        runHook("hook ScreenCapture") {
            hookScreenCapture(classLoader)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runHook("hook ActivityManagerService") {
                hookActivityManagerService(classLoader)
            }
        }

        runHook("hook DisplayControl") {
            hookDisplayControl(classLoader)
        }

        runHook("hook VirtualDisplayAdapter") {
            hookVirtualDisplayAdapter(classLoader)
        }

        runOptionalHook("hook ScreenshotHardwareBuffer") {
            hookScreenshotHardwareBuffer(classLoader)
        }

        runOptionalHook("hook OneUI") {
            hookOneUI(classLoader)
        }

        runHook("hook WindowState") {
            hookWindowState(classLoader)
        }

        runOptionalHook("hook Oplus") {
            hookOplus(classLoader)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return

        val classLoader = param.classLoader
        when (val packageName = param.packageName) {
            OPLUS_SCREENSHOT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    runOptionalHook("hook OplusScreenCapture") {
                        hookOplusScreenCapture(classLoader)
                    }
                }
                hookScreenshotHardwareBufferIfPresent(classLoader)
                hookScreenCaptureInPackage(classLoader, packageName)
            }

            FLYME_SYSTEMUIEX, OPLUS_APPPLATFORM -> {
                hookScreenshotHardwareBufferIfPresent(classLoader)
                hookScreenCaptureInPackage(classLoader, packageName)
            }

            SYSTEMUI, MIUI_SCREENSHOT -> {
                if (packageName == SYSTEMUI) {
                    runHook("hook SystemUI screenshot mute") {
                        hookSystemUiScreenshotMute(classLoader)
                    }
                    if (isMainPackageProcess(packageName)) {
                        runBiometricHook(classLoader)
                        runOptionalHook("hook keyguard charging info") {
                            hookKeyguardChargingInfo(classLoader)
                        }
                        runOptionalHook("hook quick settings tile rows") {
                            hookQuickSettingsTileRows()
                        }
                    }
                }
                hookScreenCaptureInPackage(classLoader, packageName)
            }

            SHARE_SHEET_PACKAGE -> {
                hookHideDirectShare(classLoader)
            }

            ANDROID_FRAMEWORK, INTENT_RESOLVER -> {
                // Static scope covers both old and new platform split points.
            }

            ANDROID_SYSTEM_INTELLIGENCE -> {
                hookShareTargets()
            }

            GITHUB -> {
                runHook("hook GitHub FastPass") {
                    hookGitHubFastPass(classLoader)
                }
            }

            FLUD_PLUS -> {
                runHook("update Flud default trackers") {
                    hookFludTrackerUpdater()
                }
            }

            PIXEL_LAUNCHER, LAUNCHER3 -> {
                hookPixelLauncherFeatures(classLoader)
            }

            else -> Unit
        }
    }

    private fun isMainPackageProcess(packageName: String): Boolean =
        processName == null || processName == packageName
}
