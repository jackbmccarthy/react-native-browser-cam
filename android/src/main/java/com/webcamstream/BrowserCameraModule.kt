package com.webcamstream

import com.facebook.react.bridge.*
import java.io.File

class BrowserCameraModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "BrowserCameraModule"
    }

    @ReactMethod
    fun getDocumentDirectory(promise: Promise) {
        try {
            val dir = "file://" + reactApplicationContext.filesDir.absolutePath + "/"
            promise.resolve(dir)
        } catch (e: Exception) {
            promise.reject("E_GET_DIR_FAILED", e)
        }
    }

    @ReactMethod
    fun listRecordings(promise: Promise) {
        try {
            val dir = File(reactApplicationContext.filesDir, "recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val files = dir.listFiles() ?: emptyArray()
            val result = Arguments.createArray()
            files.filter { it.name.endsWith(".mp4") }
                 .sortedByDescending { it.lastModified() }
                 .forEach {
                     result.pushString("file://" + it.absolutePath)
                 }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("E_LIST_FAILED", e)
        }
    }

    @ReactMethod
    fun deleteRecording(uri: String, promise: Promise) {
        try {
            val path = uri.replace("file://", "")
            val file = File(path)
            if (file.exists()) {
                val deleted = file.delete()
                promise.resolve(deleted)
            } else {
                promise.resolve(false)
            }
        } catch (e: Exception) {
            promise.reject("E_DELETE_FAILED", e)
        }
    }
}
