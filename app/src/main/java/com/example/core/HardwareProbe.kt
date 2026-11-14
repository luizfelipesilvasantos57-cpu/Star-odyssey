package com.example.core

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Display
import android.view.InputDevice
import com.example.model.DeviceCapabilities

object HardwareProbe {

    fun probe(context: Context): DeviceCapabilities {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64") }

        val (renderer, vendor, glesVersion) = queryEglInfo()

        val pm = context.packageManager
        val vulkanFeature = pm.systemAvailableFeatures.firstOrNull {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
        }
        val vulkanLevelFeature = pm.systemAvailableFeatures.firstOrNull {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
        }

        val vulkanSupported = vulkanFeature != null ||
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)

        val vulkanVersionLabel = if (vulkanFeature != null) {
            val v = vulkanFeature.version
            val major = (v ushr 22) and 0x3FF
            val minor = (v ushr 12) and 0x3FF
            val patch = v and 0xFFF
            "Vulkan $major.$minor.$patch"
        } else if (vulkanSupported) {
            "Vulkan 1.0+"
        } else {
            "Indisponível (Usando GLES)"
        }

        val vulkanLevel = vulkanLevelFeature?.version ?: if (vulkanSupported) 0 else -1

        val refreshRate = queryRefreshRate(context)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val availRamGb = memInfo.availMem / (1024f * 1024f * 1024f)

        val freeStorageGb = try {
            val stat = StatFs(context.filesDir.absolutePath ?: Environment.getDataDirectory().absolutePath)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024f * 1024f * 1024f)
        } catch (_: Exception) {
            0f
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hasGyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

        val hasVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.hasVibrator() == true
        } else {
            @Suppress("DEPRECATION")
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vib?.hasVibrator() == true
        }

        val gamepads = listConnectedGamepads(context)

        return DeviceCapabilities(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            primaryAbi = primaryAbi,
            isArm64 = isArm64,
            gpuRenderer = renderer,
            gpuVendor = vendor,
            glesVersion = glesVersion,
            vulkanSupported = vulkanSupported,
            vulkanVersionLabel = vulkanVersionLabel,
            vulkanHardwareLevel = vulkanLevel,
            refreshRateHz = refreshRate,
            supports120Hz = refreshRate >= 115f,
            totalRamGb = totalRamGb,
            availableRamGb = availRamGb,
            freeStorageGb = freeStorageGb,
            hasGyroscope = hasGyro,
            hasVibrator = hasVibrator,
            connectedGamepads = gamepads
        )
    }

    fun listConnectedGamepads(context: Context): List<String> {
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return emptyList()
        val result = mutableListOf<String>()
        for (id in im.inputDeviceIds) {
            val dev = im.getInputDevice(id) ?: continue
            if (dev.isVirtual) continue
            val sources = dev.sources
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            val isJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (isGamepad || isJoystick) {
                result.add(dev.name ?: "Gamepad #$id")
            }
        }
        return result.distinct()
    }

    private fun queryRefreshRate(context: Context): Float {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.mode?.refreshRate ?: 60f
        } catch (_: Exception) {
            60f
        }
    }

    private fun queryEglInfo(): Triple<String, String, String> {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return Triple("GPU EGL Indisponível", "Desconhecido", "OpenGL ES 3.0")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return Triple("GPU EGL Indisponível", "Desconhecido", "OpenGL ES 3.0")
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            if (numConfigs[0] == 0 || configs[0] == null) {
                return Triple("GPU Padrão", "Android", "OpenGL ES 3.0")
            }

            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            val surfAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) {
                return Triple("GPU Padrão", "Android", "OpenGL ES 3.0")
            }

            EGL14.eglMakeCurrent(display, surface, surface, context)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "GPU Desconhecida"
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Desconhecido"
            val gles = GLES20.glGetString(GLES20.GL_VERSION) ?: "OpenGL ES 3.0"
            Triple(renderer, vendor, gles)
        } catch (_: Exception) {
            Triple("GPU Android (GLES)", "Sistema", "OpenGL ES 3.0")
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }
}
