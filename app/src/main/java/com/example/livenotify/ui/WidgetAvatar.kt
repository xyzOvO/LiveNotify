package com.example.livenotify.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache
import com.example.livenotify.monitor.WidgetPalette
import com.example.livenotify.net.Http
import okhttp3.Request

/**
 * 小组件头像：下载 → 采样 → 裁圆描边，带内存缓存。
 *
 * 刷新器（WidgetUpdater）和集合数据的工厂（WidgetListService.Factory）都要用它，
 * 所以抽成共享 object；两者都跑在主进程，缓存能真正复用。
 * 缓存 key 带明暗——描边色随主题变，切主题后不能命中旧图。
 */
internal object WidgetAvatar {
    /** 96×96 ARGB ≈ 36KB/张，64 张约 2.3MB，控制 Parcel 大小 */
    private val cache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(url: String?, dark: Boolean): Bitmap? {
        if (url.isNullOrBlank()) return null
        val key = if (dark) "d|$url" else "l|$url"
        cache.get(key)?.let { return it }
        return try {
            val bytes = Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                resp.body?.bytes() ?: return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val circle = toCircular(bmp, dark)
            if (circle !== bmp) bmp.recycle()
            cache.put(key, circle)
            circle
        } catch (_: Throwable) {
            null
        }
    }

    /** 头像和平台图标都没有时的昵称首字圆形占位 */
    fun initial(nickname: String, dark: Boolean): Bitmap {
        val c = WidgetPalette.colors(dark)
        val size = 48
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(c.avatarBg)
        })
        val letter = nickname.trim().take(1).ifBlank { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(c.name)
            textAlign = Paint.Align.CENTER
            textSize = 24f
            isFakeBoldText = true
        }
        canvas.drawText(letter, size / 2f, size / 2f - (text.descent() + text.ascent()) / 2f, text)
        return out
    }

    private fun toCircular(src: Bitmap, dark: Boolean): Bitmap {
        val size = minOf(src.width, src.height)
        if (size <= 0) return src
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val radius = size / 2f
        val scale = maxOf(size / src.width.toFloat(), size / src.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(matrix)
            }
        }
        canvas.drawCircle(radius, radius, radius, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(WidgetPalette.colors(dark).avatarBorder)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawCircle(radius, radius, radius - 1.25f, border)
        return out
    }
}
