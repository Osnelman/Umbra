package com.freebuff.barrage

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Masque ou réaffiche l'icône de l'appli dans le lanceur.
 *
 * Le filtre MAIN/LAUNCHER vit sur l'activity-alias « .Launcher » : le
 * désactiver fait disparaître l'icône de tous les lanceurs, sans toucher à
 * MainActivity (notifianton, code secret et réglages continuent de l'ouvrir).
 */
object LauncherIcon {

    private fun alias(context: Context): ComponentName =
        ComponentName(context, "${context.packageName}.Launcher")

    fun isVisible(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(alias(context))
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT // défaut : activé
    }

    fun setVisible(context: Context, visible: Boolean) {
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        // DONT_KILL_APP : le service VPN continue de tourner pendant le basculement
        context.packageManager.setComponentEnabledSetting(alias(context), state, PackageManager.DONT_KILL_APP)
    }
}
