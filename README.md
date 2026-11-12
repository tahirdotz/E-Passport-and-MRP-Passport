# Passport Reader (Android)

Reads both kinds of passport, entirely on the phone (no network, nothing saved to disk):

| Passport | What is read | How |
|---|---|---|
| **MRP** (machine-readable, no chip) | Everything in the MRZ: names, document number, nationality, date of birth, sex, expiry, personal number, issuing state. Also the photo cropped from the page and the page image. | Camera + on-device OCR (ML Kit). Every check digit (incl. composite) must pass, and two frames must agree. |
| **ePassport** (chip logo on the cover) | All of the above **from the chip**, plus the chip photo (DG2, JPEG or JPEG 2000), DG11 (full name, place of birth, address, profession…) and DG12 (issue date, issuing authority, endorsements…). | NFC + JMRTD. PACE first (via EF.CardAccess), BAC fallback. |

Security checks on ePassports:
- **Passive Authentication**: every data group read is hashed and compared with the signed SOD; the SOD signature is verified with the Document Signer certificate.
- **Chip Authentication** (DG14): proves the chip isn't a clone.
- **Printed page vs chip**: the scanned MRZ is compared field by field with the chip.
- Not done: validating the signer up to the country's CSCA root (needs the ICAO/national master list).

## Passport delivery slips (Bangladesh)

**Delivery slips** on the home screen reads, from each slip: application ID, name, passport type, delivery type, tentative collection date, NID ("Personal No"), date of birth and the barcode.

- **Fast batch scanning**: barcode and text are read together on every camera frame. Fields that cross-check (application ID = end of the barcode, name = Given Name + Surname, 17-digit NID starts with the birth year) are accepted from a single frame. Each slip saves itself with a beep, so just put the next slip under the camera.
- **Import photos / scans** from the gallery (up to 100 at a time). Sideways scans are rotated automatically.
- **Check flags**: any value no second source confirmed (e.g. a date half-covered by a pen mark) is marked *check*. Tap a slip to fix it.
- **Export CSV**: opens correctly in Excel / Google Sheets (long numbers kept intact). Share it to email, Drive, WhatsApp, etc.
- Saved slips stay in the app's private storage on the phone until you tap **Clear all**.

## Run it on your phone

1. Android Studio → **File › Open** → select this `PassportReader` folder. Let Gradle sync.
2. On the phone: **Settings › About phone** → tap **Build number** 7 times → **Developer options** → enable **USB debugging**.
3. Plug the phone in, accept the "Allow USB debugging" prompt on the phone.
4. Pick the phone in the device dropdown and press **Run ▶**.
5. For ePassports, turn on **NFC** in the phone settings.

Command line alternative: `./gradlew installDebug`

## Testing tips

- **MRZ scan**: good light, no glare on the laminate, fill the frame with the photo page, tap to focus. The two `<<<` lines must be inside the dashed band.
- **Chip**: close the passport and put the phone flat on the cover (some countries have the chip in the photo page instead). Move slowly; when it vibrates, hold still for 5–15 s (the photo is the slow part). Remove thick or metal cases.
- "Chip rejected the key" means the document number or dates don't match. Use **Edit document number / dates**.
- If the MRZ won't scan, **Enter details manually** reads the chip with just document number + dates.

## Code map

- `mrz/Mrz.kt`: TD3 parser, check digits, OCR error repair, multi-frame agreement (unit-tested in `app/src/test`).
- `scan/ScanActivity.kt`: CameraX + ML Kit text recognition.
- `chip/ChipReader.kt`: JMRTD: PACE/BAC, DG1/2/11/12/14, SOD, passive + chip authentication.
- `chip/ChipActivity.kt`: NFC reader mode screen.
- `ui/ResultActivity.kt`: result screen.

Run tests: `./gradlew testDebugUnitTest`
