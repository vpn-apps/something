package io.github.saeeddev94.xray.helper

import XrayCore.XrayCore
import android.util.Base64
import io.github.saeeddev94.xray.Settings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

class LinkHelper(link: String) {

    private val success: Boolean
    private val outbound: JSONObject?

    init {
        val base64: String = XrayCore.json(link)
        val byteArray = Base64.decode(base64, Base64.DEFAULT)
        val decoded = String(byteArray)
        val response = try { JSONObject(decoded) } catch (error: JSONException) { JSONObject() }
        val data = response.optJSONObject("data") ?: JSONObject()
        val outbounds = data.optJSONArray("outbounds") ?: JSONArray()
        success = response.optBoolean("success", false)
        outbound = if (outbounds.length() > 0) outbounds[0] as JSONObject else null
    }

    companion object {
        fun remark(uri: URI): String {
            val name = uri.fragment ?: ""
            return name.ifEmpty { "New Profile" }
        }
    }

    fun isValid(): Boolean {
        return success && outbound != null
    }

    fun json(): String {
        return config().toString(2) + "\n"
    }

    private fun log(): JSONObject {
        val log = JSONObject()
        log.put("loglevel", "warning")
        return log
    }

    private fun dns(): JSONObject {
        val dns = JSONObject()
        val servers = JSONArray()
        servers.put(Settings.primaryDns)
        servers.put(Settings.secondaryDns)
        dns.put("servers", servers)
        return dns
    }

    private fun inbounds(): JSONArray {
        val inbounds = JSONArray()

        val socks = JSONObject()
        socks.put("listen", Settings.socksAddress)
        socks.put("port", Settings.socksPort.toInt())
        socks.put("protocol", "socks")

        val settings = JSONObject()
        settings.put("udp", true)
        if (Settings.socksUsername.trim().isNotEmpty() && Settings.socksPassword.trim().isNotEmpty()) {
            val account = JSONObject()
            account.put("user", Settings.socksUsername)
            account.put("pass", Settings.socksPassword)
            val accounts = JSONArray()
            accounts.put(account)

            settings.put("auth", "password")
            settings.put("accounts", accounts)
        }

        val sniffing = JSONObject()
        sniffing.put("enabled", true)
        val sniffingDestOverride = JSONArray()
        sniffingDestOverride.put("http")
        sniffingDestOverride.put("tls")
        sniffingDestOverride.put("quic")
        sniffing.put("destOverride", sniffingDestOverride)

        socks.put("settings", settings)
        socks.put("sniffing", sniffing)
        socks.put("tag", "socks")

        inbounds.put(socks)

        return inbounds
    }

    private fun outbounds(): JSONArray {
        val outbounds = JSONArray()

        val proxy = JSONObject(outbound!!.toString())
        if (proxy.has("name")) proxy.remove("name")
        proxy.put("tag", "proxy")

        val direct = JSONObject()
        direct.put("protocol", "freedom")
        direct.put("tag", "direct")

        val block = JSONObject()
        block.put("protocol", "blackhole")
        block.put("tag", "block")

        outbounds.put(proxy)
        outbounds.put(direct)
        outbounds.put(block)

        return outbounds
    }

    private fun routing(): JSONObject {
        val routing = JSONObject()
        routing.put("domainStrategy", "IPIfNonMatch")

        val rules = JSONArray()

        val proxyDns = JSONObject()
        val proxyDnsIp = JSONArray()
        proxyDnsIp.put(Settings.primaryDns)
        proxyDnsIp.put(Settings.secondaryDns)
        proxyDns.put("ip", proxyDnsIp)
        proxyDns.put("port", 53)
        proxyDns.put("outboundTag", "proxy")

        val directPrivate = JSONObject()
        val directPrivateIp = JSONArray()
        directPrivateIp.put("geoip:private")
        directPrivate.put("ip", directPrivateIp)
        directPrivate.put("outboundTag", "direct")

        rules.put(proxyDns)
        rules.put(directPrivate)

        routing.put("rules", rules)

        return routing
    }

    private fun config(): JSONObject {
        val config = JSONObject()
        config.put("log", log())
        config.put("dns", dns())
        config.put("inbounds", inbounds())
        config.put("outbounds", outbounds())
        config.put("routing", routing())
        return config
    }

}
