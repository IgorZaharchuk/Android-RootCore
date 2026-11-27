import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
    id("com.android.library")
}

android {
    namespace = "com.rootcore"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}



val generatedRootCoreAssets =
    layout.buildDirectory.dir("generated/rootcore/assets")

android.sourceSets.getByName("main").assets.srcDir(generatedRootCoreAssets)

val bootstrapReleaseDir =
    project(":root-core-bootstrap").layout.buildDirectory.dir(
        "outputs/apk/release"
    )

val extractRootCoreBootstrapDex = tasks.register("extractRootCoreBootstrapDex") {
    dependsOn(":root-core-bootstrap:assembleRelease")
    inputs.dir(bootstrapReleaseDir)
    outputs.file(generatedRootCoreAssets.map { it.file("main.jar") })

    doLast {
        val releaseDir = bootstrapReleaseDir.get().asFile
        val apkFiles = releaseDir.listFiles { file ->
            file.isFile && file.name.endsWith(".apk")
        }?.toList().orEmpty()

        check(apkFiles.size == 1) {
            "Expected exactly one bootstrap Release APK in $releaseDir, found ${apkFiles.size}"
        }

        val apkFile = apkFiles.single()
        val output = generatedRootCoreAssets.get().file("main.jar").asFile
        output.parentFile.mkdirs()

        ZipFile(apkFile).use { apk ->
            ZipOutputStream(output.outputStream()).use { jar ->
                val dexEntries = apk.entries().asSequence()
                    .filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }
                    .sortedBy { it.name }
                    .toList()

                check(dexEntries.isNotEmpty()) {
                    "No DEX files found in bootstrap Release APK: $apkFile"
                }

                dexEntries.forEach { entry ->
                    jar.putNextEntry(ZipEntry(entry.name))
                    apk.getInputStream(entry).use { input -> input.copyTo(jar) }
                    jar.closeEntry()
                }
            }
        }

        println("Root Core bootstrap Release DEX packaged: ${output.absolutePath}")
    }
}

tasks.named("preBuild").configure {
    dependsOn(extractRootCoreBootstrapDex)
}
