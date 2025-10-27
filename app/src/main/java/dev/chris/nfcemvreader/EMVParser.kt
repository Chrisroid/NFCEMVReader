package dev.chris.nfcemvreader

import android.nfc.tech.IsoDep
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data class for the final structured log
data class TransactionLog(
    val timestamp: String,
    val error: String? = null,
    val aid: String? = null,            // Tag 84
    val appLabel: String? = null,      // Tag 50
    val pan: String? = null,            // Tag 5A (Masked)
    val amount: String? = null,
    val currency: String? = null,
    val verboseLogs: VerboseData? = null
)

data class VerboseData(
    val fciResponseHex: String?,
    val recordDataHex: String?
)

object EMVParser {

    // APDU command to Select the PSE (1PAY.SYS.DDF01)
    private val SELECT_PSE_1 = "00A404000E315041592E5359532E444446303100".hexToByteArray()
    // APDU command to Select the PSE (2PAY.SYS.DDF01) - The fallback
    private val SELECT_PSE_2 = "00A404000E325041592E5359532E444446303100".hexToByteArray()


    private fun createReadRecordApdu(recordNum: Int, sfi: Int): ByteArray {
        val p2 = (sfi shl 3) or 4
        return byteArrayOf(0x00.toByte(), 0xB2.toByte(), recordNum.toByte(), p2.toByte(), 0x00.toByte())
    }

