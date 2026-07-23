import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
}

val appMinSdk = 31

fun readIntLe(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)

fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value shr 8).toByte()
    bytes[offset + 2] = (value shr 16).toByte()
    bytes[offset + 3] = (value shr 24).toByte()
}

fun readUleb128(bytes: ByteArray, offsetRef: IntArray): Int {
    var result = 0
    var shift = 0
    var offset = offsetRef[0]
    while (true) {
        val value = bytes[offset++].toInt() and 0xff
        result = result or ((value and 0x7f) shl shift)
        if ((value and 0x80) == 0) break
        shift += 7
    }
    offsetRef[0] = offset
    return result
}

fun updateDexHashes(bytes: ByteArray) {
    val sha1 = MessageDigest.getInstance("SHA-1")
    sha1.update(bytes, 32, bytes.size - 32)
    System.arraycopy(sha1.digest(), 0, bytes, 12, 20)

    val adler32 = Adler32()
    adler32.update(bytes, 12, bytes.size - 12)
    writeIntLe(bytes, 8, adler32.value.toInt())
}

fun stripDexDebugInfo(input: ByteArray): ByteArray {
    val bytes = input.clone()
    val classDefsSize = readIntLe(bytes, 0x60)
    val classDefsOff = readIntLe(bytes, 0x64)

    for (classIndex in 0 until classDefsSize) {
        val classDefOff = classDefsOff + classIndex * 32
        writeIntLe(bytes, classDefOff + 16, -1)

        val classDataOff = readIntLe(bytes, classDefOff + 24)
        if (classDataOff == 0) continue

        val cursor = intArrayOf(classDataOff)
        val staticFieldsSize = readUleb128(bytes, cursor)
        val instanceFieldsSize = readUleb128(bytes, cursor)
        val directMethodsSize = readUleb128(bytes, cursor)
        val virtualMethodsSize = readUleb128(bytes, cursor)

        repeat(staticFieldsSize + instanceFieldsSize) {
            readUleb128(bytes, cursor)
            readUleb128(bytes, cursor)
        }

        repeat(directMethodsSize + virtualMethodsSize) {
            readUleb128(bytes, cursor)
            readUleb128(bytes, cursor)
            val codeOff = readUleb128(bytes, cursor)
            if (codeOff != 0) {
                writeIntLe(bytes, codeOff + 8, 0)
            }
        }
    }

    updateDexHashes(bytes)
    return bytes
}

fun dexEntryOrder(name: String): Int {
    val suffix = Regex("""classes(\d*)\.dex""").matchEntire(name)?.groupValues?.get(1)
    return suffix?.takeIf { it.isNotEmpty() }?.toInt() ?: 1
}

fun shouldDropApkEntry(entry: ZipEntry, dexEntryName: Regex): Boolean {
    val name = entry.name
    return dexEntryName.matches(name) ||
        name == "META-INF/MANIFEST.MF" ||
        name.startsWith("META-INF/com/") ||
        (name.startsWith("META-INF/") &&
            (name.endsWith(".RSA") || name.endsWith(".DSA") ||
                name.endsWith(".EC") || name.endsWith(".SF")))
}

