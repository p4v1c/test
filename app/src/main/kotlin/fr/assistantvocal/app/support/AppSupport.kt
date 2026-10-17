package fr.assistantvocal.app.support

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import fr.assistantvocal.app.AppGraph
import fr.assistantvocal.core.support.Backup
import fr.assistantvocal.core.support.Check
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sauvegardes automatiques et à la voix, dans un dossier de l'app visible par un proche (câble USB ou Fichiers). */
object Backups {
    private const val K_LAST = "backup.lastAuto"
    private const val KEEP = 8
    const val SPOKEN_PLACE = "le dossier Android, data, de l'application, sous-dossier Sauvegardes"

    fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Sauvegardes").apply { mkdirs() }

    fun write(context: Context, graph: AppGraph): File? = runCatching {
        val now = LocalDateTime.now(ZoneId.of("Europe/Paris"))
        val f = File(dir(context), "sauvegarde-${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))}.json")
        f.writeText(Backup.export(graph.store, now.toString().take(16), graph.settings.name))
        dir(context).listFiles { x -> x.name.startsWith("sauvegarde-") }.orEmpty().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        graph.store.putString(K_LAST, System.currentTimeMillis().toString())
        f
    }.getOrNull()

    /** Une sauvegarde par semaine, sans rien demander. */
    fun autoIfDue(context: Context, graph: AppGraph) {
        val last = graph.store.getString(K_LAST)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last > 7 * 24 * 3_600_000L) write(context, graph)
    }

    /** Après une restauration : rappels reprogrammés. */
    fun afterRestore(graph: AppGraph) {
        runCatching { graph.reminders.rescheduleAll() }
    }
}

/** Vérifications de « est-ce que tout marche ? », dites dans l'ordre d'importance. */
object HealthChecks {
    fun run(context: Context, graph: AppGraph): List<Check> {
        fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val s = graph.settings
        val pm = context.getSystemService(PowerManager::class.java)
        val assistantRole = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) else null
        return listOf(
            Check("le micro", has(Manifest.permission.RECORD_AUDIO), "l'autorisation micro manque, ouvrez l'application et acceptez."),
            Check("l'écoute de mon nom", s.wakeWordEnabled, "elle est coupée. Dites : réactive ton nom."),
            Check("l'intelligence artificielle", graph.llm.isConfigured(), "aucune clé n'est enregistrée. Un proche doit en ajouter une dans les réglages."),
            Check("internet", graph.device.isOnline(), "pas de connexion. Vérifiez le wifi ou les données mobiles."),
            Check("la position pour les secours", has(Manifest.permission.ACCESS_FINE_LOCATION), "l'autorisation de localisation manque."),
            Check("les contacts d'urgence", graph.people.emergencyContacts.isNotEmpty(), "aucun contact d'urgence. Un proche doit en ajouter dans les réglages."),
            Check("le fonctionnement en veille", pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
                "l'économie de batterie peut m'endormir. Un proche doit me retirer de l'optimisation de batterie."),
            Check("le rôle d'assistant", assistantRole != false, "je ne suis pas l'assistant par défaut. Un proche doit me choisir dans les réglages d'Android, applications par défaut."),
            Check("les SMS et appels", has(Manifest.permission.SEND_SMS) && has(Manifest.permission.CALL_PHONE), "des autorisations manquent pour les SMS ou les appels."),
            Check("l'accès aux notifications", NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
                "je ne peux pas lire les notifications. Un proche doit l'autoriser dans les réglages.", important = false),
            Check("la caméra", has(Manifest.permission.CAMERA), "l'autorisation caméra manque.", important = false),
            Check("les livres", s.libraryUri.isNotBlank(), "aucun dossier de livres n'est choisi dans les réglages.", important = false),
            Check("les mails", s.mailAddress.contains('@') && graph.secrets.has(AppGraph.SECRET_MAIL), "Gmail n'est pas configuré.", important = false),
            Check("l'agenda", has(Manifest.permission.READ_CALENDAR), "l'autorisation agenda manque.", important = false),
        )
    }

    fun journalTime(ms: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
}