    // APDU command to SELECT Application by AID
    private fun createSelectAidApdu(aid: ByteArray): ByteArray {
        return byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(), aid.size.toByte()) + aid + 0x00.toByte()
    }

    private val SDF = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

    fun parseEmvData(isoDep: IsoDep, verbose: Boolean): TransactionLog {
        val timestamp = SDF.format(Date())
        var fciHex: String? = null
        var recordHex: String? = null

        try {
            isoDep.connect()
            // Set a longer timeout in case the card is slow
            isoDep.timeout = 5000

            // 1. Select PSE (try 1PAY, then 2PAY)
            var pseResponse = isoDep.transceive(SELECT_PSE_1)
            var status = getStatusWords(pseResponse)

            if (status == "6A82") { // 6A82 means "File not found"
                // Log the first attempt failure if verbose
                if (verbose) fciHex = "PSE1 failed (6A82), trying PSE2...\n"

                pseResponse = isoDep.transceive(SELECT_PSE_2) // Try 2PAY
                status = getStatusWords(pseResponse)
            }

            if (status != "9000") { // 9000 is "OK"
                return TransactionLog(timestamp, error = "Failed to select PSE. Status: $status")
            }

            // 2. Parse PSE for AIDs (find tag 4F)
            val pseTlv = parseTlv(pseResponse)
            val firstAid = pseTlv["4F"]?.hexToByteArray()
                ?: return TransactionLog(timestamp, error = "No AID (Tag 4F) found in PSE response.")

            // 3. Select the first AID
            val fciResponse = isoDep.transceive(createSelectAidApdu(firstAid))
            if (!isSuccess(fciResponse)) {
                return TransactionLog(timestamp, error = "Failed to select Application AID.")
            }
            // Append to verbose log if already started
            fciHex = (fciHex ?: "") + fciResponse.toHexString()

            // 4. Parse FCI response for required tags
            val fciTlv = parseTlv(fciResponse)
            val aid = fciTlv["84"]
            val appLabel = fciTlv["50"]?.hexToByteArray()?.toString(Charsets.UTF_8)
            val sfi = fciTlv["88"]?.hexToByteArray()?.firstOrNull()?.toInt()

            var pan: String? = null

            // 5. If SFI exists, read records to find PAN
            if (sfi != null) {
                var recordNum = 1
                val allRecordData = mutableListOf<Byte>()
                while (true) {
                    try {
                        val readRecordResponse = isoDep.transceive(createReadRecordApdu(recordNum, sfi))
                        if (!isSuccess(readRecordResponse)) {
                            // Stop if record read fails (e.g., 6A83 "Record not found")
                            break
                        }
                        allRecordData.addAll(readRecordResponse.dropLast(2)) // Add data, drop SW1/SW2
                        recordNum++
                    } catch (e: Exception) {
                        break // Stop on any communication error
                    }
                }

                if (allRecordData.isNotEmpty()) {
                    val recordTlv = parseTlv(allRecordData.toByteArray())
                    if (verbose) recordHex = allRecordData.toByteArray().toHexString()
                    pan = recordTlv["5A"]?.let { maskPan(it) }
                }
            }

            return TransactionLog(
                timestamp = timestamp,
                aid = aid,
                appLabel = appLabel,
                pan = pan,
                amount = null, // N/A, see explanation
                currency = null, // N/A, see explanation
                verboseLogs = if(verbose) VerboseData(fciHex, recordHex) else null
            )

        } catch (e: IOException) {
            return TransactionLog(timestamp, error = "NFC Communication Error: ${e.message}")
        } finally {
            try {
                isoDep.close()
            } catch (e: IOException) {
                // Ignore close error
            }
        }
    }

    /**
     * A basic recursive TLV parser.
     * Returns a map of Tag (String) -> Value (Hex String).
     */
    fun parseTlv(data: ByteArray): Map<String, String> {
        val tlvMap = mutableMapOf<String, String>()
        var i = 0
        while (i < data.size) {
            // Check for 0x00 or 0xFF padding
            if (data[i] == 0x00.toByte() || data[i] == 0xFF.toByte()) {
                i++
                continue
            }

            // 1. Find Tag
            var tag: String
            var tagBytes = 1
            val b1 = data[i].toInt() and 0xFF

            if ((b1 and 0x1F) == 0x1F) { // Two-byte tag
                tagBytes = 2
                val b2 = data[i + 1].toInt() and 0xFF
                tag = data.copyOfRange(i, i + 2).toHexString().uppercase()
            } else { // One-byte tag
                tag = data.copyOfRange(i, i + 1).toHexString().uppercase()
            }
            i += tagBytes

            if (i >= data.size) break // Malformed TLV

            // 2. Find Length
            var len: Int
            var lenBytes = 1
            val l1 = data[i].toInt() and 0xFF
            if (l1 > 127) { // Long form
                lenBytes += l1 and 0x7F
                // Note: This parser only supports 1 extra byte for length (up to 255)
                // A full parser would handle multi-byte lengths.
                if (lenBytes == 2) {
                    len = data[i+1].toInt() and 0xFF
                } else {
                    // Unsupported length, skip
                    break
                }
            } else { // Short form
                len = l1
            }
            i += lenBytes

            if (i + len > data.size) break // Malformed TLV

            // 3. Get Value
            val value = data.copyOfRange(i, i + len)
            tlvMap[tag] = value.toHexString()

            // 4. Recurse if constructed (first bit of tag is 1)
            if ((b1 and 0x20) == 0x20) {
                tlvMap.putAll(parseTlv(value))
            }
            i += len
        }
        return tlvMap
    }

    /**
     * Masks a PAN string (e.g., 4111111111111111) to 411111******1111.
     * Assumes PAN is hex and may have an 'F' padding.
     */
    fun maskPan(panHex: String): String {
        val pan = panHex.takeWhile { it.uppercaseChar() != 'F' }
        if (pan.length < 13) return "PAN_TOO_SHORT"

        return pan.replaceRange(6, pan.length - 4, "******")
    }

    // --- Utilities ---

    private fun getStatusWords(response: ByteArray): String {
        if (response.size < 2) return "0000" // No status words
        return response.copyOfRange(response.size - 2, response.size).toHexString().uppercase()
    }

    private fun isSuccess(response: ByteArray): Boolean {
        return getStatusWords(response) == "9000"
    }

    // String "0A1B" -> ByteArray[0x0A, 0x1B]
    fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    // ByteArray[0x0A, 0x1B] -> String "0A1B"
    fun ByteArray.toHexString(): String =
        joinToString("") { "%02X".format(it) }
}