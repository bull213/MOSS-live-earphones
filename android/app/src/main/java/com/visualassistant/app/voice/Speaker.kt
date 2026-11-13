package com.visualassistant.app.voice

interface Speaker {
    fun speak(text: String, onStart: () -> Unit = {}, onDone: () -> Unit = {})
    fun stop()
    fun isSpeaking(): Boolean
    fun shutdown()
}
