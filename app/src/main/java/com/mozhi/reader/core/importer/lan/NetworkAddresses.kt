package com.mozhi.reader.core.importer.lan

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 传书服务要显示给电脑用户的地址。只找站内 IPv4：手机在 Wi-Fi 下的 192.168/10./172.16
 * 地址才是电脑能直接访问的，蜂窝网络的公网地址在这里没有意义。
 */
object NetworkAddresses {

    /**
     * @return 优先 wlan 接口的站内地址；一个都没有（未连 Wi-Fi / 仅蜂窝）时返回 null。
     */
    fun siteLocalIpv4(): String? = candidates().firstOrNull()

    fun candidates(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedBy { interfaceRank(it.name) }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.asSequence().mapNotNull { address ->
                    (address as? Inet4Address)
                        ?.takeIf { it.isSiteLocalAddress && !it.isLoopbackAddress }
                        ?.hostAddress
                }
            }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

    /** wlan 排最前，其次以太网/USB 网络共享，虚拟接口垫底。 */
    private fun interfaceRank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") -> 1
        name.startsWith("rndis") || name.startsWith("usb") -> 2
        name.startsWith("ap") -> 3
        else -> 9
    }
}
