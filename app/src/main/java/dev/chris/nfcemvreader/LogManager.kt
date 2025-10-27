package dev.chris.nfcemvreader

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

object LogManager {

    private const val LOG_FILE_NAME = "emv_logs.jsonl" // JSON Lines format

    private fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    fun writeLog(context: Context, logEntry: String) {
        try {
            getLogFile(context).appendText("$logEntry\n", Charsets.UTF_8)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun getLogFileUri(context: Context): Uri? {
        val logFile = getLogFile(context)
        if (!logFile.exists() || logFile.length() == 0L) {
            return null
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            logFile
        )
    }
}