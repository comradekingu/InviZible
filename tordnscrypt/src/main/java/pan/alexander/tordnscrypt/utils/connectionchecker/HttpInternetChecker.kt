/*
    This file is part of InviZible Pro.

    InviZible Pro is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    InviZible Pro is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with InviZible Pro.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019-2021 by Garmatin Oleksandr invizible.soft@gmail.com
 */

package pan.alexander.tordnscrypt.utils.connectionchecker

import android.util.Log
import pan.alexander.tordnscrypt.settings.PathVars
import pan.alexander.tordnscrypt.utils.Constants.LOOPBACK_ADDRESS
import pan.alexander.tordnscrypt.utils.Constants.TOR_BROWSER_USER_AGENT
import pan.alexander.tordnscrypt.utils.root.RootExecService.LOG_TAG
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

private const val READ_TIMEOUT_SEC = 15
private const val CONNECT_TIMEOUT_SEC = 50
private const val USER_AGENT_PROPERTY = "User-Agent"
private const val REQUEST_METHOD_GET = "GET"

class HttpInternetChecker @Inject constructor(
    private val pathVars: PathVars
) {

    private var connection: HttpURLConnection? = null

    fun checkConnectionAvailability(site: String): Boolean {
        var result = false

        try {
            result = checkConnection(site, false)
            return result
        } catch (e: Exception) {
            Log.w(LOG_TAG, "HttpInternetChecker check connection failed ${e.message} ${e.cause}")
        } finally {
            if (result) {
                connection?.disconnect()
            }
        }

        return false
    }

    //Warning!!! Using this method with Tor may produce NullPointerException
    // com.android.okhttp.internal.Util.closeQuietly(Util.java:95)
    private fun checkConnection(site: String, withTor: Boolean): Boolean {
        val url = URL(site)

        connection = if (withTor) {
            val proxy = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress(
                    LOOPBACK_ADDRESS,
                    pathVars.torSOCKSPort.toInt()
                )
            )

            url.openConnection(proxy) as HttpsURLConnection
        } else {
            url.openConnection() as HttpsURLConnection
        }

        val connection = connection ?: return false

        connection.apply {
            requestMethod = REQUEST_METHOD_GET
            connectTimeout = CONNECT_TIMEOUT_SEC * 1000
            readTimeout = READ_TIMEOUT_SEC * 1000
            setRequestProperty(USER_AGENT_PROPERTY, TOR_BROWSER_USER_AGENT)
            connect()
        }

        val code = connection.responseCode
        return code == HttpURLConnection.HTTP_OK
    }
}
