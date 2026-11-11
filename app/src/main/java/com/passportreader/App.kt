package com.passportreader

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down "BC" provider that lacks algorithms JMRTD needs for
        // BAC/PACE/Chip Authentication. Replace it with the full BouncyCastle.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
