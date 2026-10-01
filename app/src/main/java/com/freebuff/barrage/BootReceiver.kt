package com.freebuff.barrage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.freebuff.barrage.vpn.LocalVpnService

/**
 * Relance le VPN au démarrage du téléphone (ou après une mise à jour de
 * l'appli) si la protection avait été laissée active. Sans ça, un reboot
 * couperait silencieusement le filtrage.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs(context).protectionWanted) return
        try {
            context.startForegroundService(Intent(context, LocalVpnService::class.java))
        } catch (e: Exception) {
            // Ex. ForegroundServiceStartNotAllowedException sur certains OEM :
            // la protection reprendra au prochain ouverture de l'appli.
            Log.w("Umbra", "démarrage au boot impossible : $e")
        }
    }
}
