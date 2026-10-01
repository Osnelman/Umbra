package com.freebuff.barrage

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.freebuff.barrage.admin.BarrageAdminReceiver
import com.freebuff.barrage.filter.BlocklistRepository
import com.freebuff.barrage.lock.CodeLock
import com.freebuff.barrage.vpn.LocalVpnService
import kotlin.concurrent.thread

/**
 * Écran unique : statut de la protection, toggle verrouillé par le code
 * binaire, statistiques, mise à jour de la liste, aide et parcours de
 * désinstallation (impossible sans avoir retapé le code binaire).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var toggle: SwitchCompat
    private lateinit var statsDomains: TextView
    private lateinit var statsBlocked: TextView
    private lateinit var recent: TextView
    private lateinit var btnUpdate: Button
    private lateinit var btnHide: Button
    private lateinit var btnLock: Button
    private var updatingUi = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) startVpn() else refreshUi()
        }

    private val adminConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(this, R.string.lock_enabled, Toast.LENGTH_SHORT).show()
            }
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        statusTitle = findViewById(R.id.status_title)
        statusSubtitle = findViewById(R.id.status_subtitle)
        toggle = findViewById(R.id.switch_protect)
        statsDomains = findViewById(R.id.stats_domains)
        statsBlocked = findViewById(R.id.stats_blocked)
        recent = findViewById(R.id.recent)
        btnUpdate = findViewById(R.id.btn_update)

        toggle.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (checked) onEnableRequested() else onDisableRequested()
        }

        findViewById<Button>(R.id.btn_help).setOnClickListener { showHelp() }
        findViewById<Button>(R.id.btn_uninstall).setOnClickListener { showUninstallFlow() }
        btnUpdate.setOnClickListener { showUpdateDialog() }
        btnHide = findViewById(R.id.btn_hide)
        btnHide.setOnClickListener { toggleIconVisibility() }
        btnLock = findViewById(R.id.btn_lock)
        btnLock.setOnClickListener { onLockClicked() }

        // Si on est device owner (commande ADB), (ré)applique les verrous
        BarrageAdminReceiver.applyDeviceOwnerLocks(this)

        requestNotifPermissionIfNeeded()

        if (prefs.codeHash == null) {
            showSetCodeDialog(firstRun = true)
        }
        if (prefs.listSize == 0) {
            countListAsync()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    // ------------------------------------------------------------------ état

    private fun refreshUi() {
        val active = LocalVpnService.isRunning
        updatingUi = true
        toggle.isChecked = active
        updatingUi = false

        statusTitle.setText(if (active) R.string.status_on else R.string.status_off)
        statusTitle.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.status_on else R.color.status_off)
        )
        statusSubtitle.setText(if (active) R.string.subtitle_on else R.string.subtitle_off)

        val size = prefs.listSize
        statsDomains.text = getString(
            R.string.stats_domains,
            if (size > 0) formatCount(size) else "—"
        )
        statsBlocked.text = getString(
            R.string.stats_blocked,
            formatCount(prefs.blockedTotal),
            formatCount(LocalVpnService.sessionBlocked)
        )

        val blocks = prefs.recentBlocks()
        recent.text = if (blocks.isEmpty()) {
            getString(R.string.recent_empty)
        } else {
            blocks.joinToString("\n") { "✕ $it" }
        }

        btnHide.setText(if (LauncherIcon.isVisible(this)) R.string.btn_hide else R.string.btn_show)

        btnLock.setText(
            when (lockState()) {
                LOCK_DO -> R.string.lock_state_do
                LOCK_ADMIN -> R.string.lock_state_admin
                else -> R.string.lock_state_none
            }
        )
    }

    private fun formatCount(n: Int): String =
        if (n >= 1_000_000) String.format("%.1fM", n / 1_000_000.0)
        else if (n >= 1_000) String.format("%.1fk", n / 1_000.0)
        else n.toString()

    // ------------------------------------------------------- activation / coupure

    private fun onEnableRequested() {
        if (prefs.codeHash == null) {
            // Pas de code binaire encore : on force la création avant d'activer
            showSetCodeDialog(firstRun = false)
            refreshUi()
            return
        }
        startVpn()
    }

    private fun onDisableRequested() {
        // Dès la première pulsation : on remet l'interrupteur, le code binaire
        // est exigé avant toute coupure.
        refreshUi()
        requireGate {
            stopVpn()
            Toast.makeText(this, "Protection désactivée.", Toast.LENGTH_SHORT).show()
            refreshUi()
        }
    }

    private fun startVpn() {
        prefs.protectionWanted = true
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnConsent.launch(consent)
        } else {
            startService(Intent(this, LocalVpnService::class.java))
        }
        // L'état réel arrive dans onResume / via le service
        toggle.postDelayed({ refreshUi() }, 400)
    }

    private fun stopVpn() {
        prefs.protectionWanted = false
        stopService(Intent(this, LocalVpnService::class.java))
    }

    // ------------------------------------------------------------- icône masquée

    /**
     * Masque l'icône du lanceur : l'appli continue de tourner (VPN +
     * notification), on la rouvre via la notification ou *#*#4839#*#*.
     */
    private fun toggleIconVisibility() {
        if (LauncherIcon.isVisible(this)) {
            if (!LocalVpnService.isRunning) {
                Toast.makeText(this, R.string.hide_need_active, Toast.LENGTH_LONG).show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.hide_title)
                .setMessage(R.string.hide_message)
                .setPositiveButton(R.string.hide_confirm) { _, _ ->
                    LauncherIcon.setVisible(this, false)
                    Toast.makeText(this, R.string.hide_done, Toast.LENGTH_LONG).show()
                    refreshUi()
                }
                .setNegativeButton(R.string.gate_cancel, null)
                .show()
        } else {
            LauncherIcon.setVisible(this, true)
            Toast.makeText(this, R.string.show_done, Toast.LENGTH_SHORT).show()
            refreshUi()
        }
    }

    // ------------------------------------------------------------------ verrou

    /** Exige le code binaire avant une action sensible. */
    private fun requireGate(onSuccess: () -> Unit) {
        val hash = prefs.codeHash
        if (hash == null) {
            showSetCodeDialog(firstRun = false)
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "0 et 1"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.gate_title)
            .setMessage(getString(R.string.gate_message, prefs.codeLength))
            .setView(input)
            .setPositiveButton(R.string.gate_ok) { _, _ ->
                if (CodeLock.verify(input.text.toString(), hash)) {
                    onSuccess()
                } else {
                    Toast.makeText(this, R.string.gate_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.gate_cancel, null)
            .show()
    }

    /** Premier réglage du code court → conversion en code binaire. */
    private fun showSetCodeDialog(firstRun: Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Ex. 4839"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.code_title)
            .setMessage(R.string.code_message)
            .setView(input)
            .setCancelable(!firstRun)
            .setPositiveButton("Continuer") { _, _ ->
                val binary = CodeLock.toBinary(input.text.toString())
                if (binary == null) {
                    Toast.makeText(this, R.string.code_invalid, Toast.LENGTH_LONG).show()
                } else {
                    confirmBinary(input.text.toString().trim(), binary)
                }
            }
            .show()
    }

    private fun confirmBinary(code: String, binary: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.code_confirm_title)
            .setMessage(getString(R.string.code_confirm_message, code, binary))
            .setPositiveButton("J'ai noté") { _, _ ->
                prefs.codeHash = CodeLock.hash(binary)
                prefs.codeLength = binary.length
                Toast.makeText(this, R.string.code_saved, Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            .setCancelable(false)
            .show()
    }

    // -------------------------------------------------------------------- aide

    private fun showHelp() {
        val text = """
            |Comment ça marche
            |• Un mini-VPN local filtre les requêtes DNS du téléphone. Tout se passe sur l'appareil : rien n'est envoyé sur internet.
            |• Les domaines de la liste (2 M de sites connus) et les mots-clés (porn, xxx, onlyfans, xnxx…) sont coupés : l'appli affiche « site inaccessible », Umbra affiche « Site bloqué ».
            |• Chrome, TikTok, Telegram, Twitter, navigateur intégré… tout passe par le même DNS système.
            |
            |Le verrou binaire
            |• Ton code court est converti en code binaire. Ce code est exigé pour couper la protection.
            |• Retirer l'app demande de retaper ce long code : c'est volontairement long pour te laisser le temps de te calmer.
            |
            |Limites connues
            |• « DNS chiffré » (DoH/DoT, ex. Firefox ou Réglages > Réseau > DNS privé) peut contourner le filtre : garde-le sur « Désactivé » ou « Automatique ».
            |• Android ne permet pas d'empêcher réellement la désinstallation : le code binaire ajoute une barrière volontaire, pas une sécurité inviolable.
            |• Seul le nom de domaine est visible (pas les mots-clés dans l'adresse après « / »).
        """.trimMargin()
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_help)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showUninstallFlow() {
        requireGate {
            val message = when (lockState()) {
                LOCK_DO ->
                    "Code accepté.\n\nVerrou TOTAL actif (device owner) : Android bloque la " +
                        "désinstallation de Umbra.\n\nSeul un retrait par câble peut lever " +
                        "le verrou :\n\nadb shell dpm remove-active-admin " +
                        "com.freebuff.barrage/com.freebuff.barrage.admin.BarrageAdminReceiver\n\n" +
                        "Ensuite, désinstallation normale depuis Réglages."
                LOCK_ADMIN ->
                    "Code accepté.\n\nVerrou admin actif : Android refusera la désinstallation " +
                        "tant que l'admin n'est pas désactivé.\n\n" +
                        "1. Réglages → Applications → Accès spécial → Admin d'appareil " +
                        "→ décoche Umbra.\n" +
                        "2. Puis Réglages → Applications → Umbra → Désinstaller."
                else ->
                    "Code accepté.\n\nPour désinstaller Umbra :\n" +
                        "1. Réglages → Applications → Umbra.\n" +
                        "2. « Désinstaller ».\n\n" +
                        "La protection VPN s'arrêtera en même temps."
            }
            val builder = AlertDialog.Builder(this)
                .setTitle("Retirer l'app")
                .setMessage(message)
                .setNegativeButton(R.string.gate_cancel, null)
            if (lockState() != LOCK_DO) {
                builder.setPositiveButton("Ouvrir les paramètres") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                }
            }
            builder.show()
        }
    }

    // ------------------------------------------------- verrou de désinstallation

    private fun dpm(): DevicePolicyManager =
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun adminComponent(): ComponentName =
        ComponentName(this, BarrageAdminReceiver::class.java)

    private fun lockState(): Int = when {
        dpm().isDeviceOwnerApp(packageName) -> LOCK_DO
        dpm().isAdminActive(adminComponent()) -> LOCK_ADMIN
        else -> LOCK_NONE
    }

    /** Clique sur « Verrou de désinstallation » : info, ou activation admin. */
    private fun onLockClicked() {
        when (lockState()) {
            LOCK_DO -> AlertDialog.Builder(this)
                .setTitle(R.string.lock_info_do_title)
                .setMessage(R.string.lock_info_do_msg)
                .setPositiveButton("OK", null)
                .show()
            LOCK_ADMIN -> AlertDialog.Builder(this)
                .setTitle(R.string.lock_info_admin_title)
                .setMessage(R.string.lock_info_admin_msg)
                .setPositiveButton("OK", null)
                .show()
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.lock_enable_title)
                .setMessage(R.string.lock_enable_msg)
                .setPositiveButton(R.string.lock_enable_confirm) { _, _ ->
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            getString(R.string.lock_enable_explanation)
                        )
                    }
                    adminConsent.launch(intent)
                }
                .setNegativeButton(R.string.gate_cancel, null)
                .show()
        }
    }

    private companion object {
        const val LOCK_NONE = 0
        const val LOCK_ADMIN = 1
        const val LOCK_DO = 2
    }

    // -------------------------------------------------------------- mise à jour

    private fun showUpdateDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.listUrl)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Mettre à jour la liste")
            .setMessage("Liste distante au format hosts, AdGuard ou domaine simple :")
            .setView(input)
            .setPositiveButton("Télécharger") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                prefs.listUrl = url
                btnUpdate.isEnabled = false
                Toast.makeText(this, "Téléchargement…", Toast.LENGTH_SHORT).show()
                BlocklistRepository.update(this, url) { result ->
                    runOnUiThread {
                        btnUpdate.isEnabled = true
                        if (result.error != null) {
                            Toast.makeText(this, "Échec : ${result.error}", Toast.LENGTH_LONG).show()
                        } else {
                            prefs.listSize = result.entries
                            LocalVpnService.refresh(this)
                            Toast.makeText(
                                this,
                                "Liste mise à jour : ${formatCount(result.entries)} domaines.",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshUi()
                        }
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun countListAsync() {
        thread {
            val n = try {
                BlocklistRepository.countEntries(this)
            } catch (e: Exception) {
                0
            }
            if (n > 0) {
                prefs.listSize = n
                runOnUiThread { refreshUi() }
            }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
