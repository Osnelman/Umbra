package com.freebuff.barrage.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

/**
 * Receiver d'admin d'appareil.
 *
 * Deux rôles :
 *  - niveau 1 (admin classique) : tant qu'il est actif, Android refuse la
 *    désinstallation de l'appli tant que l'admin n'a pas été désactivé ;
 *  - niveau 2 (device owner, posé via ADB) : on verrouille réellement la
 *    désinstallation de notre paquet avec setUninstallBlocked(), et on bloque
 *    la réinitialisation d'usine. Le verrou ne peut être levé que par câble
 *    (dpm remove-active-admin).
 */
class BarrageAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        applyDeviceOwnerLocks(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(BarrageAdminReceiver.TAG, "admin désactivé")
    }

    companion object {
        const val TAG = "Umbra"

        fun adminComponent(context: Context): ComponentName =
            ComponentName(context, BarrageAdminReceiver::class.java)

        /**
         * Applique les verrous device owner (idempotent). Rappelez ce qui en
         * appelle à chaque ouverture : couvre le cas où le broadcast
         * onEnabled n'aurait pas été livré.
         */
        fun applyDeviceOwnerLocks(context: Context) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (!dpm.isDeviceOwnerApp(context.packageName)) return
                val cn = adminComponent(context)
                dpm.setUninstallBlocked(cn, context.packageName, true)
                dpm.addUserRestriction(cn, UserManager.DISALLOW_FACTORY_RESET)
                Log.i(TAG, "device owner actif : désinstallation + reset bloqués")
            } catch (e: Exception) {
                Log.w(TAG, "application des verrous device owner impossible : $e")
            }
        }
    }
}
