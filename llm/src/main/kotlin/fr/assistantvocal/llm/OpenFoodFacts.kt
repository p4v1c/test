package fr.assistantvocal.llm

import fr.assistantvocal.core.vision.Product
import fr.assistantvocal.core.vision.ProductLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Produits alimentaires par code-barres (Open Food Facts, base ouverte ODbL, sans clé). */
class OpenFoodFacts(
    private val baseUrl: String = "https://world.openfoodfacts.org",
    private val client: OkHttpClient = GeminiProvider.defaultClient(),
) : ProductLookup {

    override suspend fun lookup(code: String): Product? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/v2/product/$code.json?fields=product_name_fr,product_name,brands,quantity,allergens_tags,nutriscore_grade")
            .header("User-Agent", "AssistantVocal/0.4 (assistant vocal pour personne aveugle)")
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                parse(code, resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    companion object {
        private val ALLERGENS = mapOf(
            "milk" to "lait", "gluten" to "gluten", "eggs" to "œufs", "nuts" to "fruits à coque", "peanuts" to "arachides",
            "soybeans" to "soja", "fish" to "poisson", "crustaceans" to "crustacés", "molluscs" to "mollusques", "celery" to "céleri",
            "mustard" to "moutarde", "sesame-seeds" to "sésame", "sulphur-dioxide-and-sulphites" to "sulfites", "lupin" to "lupin",
        )

        fun parse(code: String, body: String): Product? {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if (root["status"]?.jsonPrimitive?.intOrNull != 1) return null
            val p = root["product"] as? JsonObject ?: return null
            fun str(k: String) = p[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val allergens = (p["allergens_tags"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                .map { tag -> tag.substringAfter(':').let { ALLERGENS[it] ?: it.replace('-', ' ') } }.distinct()
            return Product(code, str("product_name_fr") ?: str("product_name"), str("brands")?.substringBefore(','), str("quantity"),
                allergens, str("nutriscore_grade")?.takeIf { it in setOf("a", "b", "c", "d", "e") })
        }
    }
}
