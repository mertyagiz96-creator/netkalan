import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

// 🌍 World Bank Open Data API — tamamen ücretsiz, API key GEREKTİRMİYOR, resmi
// kurumsal kaynak. Maaş/kira verisi yok (o yüzden hâlâ tahmini kalıyoruz), ama
// işsizlik ve enflasyon için gerçek, güncel resmi rakamlar veriyor.
object WorldBankClient {
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }

    // İşsizlik oranı (SL.UEM.TOTL.ZS) ve enflasyon (FP.CPI.TOTL.ZG) indikatör kodları.
    // mrnev=1 → "most recent non-empty value", yani boş olmayan en güncel yılı getirir.
    // 🔁 Cloudflare arkasındaki API'ler bazen tekil istekleri geciktirebiliyor —
    // bu yüzden başarısız olursa kısa bir bekleme sonrası 2 kez daha deniyoruz.
    private suspend fun fetchIndicator(countryCode: String, indicatorCode: String): Double? {
        repeat(3) { attempt ->
            try {
                val url = "https://api.worldbank.org/v2/country/$countryCode/indicator/$indicatorCode?format=json&mrnev=1"
                val response: HttpResponse = client.get(url) {
                    header("User-Agent", "NetKalan/1.0 (+https://netkalan.example)")
                    header("Accept", "application/json")
                }
                val text = response.bodyAsText()

                // 🛡️ Bazen Cloudflare, JSON yerine bir XML/HTML hata sayfası döndürüyor
                // (bot koruma/hız sınırı). Bunu JSON parse etmeye ÇALIŞMADAN önce
                // yakalayıp temiz bir şekilde bir sonraki denemeye geçiyoruz.
                if (!text.trimStart().startsWith("[") && !text.trimStart().startsWith("{")) {
                    println("⚠️ World Bank JSON olmayan cevap döndürdü ($countryCode/$indicatorCode), deneme ${attempt + 1}/3 — tekrar denenecek.")
                    kotlinx.coroutines.delay(2000 + (500L * attempt) + (Math.random() * 1000).toLong())
                    return@repeat
                }

                val json = Json.parseToJsonElement(text)
                val dataArray = json.jsonArray.getOrNull(1)?.jsonArray ?: return null
                val firstEntry = dataArray.firstOrNull()?.jsonObject ?: return null
                return firstEntry["value"]?.jsonPrimitive?.doubleOrNull
            } catch (e: Exception) {
                println("🔥 World Bank fetch hatası ($countryCode/$indicatorCode), deneme ${attempt + 1}/3: ${e.message}")
                kotlinx.coroutines.delay(2000 + (500L * attempt) + (Math.random() * 1000).toLong())
            }
        }
        return null
    }

    suspend fun fetchUnemploymentPercent(countryCode: String): Double? =
        fetchIndicator(countryCode, "SL.UEM.TOTL.ZS")

    suspend fun fetchInflationPercent(countryCode: String): Double? =
        fetchIndicator(countryCode, "FP.CPI.TOTL.ZG")
}