package com.enki.netrix.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

object LogManager {
    
    enum class Level { 
        INFO,   // Connection states (Connected, Disconnected)
        WARN,   // Warnings (QUIC Blocked, etc.)
        ERROR,  // Errors (Timeout, Socket error)
        BYPASS  // DPI Bypass operations (Split applied, etc.)
    }
    
    data class LogEntry(
        val time: String,
        val level: Level,
        val message: String
    )
    
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs
    
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val MAX_LOGS = 1000 // Keep a bit more logs
    
    @Volatile
    var enabled = true
    
    fun i(message: String) = log(Level.INFO, message)
    fun w(message: String) = log(Level.WARN, message)
    fun e(message: String) = log(Level.ERROR, message)
    fun bypass(message: String) = log(Level.BYPASS, message)
    
    private fun log(level: Level, message: String) {
        if (!enabled) return
        
        val entry = LogEntry(
            time = timeFormat.format(Date()),
            level = level,
            message = message
        )
        
        synchronized(this) {
            val current = _logs.value.toMutableList()
            current.add(entry)
            if (current.size > MAX_LOGS) {
                current.removeAt(0)
            }
            _logs.value = current
        }
    }
    
    fun clear() {
        _logs.value = emptyList()
    }
}