package com.mcqstudio.pro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import java.io.File
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQ = 1001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(NativeVideoBridge(this), "AndroidEncoder")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                vw: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, FILE_CHOOSER_REQ)
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    Log.d("MCQStudio_WebView", "${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQ) {
            val results = if (resultCode == Activity.RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null) {
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}

class NativeVideoBridge(private val activity: Activity) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var tempFile: File? = null
    private var outFileName: String = "MCQ_Video.mp4"
    private var width = 1920
    private var height = 1080
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private var isPlanar = false

    @JavascriptInterface
    fun startEncoder(w: Int, h: Int, fps: Int, bitrate: Int, fileName: String): Boolean {
        return try {
            width = w
            height = h
            outFileName = fileName
            tempFile = File(activity.cacheDir, "temp_render_${System.currentTimeMillis()}.mp4")
            if (tempFile!!.exists()) tempFile!!.delete()

            val chosenColorFormat = selectColorFormat()
            colorFormat = chosenColorFormat
            isPlanar = (chosenColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            trackIndex = -1
            muxerStarted = false
            true
        } catch (e: Exception) {
            Log.e("NativeVideoBridge", "Failed to startEncoder", e)
            false
        }
    }

    private fun selectColorFormat(): Int {
        return try {
            val testCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val caps = testCodec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            testCodec.release()

            val supported = caps.colorFormats
            if (supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            } else if (supported.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            } else {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }
        } catch (e: Exception) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }
    }

    @JavascriptInterface
    fun encodeFrame(dataUrl: String, ptsUs: Long): Boolean {
        return try {
            val base64 = dataUrl.substringAfter(",")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            val yuv = bitmapToYUV(bmp, width, height, isPlanar)
            bmp.recycle()

            val c = codec ?: return false
            val inIdx = c.dequeueInputBuffer(50_000)
            if (inIdx >= 0) {
                val buf: ByteBuffer = c.getInputBuffer(inIdx) ?: return false
                buf.clear()
                buf.put(yuv)
                c.queueInputBuffer(inIdx, 0, yuv.size, ptsUs, 0)
            }
            drainEncoder(false)
            true
        } catch (e: Exception) {
            Log.e("NativeVideoBridge", "Failed to encodeFrame", e)
            false
        }
    }

    @JavascriptInterface
    fun finishEncoder(): String {
        return try {
            val c = codec ?: return "Error: Encoder not started"
            val inIdx = c.dequeueInputBuffer(50_000)
            if (inIdx >= 0) {
                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainEncoder(true)
            c.stop()
            c.release()
            codec = null

            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            muxer = null

            val f = tempFile
            if (f != null && f.exists() && f.length() > 0) {
                saveToGallery(f, outFileName)
            } else {
                "Error: No encoded data written"
            }
        } catch (e: Exception) {
            Log.e("NativeVideoBridge", "Failed to finishEncoder", e)
            "Error: ${e.message}"
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 10_000)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val m = muxer ?: break
                trackIndex = m.addTrack(c.outputFormat)
                m.start()
                muxerStarted = true
            } else if (outIdx >= 0) {
                val encodedData = c.getOutputBuffer(outIdx)
                if (encodedData != null) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size != 0 && muxerStarted) {
                        encodedData.position(info.offset)
                        encodedData.limit(info.offset + info.size)
                        muxer?.writeSampleData(trackIndex, encodedData, info)
                    }
                }
                c.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    private fun bitmapToYUV(bmp: Bitmap, w: Int, h: Int, planar: Boolean): ByteArray {
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h * 3 / 2)
        var yIndex = 0
        val uOffset = w * h
        val vOffset = uOffset + (w * h / 4)
        var uvIndex = uOffset
        var uPlanarIdx = uOffset
        var vPlanarIdx = vOffset

        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = argb[j * w + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    if (planar) {
                        if (uPlanarIdx < vOffset) yuv[uPlanarIdx++] = u.coerceIn(0, 255).toByte()
                        if (vPlanarIdx < yuv.size) yuv[vPlanarIdx++] = v.coerceIn(0, 255).toByte()
                    } else {
                        if (uvIndex + 1 < yuv.size) {
                            yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                            yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                        }
                    }
                }
            }
        }
        return yuv
    }

    private fun saveToGallery(file: File, displayName: String): String {
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MCQStudio")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                file.delete()
                return "Movies/MCQStudio/$displayName"
            } catch (e: Exception) {
                Log.e("NativeVideoBridge", "Failed to save file to gallery stream", e)
            }
        }
        return file.absolutePath
    }
}
