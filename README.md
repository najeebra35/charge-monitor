# ⚡ Charge Monitor (Android, Kotlin + Jetpack Compose)

Charger connect pannaa live-ah kaattum:
- **Watts** (live + 2-min graph + session peak)
- **Charging speed**: Slow / Normal / Fast / Super fast / Hyper
- **Charging type**: USB PD, PD PPS, Quick Charge 2/3/3.5, VOOC/SuperVOOC/Warp, Xiaomi Turbo/HyperCharge, Samsung AFC/Super Fast, Wireless, PC USB
- **Voltage, Current (mA/A)**, charger max voltage/current, negotiated watts
- **Battery**: level, temperature, health, technology, mAh, estimated capacity, design capacity, health %, cycle count, time to full
- **Advanced**: phone kernel (sysfs) data, kidaichaa

Min Android 8.0. Permissions edhuvum thevai illa.

## APK eppadi edukkuradhu

### Option 1: GitHub (PC illama, mobile-laye)
1. github.com-la free account create pannunga, puthu repo create pannunga.
2. Indha folder-la irukkura files ellathayum repo-la upload pannunga (`.github` folder-um sethu).
3. **Actions** tab → "Build APK" → run aagi 5–7 min-la mudiyum.
4. Run-ah open panni **Artifacts → ChargeMonitor-APK** download panni, zip-ah open panni APK install pannunga.

### Option 2: Android Studio (PC)
1. Android Studio → **Open** → indha `ChargeMonitor` folder.
2. Gradle sync mudinjadhum phone connect panni ▶ Run, illa
   **Build → Build APK(s)**.

## Accuracy note
- Power = battery voltage × battery current (battery-kulla pogura power). Charger output 10–20% adhigama irukkum.
- Android-la PD/QC/VOOC sollura public API illa. Phone kernel data padikka mudinja exact protocol varum; illana voltage + watts vachi estimate pannum.
- Sila phones (Samsung maari) current-ah konjam late-ah update pannum, adhanaala reading stable aaga 5–10 sec wait pannunga.
