package dev.chris.nfcemvreader

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import dev.chris.nfcemvreader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private val gson = GsonBuilder().setPrettyPrinting().create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            binding.logTextView.text = "NFC is not available on this device."
            return
        }

        binding.shareLogsButton.setOnClickListener {
            shareLogs()
        }
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter != null) {
            setupForegroundDispatch()
        }
    }

    private fun setupForegroundDispatch() {
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)
        val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
        val techLists = arrayOf(arrayOf(IsoDep::class.java.name))

        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, filters, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            if (tag != null) {
                processNfcTag(tag)
            }
        }
    }

    private fun processNfcTag(tag: Tag) {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            updateLog("Not an IsoDep card.")
            return
        }

        val isVerbose = binding.verboseSwitch.isChecked

        // Run card communication on a background thread
        lifecycleScope.launch(Dispatchers.IO) {
            val logEntry = EMVParser.parseEmvData(isoDep, isVerbose)
            val logJson = gson.toJson(logEntry)

            // Save log
            LogManager.writeLog(this@MainActivity, logJson)

            // Update UI on the main thread
            withContext(Dispatchers.Main) {
                updateLog(logJson)
            }
        }
    }

    private fun shareLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logFileUri = LogManager.getLogFileUri(this@MainActivity)
            if (logFileUri == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No logs to share.", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, logFileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            withContext(Dispatchers.Main) {
                startActivity(Intent.createChooser(shareIntent, "Share EMV Logs"))
            }
        }
    }

    private fun updateLog(message: String) {
        binding.logTextView.text = message
    }
}