val localProperties = Properties().apply {
    val file = project.rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

android {
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "ka.xpomni"
        minSdk = appMinSdk
        targetSdk = 37
        versionCode = 14
        versionName = "1.4.0"
    }

    signingConfigs {
        if (localProperties.getProperty("storeFile") != null) {
            create("config") {
                storeFile = project.rootProject.file(localProperties.getProperty("storeFile"))
                storePassword = localProperties.getProperty("storePassword")
                keyAlias = localProperties.getProperty("keyAlias")
                keyPassword = localProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        configureEach {
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            signingConfig = if (localProperties.getProperty("storeFile") != null) {
                signingConfigs.getByName("config")
            } else {
                signingConfigs.getByName("debug")
            }
            vcsInfo.include = false
            isMinifyEnabled = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "kotlin/**",
                "META-INF/com/**",
                "META-INF/*.kotlin_module",
                "META-INF/kotlin*",
                "META-INF/versions/**",
                "DebugProbesKt.bin",
            )
        }
    }

    dependenciesInfo.includeInApk = false
    enableKotlin = true
    namespace = "ka.xpomni"
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly("io.github.libxposed:api:102.0.0")
}

tasks.register("stripReleaseDexDebugInfo") {
    dependsOn("packageRelease")

    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val workDir = layout.buildDirectory.dir("stripReleaseDexDebugInfo").get().asFile
        delete(workDir)
        workDir.mkdirs()

        val unsignedApk = workDir.resolve("unsigned.apk")
        val alignedApk = workDir.resolve("aligned.apk")
        val strippedDexInputDir = workDir.resolve("stripped-dex-input")
        val compactedDexDir = workDir.resolve("compacted-dex")
        strippedDexInputDir.mkdirs()
        compactedDexDir.mkdirs()
        val strippedDexInputs = mutableListOf<File>()
        val dexEntryName = Regex("""classes(\d*)?\.dex""")

        ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (dexEntryName.matches(entry.name)) {
                    zip.getInputStream(entry).use { input ->
                        val strippedDex = strippedDexInputDir.resolve(entry.name)
                        strippedDex.writeBytes(stripDexDebugInfo(input.readBytes()))
                        strippedDexInputs += strippedDex
                    }
                }
            }
        }

        val windows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
        var sdkPath = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (sdkPath == null && rootProject.file("local.properties").exists()) {
            val sdkProperties = Properties()
            rootProject.file("local.properties").inputStream().use { sdkProperties.load(it) }
            sdkPath = sdkProperties.getProperty("sdk.dir")
        }
        if (sdkPath == null) {
            throw GradleException("ANDROID_HOME or sdk.dir is required to strip dex debug info.")
        }

        val buildTools = File(sdkPath, "build-tools/${android.buildToolsVersion}")
        val d8Jar = buildTools.resolve("lib/d8.jar")
        val zipalign = buildTools.resolve(if (windows) "zipalign.exe" else "zipalign")
        val apksigner = buildTools.resolve(if (windows) "apksigner.bat" else "apksigner")
        val javaExecutable = File(System.getProperty("java.home"), "bin/${if (windows) "java.exe" else "java"}")

        if (!d8Jar.isFile) {
            throw GradleException("D8 jar was not found at ${d8Jar.absolutePath}.")
        }
        if (!javaExecutable.isFile) {
            throw GradleException("Java executable was not found at ${javaExecutable.absolutePath}.")
        }

        if (strippedDexInputs.isNotEmpty()) {
            providers.exec {
                commandLine(
                    listOf(
                        javaExecutable.absolutePath,
                        "-cp",
                        d8Jar.absolutePath,
                        "com.android.tools.r8.D8",
                        "--release",
                        "--min-api",
                        appMinSdk.toString(),
                        "--output",
                        compactedDexDir.absolutePath,
                    ) + strippedDexInputs.map { it.absolutePath },
                )
            }.result.get().assertNormalExitValue()
        }

        val compactedDex = compactedDexDir.listFiles { file ->
            file.isFile && dexEntryName.matches(file.name)
        }?.sortedBy { dexEntryOrder(it.name) }.orEmpty()

        if (strippedDexInputs.isNotEmpty() && compactedDex.isEmpty()) {
            throw GradleException("D8 did not emit any compacted dex files.")
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(unsignedApk))).use { output ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (!shouldDropApkEntry(entry, dexEntryName)) {
                        val outputEntry = ZipEntry(entry.name).apply {
                            // Android 11+ rejects target-R+ APKs when resources.arsc is
                            // compressed. Preserve all originally uncompressed entries;
                            // zipalign will add the required data alignment afterwards.
                            if (!entry.isDirectory &&
                                (entry.method == ZipEntry.STORED || entry.name == "resources.arsc")
                            ) {
                                method = ZipEntry.STORED
                                size = entry.size
                                compressedSize = entry.size
                                crc = entry.crc
                            }
                        }
                        output.putNextEntry(outputEntry)
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { input ->
                                input.copyTo(output)
                            }
                        }
                        output.closeEntry()
                    }
                }
            }

            compactedDex.forEach { file ->
                output.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.closeEntry()
            }
        }

        providers.exec {
            commandLine(zipalign.absolutePath, "-f", "-P", "16", "4", unsignedApk.absolutePath, alignedApk.absolutePath)
        }.result.get().assertNormalExitValue()

        val signing = android.buildTypes.getByName("release").signingConfig
        if (signing == null || signing.storeFile == null) {
            throw GradleException("Release signing config is required after stripping dex debug info.")
        }

        val signCommand = mutableListOf(
            apksigner.absolutePath,
            "sign",
            "--ks",
            signing.storeFile!!.absolutePath,
        )
        signing.storePassword?.let {
            signCommand += listOf("--ks-pass", "pass:$it")
        }
        signing.keyAlias?.let {
            signCommand += listOf("--ks-key-alias", it)
        }
        signing.keyPassword?.let {
            signCommand += listOf("--key-pass", "pass:$it")
        }
        signing.storeType?.let {
            signCommand += listOf("--ks-type", it)
        }
        signCommand += listOf("--out", apk.absolutePath, alignedApk.absolutePath)

        providers.exec {
            commandLine(signCommand)
        }.result.get().assertNormalExitValue()

        val versionName = android.defaultConfig.versionName ?: "dev"
        val versionCode = android.defaultConfig.versionCode ?: 0
        val timestamp = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .format(LocalDateTime.now())
        val namedApk = apk.parentFile.resolve("Xpomni-v$versionName-$versionCode-$timestamp.apk")
        apk.copyTo(namedApk, overwrite = true)
    }
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        finalizedBy("stripReleaseDexDebugInfo")
    }
}
