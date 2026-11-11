// AGP 9 compiles Kotlin itself; the Kotlin plugin is listed only to pin the Kotlin version.
plugins {
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
}
