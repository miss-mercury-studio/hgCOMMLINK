package studio.missmercury.hgcommlink

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import chummer.core.CharacterFile
import chummer.core.DataPack
import java.io.File
import kotlin.concurrent.thread

/** M0 spike: measures how long the Chummer data pack takes to load on this device. */
class BenchmarkActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 13f; setPadding(32, 96, 32, 32); setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(out) })
        log("Running benchmark...")
        thread(name = "benchmark") { run() }
    }

    private fun log(msg: String) {
        Log.i("HGBENCH", msg)
        runOnUiThread { out.append(msg + "\n") }
    }

    private fun usedHeapMb() = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }) / 1_000_000
    private fun nativeMb() = Debug.getNativeHeapAllocatedSize() / 1_000_000

    private fun run() {
        try {
            val dataDir = File(filesDir, "data").apply { mkdirs() }
            val t0 = System.nanoTime()
            var bytes = 0L
            for (name in assets.list("data").orEmpty()) {
                File(dataDir, name).outputStream().use { o -> assets.open("data/$name").use { bytes += it.copyTo(o) } }
            }
            val viper = File(filesDir, "Viper_career.chum5")
            assets.open("fixtures/Viper_career.chum5").use { i -> viper.outputStream().use { i.copyTo(it) } }
            log("Copied assets: ${bytes / 1_000_000} MB in ${(System.nanoTime() - t0) / 1_000_000} ms (one-off)")

            repeat(3) { run ->
                System.gc()
                val heap0 = usedHeapMb()
                val t1 = System.nanoTime()
                val pack = DataPack.load(dataDir)
                val loadMs = (System.nanoTime() - t1) / 1_000_000
                val t2 = System.nanoTime()
                val c = CharacterFile.load(viper)
                val ids = c.sourceIds()
                val resolved = ids.count { pack[it.second] != null }
                val charMs = (System.nanoTime() - t2) / 1_000_000
                log("Run ${run + 1}: pack ${pack.byId.size} entries / ${pack.fileCount} files in $loadMs ms; " +
                    "Viper ${c.name} loaded + $resolved/${ids.size} ids resolved in $charMs ms; " +
                    "heap +${usedHeapMb() - heap0} MB (now ${usedHeapMb()} MB), native ${nativeMb()} MB")
            }
            log("DONE")
        } catch (t: Throwable) {
            log("FAILED: $t")
            Log.e("HGBENCH", "failed", t)
        }
    }
}
