package com.freebuff.barrage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Entrée secrète quand l'icône est masquée : composer *#*#4839#*#* ouvre
 * l'appli. Fonctionne selon le composeur installé (AOSP/Samsung gèrent les
 * codes secrets ; la notification « Protection active » reste l'entrée
 * garantie).
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SECRET_CODE) return
        if (intent.data?.host != SECRET_CODE) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        } catch (e: Exception) {
            Log.w("Umbra", "ouverture par code secret impossible : $e")
        }
    }

    private companion object {
        const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"
        const val SECRET_CODE = "4839"
    }
}
