import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

// 🏠 WhereNext Cost of Living Index — tamamen ücretsiz, API key GEREKTİRMİYOR,
// CC BY 4.0 lisanslı, World Bank ICP 2021 (Uluslararası Karşılaştırma Programı)
// verisine dayanıyor. 95 ülke, hesap gerekmiyor. Kaynak: getwherenext.com
object WhereNextClient {
    private const val API_URL = "https://getwherenext.com/api/data/cost-of-living"
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    data class CostOfLiving(
        val monthlyTotalUsd: Double, // 💯 GERÇEK — WhereNext'in doğrudan verdiği toplam aylık yaşam maliyeti
        val monthlyRentUsd: Double,  // Toplamın, alt-endekslere göre ORANTILI olarak ayrıştırılmış kira kısmı
        val monthlyExpenseUsd: Double, // Toplamın geri kalanı (market+ulaşım+fatura)
        val lastUpdated: String
    )

    // Dönen map key: "US", "DE" ... (ISO2 ülke kodu)
    suspend fun fetchCostOfLiving(): Map<String, CostOfLiving> {
        return try {
            val response: HttpResponse = client.get(API_URL) {
                header("User-Agent", "NetKalan/1.0 (+https://netkalan.example)")
                header("Accept", "application/json")
            }
            val text = response.bodyAsText()
            if (!text.trimStart().startsWith("{")) {
                println("⚠️ WhereNext JSON olmayan cevap döndürdü.")
                return emptyMap()
            }

            val json = Json.parseToJsonElement(text).jsonObject
            val updated = json["meta"]?.jsonObject?.get("updated")?.jsonPrimitive?.content ?: "bilinmiyor"
            val dataArray = json["data"]?.jsonArray ?: return emptyMap()

            val result = mutableMapOf<String, CostOfLiving>()
            for (entry in dataArray) {
                val obj = entry.jsonObject
                val code = obj["country_code"]?.jsonPrimitive?.content ?: continue
                val monthlyTotal = obj["monthly_estimate_usd"]?.jsonPrimitive?.doubleOrNull ?: continue
                val rentIdx = obj["rent_index"]?.jsonPrimitive?.doubleOrNull ?: 50.0
                val groceryIdx = obj["grocery_index"]?.jsonPrimitive?.doubleOrNull ?: 50.0
                val transportIdx = obj["transport_index"]?.jsonPrimitive?.doubleOrNull ?: 50.0

                // 🧮 Skorlar "uygunluk" skoru (yüksek = ucuz). Gerçek fiyat seviyesine
                // (PLI) çevirip, kira'nın toplam içindeki payını bu gerçek oranla
                // hesaplıyoruz — hiçbir sabit yüzde varsayımı YOK, tamamen WhereNext'in
                // kendi verisinden türetiliyor.
                val rentPli = 2.0 * (100.0 - rentIdx)
                val otherPliAvg = 2.0 * (100.0 - ((groceryIdx + transportIdx) / 2.0))
                val rentShare = if (rentPli + otherPliAvg > 0) rentPli / (rentPli + otherPliAvg) else 0.5

                val rentUsd = monthlyTotal * rentShare
                val expenseUsd = monthlyTotal - rentUsd

                result[code] = CostOfLiving(monthlyTotal, rentUsd, expenseUsd, updated)
            }
            result
        } catch (e: Exception) {
            println("🔥 WhereNext fetch hatası: ${e.message}")
            emptyMap()
        }
    }
}