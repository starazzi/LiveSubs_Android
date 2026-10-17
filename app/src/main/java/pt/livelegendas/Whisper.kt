package pt.livelegendas

/** Ponte para o whisper.cpp (reconhecimento de voz offline, multilingue). */
object Whisper {
    init { System.loadLibrary("livesubs_whisper") }

    @JvmStatic external fun nativeInit(path: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeTranscribe(handle: Long, audio: FloatArray, lang: String, threads: Int): ByteArray
}
