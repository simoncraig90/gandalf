package uk.simon.walk

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * openWakeWord streaming wake-word detector (Apache-2.0 models, ONNX Runtime): melspectrogram.onnx ->
 * embedding_model.onnx -> hey_gandalf.onnx, one score per 80 ms (1280 samples at 16 kHz).
 * Mirrors openwakeword.utils.AudioFeatures streaming mode: mel of the newest 1280 samples plus 480 of context
 * (8 frames), embedding of the last 76 mel frames, wake model over the last 16 embeddings.
 * Not thread-safe; use from the one wake thread.
 */
class OwwDetector(private val ctx: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); setInterOpNumThreads(1) }
    private fun load(name: String) = env.createSession(ctx.assets.open("oww/$name").use { it.readBytes() }, opts)
    private val mel = load("melspectrogram.onnx")
    private val emb = load("embedding_model.onnx")
    private val wake = load("hey_gandalf.onnx")

    private val raw = FloatArray(CHUNK + 480)          // newest samples, int16 scale
    private val pending = FloatArray(CHUNK); private var npend = 0
    private val melBuf = FloatArray(76 * 32)
    private val feat = FloatArray(16 * 96)
    private val primedFeat = FloatArray(16 * 96)       // embeddings of faint noise: what an idle model sees
    @Volatile var lastScore = 0f

    init {
        // Prime like AudioFeatures does: run ~2.5 s of low-level noise through the pipeline once, keep the features.
        val rnd = java.util.Random(7)
        val noise = FloatArray(40 * CHUNK) { (rnd.nextInt(2001) - 1000).toFloat() }
        for (i in 0 until 40) score(noise, i * CHUNK, CHUNK)
        feat.copyInto(primedFeat); reset()
    }

    /** Back to the idle state (call when the audio stream has a gap). */
    fun reset() {
        raw.fill(0f); npend = 0; melBuf.fill(1f); primedFeat.copyInto(feat); lastScore = 0f
    }

    /** Feeds [n] samples (int16 scale, as floats) from [x] at [off]. Returns the highest wake score of the complete 80 ms chunks, or -1 if none completed. */
    fun feed(x: FloatArray, off: Int, n: Int): Float {
        var best = -1f; var p = off; val end = off + n
        while (p < end) {
            val take = minOf(CHUNK - npend, end - p)
            System.arraycopy(x, p, pending, npend, take); npend += take; p += take
            if (npend == CHUNK) { best = maxOf(best, score(pending, 0, CHUNK)); npend = 0 }
        }
        return best
    }

    private fun score(x: FloatArray, off: Int, n: Int): Float {
        System.arraycopy(raw, n, raw, 0, raw.size - n); System.arraycopy(x, off, raw, raw.size - n, n)
        // mel: (1, 1760) -> 8 frames x 32; transform x/10 + 2; append to the 76-frame buffer
        val m = OnnxTensor.createTensor(env, FloatBuffer.wrap(raw), longArrayOf(1, raw.size.toLong())).use { t ->
            mel.run(mapOf(mel.inputNames.first() to t)).use { r -> (r[0] as OnnxTensor).floatBuffer.let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } } }
        }
        val frames = m.size / 32
        System.arraycopy(melBuf, frames * 32, melBuf, 0, melBuf.size - frames * 32)
        for (i in m.indices) melBuf[melBuf.size - m.size + i] = m[i] / 10f + 2f
        // embedding: last 76 frames -> 96
        val e = OnnxTensor.createTensor(env, FloatBuffer.wrap(melBuf), longArrayOf(1, 76, 32, 1)).use { t ->
            emb.run(mapOf(emb.inputNames.first() to t)).use { r -> (r[0] as OnnxTensor).floatBuffer.let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } } }
        }
        System.arraycopy(feat, 96, feat, 0, feat.size - 96); e.copyInto(feat, feat.size - 96, 0, 96)
        val s = OnnxTensor.createTensor(env, FloatBuffer.wrap(feat), longArrayOf(1, 16, 96)).use { t ->
            wake.run(mapOf(wake.inputNames.first() to t)).use { r -> (r[0] as OnnxTensor).floatBuffer.get(0) }
        }
        lastScore = s; return s
    }

    fun close() { try { mel.close(); emb.close(); wake.close(); opts.close() } catch (_: Throwable) {} }

    companion object {
        const val CHUNK = 1280
        /** Score at or above which the wake word counts (chosen offline, see wakeword/README.md). */
        const val THRESHOLD = 0.5f
        /** Consecutive 80 ms chunks at or above THRESHOLD. */
        const val PATIENCE = 1
    }
}
