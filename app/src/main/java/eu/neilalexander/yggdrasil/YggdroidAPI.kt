package io.meshnet.yggdroid

import android.content.Context
import android.content.Intent
import eu.neilalexander.yggdrasil.*

/**
 * Простой API для Python/Kivy
 * Обертка над оригинальным кодом Yggdrasil
 */
class YggdroidAPI private constructor(private val context: Context) {
    
    private val config = ConfigurationProxy(context)
    private val app = context.applicationContext as GlobalApplication
    
    companion object {
        @Volatile
        private var instance: YggdroidAPI? = null
        
        fun getInstance(context: Context): YggdroidAPI {
            return instance ?: synchronized(this) {
                instance ?: YggdroidAPI(context).also { instance = it }
            }
        }
    }
    
    // ========== Управление VPN ==========
    
    fun startVpn() {
        val intent = Intent(context, PacketTunnelProvider::class.java)
        intent.action = PacketTunnelProvider.ACTION_START
        context.startService(intent)
    }
    
    fun stopVpn() {
        val intent = Intent(context, PacketTunnelProvider::class.java)
        intent.action = PacketTunnelProvider.ACTION_STOP
        context.startService(intent)
    }
    
    fun isRunning(): Boolean {
        return app.getCurrentState() != State.Disabled
    }
    
    // ========== Конфигурация ==========
    
    fun generateNewConfig() {
        config.resetJSON()
    }
    
    fun generateNewKeys() {
        config.resetKeys()
    }
    
    fun getConfigJson(): String {
        return config.getJSON().toString()
    }
    
    // ========== Пиры ==========
    
    fun addPeer(peerUri: String) {
        config.updateJSON { json ->
            json.getJSONArray("Peers").put(peerUri)
        }
    }
    
    fun removePeer(peerUri: String) {
        config.updateJSON { json ->
            val peers = json.getJSONArray("Peers")
            val newPeers = org.json.JSONArray()
            for (i in 0 until peers.length()) {
                val peer = peers.getString(i)
                if (peer != peerUri) {
                    newPeers.put(peer)
                }
            }
            json.put("Peers", newPeers)
        }
    }
    
    fun getPeers(): List<String> {
        val peers = config.getJSON().getJSONArray("Peers")
        return (0 until peers.length()).map { peers.getString(it) }
    }
    
    // ========== DNS ==========
    
    fun addDnsServer(server: String) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val current = prefs.getString(KEY_DNS_SERVERS, "") ?: ""
        val newServers = if (current.isEmpty()) server else "$current,$server"
        prefs.edit().putString(KEY_DNS_SERVERS, newServers).apply()
    }
    
    fun removeDnsServer(server: String) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val current = prefs.getString(KEY_DNS_SERVERS, "") ?: ""
        val servers = current.split(",").filter { it.isNotEmpty() && it != server }
        prefs.edit().putString(KEY_DNS_SERVERS, servers.joinToString(",")).apply()
    }
    
    fun getDnsServers(): List<String> {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY_DNS_SERVERS, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    }
    
    fun setChromeFix(enabled: Boolean) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(KEY_ENABLE_CHROME_FIX, enabled).apply()
    }
    
    // ========== Статус ==========
    
    fun getIpAddress(): String {
        // Получаем из YggStateReceiver (нужен receiver)
        return ""
    }
    
    fun getPeersCount(): Int {
        // Получаем из YggStateReceiver
        return 0
    }
    
    fun getVersion(): String {
        return mobile.Mobile.getVersion()
    }
    
    // ========== Multicast ==========
    
    fun setMulticastEnabled(enabled: Boolean) {
        config.multicastListen = enabled
        config.multicastBeacon = enabled
    }
    
    fun isMulticastEnabled(): Boolean {
        return config.multicastListen
    }
}
