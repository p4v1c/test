package fr.assistantvocal.core.llm

import fr.assistantvocal.core.settings.KeyValueStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Compte les requêtes IA par fournisseur et par « jour de quota ».
 * Gemini remet ses quotas à zéro à minuit heure du Pacifique (9 h en France) : on suit le même jour.
 */
class UsageCounter(
    private val store: KeyValueStore,
    private val now: () -> Instant,
    private val limitFor: (String) -> Int,
) {
    private fun zoneFor(providerId: String): ZoneId =
        if (providerId == "gemini") ZoneId.of("America/Los_Angeles") else ZoneId.of("Europe/Paris")

    private fun day(providerId: String): LocalDate = now().atZone(zoneFor(providerId)).toLocalDate()

    private fun key(providerId: String) = "usage.$providerId.${day(providerId)}"

    fun count(providerId: String): Int = store.getInt(key(providerId), 0)

    fun limit(providerId: String): Int = limitFor(providerId)

    fun isExhausted(providerId: String): Boolean = count(providerId) >= limit(providerId)

    /** Incrémente et renvoie un avertissement à annoncer quand on franchit 80 % puis 100 % de la limite. */
    fun increment(providerId: String, displayName: String): String? {
        val n = count(providerId) + 1
        store.putInt(key(providerId), n)
        val limit = limit(providerId)
        return when (n) {
            (limit * 0.8).toInt() -> "Information : j'ai utilisé $n requêtes $displayName sur $limit aujourd'hui."
            limit -> "J'ai atteint ma limite du jour avec $displayName."
            else -> null
        }
    }

    /** Marque le fournisseur comme épuisé pour la journée (après une réponse 429 du serveur). */
    fun markExhausted(providerId: String) = store.putInt(key(providerId), limit(providerId))
}

/**
 * Essaie les fournisseurs dans l'ordre. Sur quota, panne ou erreur réseau, passe au suivant et le dit.
 * Exception : un blocage de sécurité (BLOCKED) n'entraîne pas de bascule, le contenu serait refusé ailleurs aussi.
 */
class FallbackLlmProvider(
    private val providers: () -> List<LlmProvider>,
    private val usage: UsageCounter,
) : LlmProvider {
    override val id = "fallback"
    override val displayName = "IA"

    override fun isConfigured(): Boolean = providers().any { it.isConfigured() }

    override suspend fun generate(request: LlmRequest): LlmResult {
        val notices = mutableListOf<String>()
        var lastFailure: LlmResult.Failure? = null
        val candidates = providers().filter { it.isConfigured() }
        if (candidates.isEmpty()) {
            return LlmResult.Failure(FailureKind.NOT_CONFIGURED, "Aucune clé d'IA n'est configurée.")
        }
        for ((index, provider) in candidates.withIndex()) {
            val next = candidates.getOrNull(index + 1)
            if (usage.isExhausted(provider.id)) {
                if (next != null) notices += "${provider.displayName} a atteint sa limite du jour, j'utilise ${next.displayName}."
                lastFailure = LlmResult.Failure(FailureKind.QUOTA, "Limite quotidienne atteinte", provider.id)
                continue
            }
            val result = provider.generate(request)
            when (result) {
                is LlmResult.Reply -> {
                    usage.increment(provider.id, provider.displayName)?.let { notices += it }
                    return result.copy(notice = notices.joinToString(" ").ifEmpty { null })
                }
                is LlmResult.Failure -> {
                    lastFailure = result
                    if (result.kind == FailureKind.QUOTA) usage.markExhausted(provider.id)
                    if (result.kind == FailureKind.BLOCKED) break
                    if (next != null) notices += switchNotice(provider, next, result.kind)
                }
            }
        }
        val failure = lastFailure ?: LlmResult.Failure(FailureKind.SERVER, "Aucun fournisseur disponible")
        return failure.copy(notice = notices.joinToString(" ").ifEmpty { null })
    }

    private fun switchNotice(from: LlmProvider, to: LlmProvider, kind: FailureKind): String = when (kind) {
        FailureKind.QUOTA -> "${from.displayName} a atteint sa limite, je passe à ${to.displayName}."
        FailureKind.AUTH -> "La clé ${from.displayName} ne fonctionne pas, je passe à ${to.displayName}."
        FailureKind.NETWORK -> "${from.displayName} ne répond pas, j'essaie ${to.displayName}."
        else -> "${from.displayName} a un problème, je passe à ${to.displayName}."
    }
}
