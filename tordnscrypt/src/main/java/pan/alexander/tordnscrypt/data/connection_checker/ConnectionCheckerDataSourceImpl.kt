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

package pan.alexander.tordnscrypt.data.connection_checker

import android.content.Context
import pan.alexander.tordnscrypt.utils.connectionchecker.HttpInternetChecker
import pan.alexander.tordnscrypt.utils.connectionchecker.NetworkChecker
import pan.alexander.tordnscrypt.utils.connectionchecker.SocketInternetChecker
import javax.inject.Inject
import javax.inject.Provider

class ConnectionCheckerDataSourceImpl @Inject constructor(
    private val httpInternetChecker: Provider<HttpInternetChecker>,
    private val socketInternetChecker: Provider<SocketInternetChecker>,
    private val context: Context
) : ConnectionCheckerDataSource {
    override fun checkInternetAvailableOverHttp(site: String): Boolean =
        httpInternetChecker.get().checkConnectionAvailability(site)

    override fun checkInternetAvailableOverSocks(ip: String, port: Int, withTor: Boolean): Boolean =
        socketInternetChecker.get().checkConnectionAvailability(ip, port, withTor)

    override fun checkNetworkAvailable(): Boolean =
        NetworkChecker.isNetworkAvailable(context)

}
