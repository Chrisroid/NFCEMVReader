# Android NFC EMV Reader

A lightweight Android app to read basic, non-sensitive EMV data from NFC-enabled credit/debit cards and store masked logs.

## Features

* Reads basic card data using Android's `NfcAdapter`.
* Parses EMV TLV data to extract:
    * **Tag 84**: Application Identifier (AID)
    * **Tag 50**: Application Label
    * **Tag 5A**: Primary Account Number (PAN)
* **Securely masks the PAN** in logs (e.g., `411111******1111`).
* Logs each successful read as a JSON object to a local file.
* "Share Logs" button to export the log file.
* "Verbose Mode" switch to include raw TLV hex data in the log.

---
![WhatsApp Image 2025-10-27 at 11 37 20_554b82fa](https://github.com/user-attachments/assets/0637ade1-63a4-4e74-ba0b-dc94eefaf20c)


## How to Build and Run

1.  **Prerequisites**: Android Studio (latest stable version).
2.  **Clone/Download**: Get the source code.
3.  **Open**: Open the project folder in Android Studio.
4.  **Build**: Gradle will sync. Build the project (Build > Make Project).
5.  **Run**: Connect an Android device with NFC capabilities.
6.  **Install**: Run the app (Run > Run 'app').

---

## How to Use

1.  Ensure NFC is **enabled** on your Android device (usually in Settings > Connections > NFC).
2.  Open the app.
3.  (Optional) Toggle "Verbose Mode" on.
4.  Tap an NFC-enabled (contactless) EMV card to the **center of the back of your phone**.
5.  The app will read the card, and the JSON log for that transaction will appear on the screen.
6.  This log is appended to `emv_logs.jsonl` in the app's private storage.
7.  Click "Share Logs" to export this file using a standard Android share sheet.

---

## Assumptions & Data Notice

This app is for educational and diagnostic purposes.

* **PAN Masking**: It reads **Tag 5A** to get the PAN and immediately masks it *before* logging.
* **No Transactional Data**: This app **cannot** read transactional tags like `9F02` (Amount) or `5F2A` (Currency). These tags are not stored on the card; they are set by a POS terminal *during* a transaction. The log will show `null` for these fields.
* **No Sensitive Data**: This app does not read, interpret, or store Track 2 data, cardholder names, or any cryptographic keys.
* **Card Compatibility**: The app attempts to select both "1PAY.SYS.DDF01" and "2PAY.SYS.DDF01" application environments. This should cover most Visa, Mastercard, and other common cards, but may not work for every card.

---

## Example Inputs and Outputs

### Example Input (Raw TLV Data)

The app doesn't take direct text input. It reads this data from the card via NFC commands.

1.  **FCI Response (after selecting AID):**
    `6F298407A0000000031010500456495341A51A8801015F2D04656E66729F1101019F120A436172746520566973619000`
    * *Key Tags*: `84` (AID), `50` (App Label), `88` (SFI)

2.  **READ RECORD Response (from SFI):**
    `701A5A0841111111111111115F24032512319F080200309000`
    * *Key Tag*: `5A` (PAN)

### Example Output (JSON Log)

#### Normal Mode (`Verbose Mode: OFF`)

This is the standard log entry.

```json
{
  "timestamp": "2025-10-27T10:30:05+0100",
  "aid": "A0000000031010",
  "appLabel": "VISA",
  "pan": "411111******1111",
  "amount": null,
  "currency": null
}
```

### Verbose Mode (Verbose Mode: ON)
This log includes the raw hex data from the card responses.

```json
{
  "timestamp": "2025-10-27T10:30:15+0100",
  "aid": "A0000000031010",
  "appLabel": "VISA",
  "pan": "411111******1111",
  "amount": null,
  "currency": null,
  "verboseLogs": {
    "fciResponseHex": "6F298407A00000s00031010500456495341A51A8801015F2D04656E66729F1101019F120A436172746520566973619000",
    "recordDataHex": "701A5A0841111111111111115F24032512319F08020030"
  }
}
```
