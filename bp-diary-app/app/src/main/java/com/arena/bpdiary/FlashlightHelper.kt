package com.arena.bpdiary

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Управление вспышкой (фонариком) камеры для световых уведомлений.
 * Позволяет подать серию ярких световых вспышек при напоминании для глухих и слабослышащих.
 */
object FlashlightHelper {

    private val handler = Handler(Looper.getMainLooper())
    private var isFlashing = false

    fun flash(ctx: Context, count: Int = 5, onMs: Long = 200L, offMs: Long = 150L) {
        if (Build.VERSION.SDK_INT < 23) return
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cameraId = findTorchCameraId(cm) ?: return

        stop()
        isFlashing = true

        var current = 0
        fun step() {
            if (!isFlashing) return
            if (current >= count) {
                stopTorch(cm, cameraId)
                isFlashing = false
                return
            }

            // Включаем фонарик
            setTorch(cm, cameraId, true)
            handler.postDelayed({
                if (!isFlashing) return@postDelayed
                // Выключаем фонарик
                setTorch(cm, cameraId, false)
                current++
                handler.postDelayed({
                    step()
                }, offMs)
            }, onMs)
        }

        step()
    }

    fun stop() {
        isFlashing = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun findTorchCameraId(cm: CameraManager): String? {
        return try {
            cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                flash && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun setTorch(cm: CameraManager, cameraId: String, on: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                cm.setTorchMode(cameraId, on)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopTorch(cm: CameraManager, cameraId: String) {
        setTorch(cm, cameraId, false)
    }
}
