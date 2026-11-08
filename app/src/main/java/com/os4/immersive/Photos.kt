package com.os4.immersive

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.provider.MediaStore
import android.util.Size

/** The library's photos, for the photo widgets: a chosen one, cut to the part chosen. */
object Photos {
    fun allowed(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /**
     * Every photo with when it was taken (ms; when it was added, for one that does not say), newest
     * first; empty if they cannot be read.
     */
    fun library(c: Context): List<Pair<Long, Long>> = runCatching {
        c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED), null, null, null)?.use { q ->
            List(q.count) {
                q.moveToPosition(it)
                q.getLong(0) to (if (q.isNull(1)) q.getLong(2) * 1000 else q.getLong(1))
            }.sortedByDescending { it.second }
        }
    }.getOrNull() ?: emptyList()

    /** The photo whole, upright, about [px] on its longer side. */
    fun preview(c: Context, id: Long, px: Int): Bitmap? =
        runCatching { c.contentResolver.loadThumbnail(uri(id), Size(px, px), null) }.getOrNull()

    /** The part [crop] (fractions of the upright photo) of photo [id], decoded at no more than [w] x [h] needs. */
    fun cropped(c: Context, id: Long, crop: RectF, w: Int, h: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri(id))) { d, info, _ ->
            val iw = info.size.width.toFloat()
            val ih = info.size.height.toFloat()
            val f = minOf(1f, maxOf(w / (crop.width() * iw), h / (crop.height() * ih)))
            val tw = (iw * f).toInt().coerceAtLeast(1)
            val th = (ih * f).toInt().coerceAtLeast(1)
            d.setTargetSize(tw, th)
            d.crop = Rect((crop.left * tw).toInt(), (crop.top * th).toInt(),
                (crop.right * tw).toInt().coerceAtMost(tw), (crop.bottom * th).toInt().coerceAtMost(th))
        }
    }.getOrNull()
}
