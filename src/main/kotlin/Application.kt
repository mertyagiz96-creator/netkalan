import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 🌍 Şu an desteklenen tüm ülke kodları — World Bank verisi bunlar için çekiliyor.
private val SUPPORTED_COUNTRY_CODES = listOf("US", "DE", "GB", "NL", "TR", "IN", "BR", "CA", "PL", "AU", "CH", "FR", "JP")

// 🔄 Sunucu açık kaldığı sürece AYDA BİR şunları tekrar çalıştırıyor:
// 1) World Bank'ten işsizlik/enflasyonu tazeliyor (bu veri sık güncellenir)
// 2) Stack Overflow'un anket dosyasını kontrol ediyor — 30 günden eskiyse yeniden
//    indirip medyanları tekrar hesaplıyor. NOT: SO anketi kendisi YILDA BİR
//    yayınlanıyor, yani çoğu ay bu adım "değişiklik yok" bulacak — ama otomasyon
//    gerçek, yeni bir anket çıktığında elle bir şey yapmamıza gerek kalmayacak.
// Hiçbiri sunucu başlangıcını BLOKLAMIYOR, arka planda çalışıyor.
@OptIn(DelicateCoroutinesApi::class)
private fun startMonthlyDataRefreshLoop() {
    GlobalScope.launch {
        while (true) {
            println("🔄 Aylık veri yenileme döngüsü başladı...")

            for (code in SUPPORTED_COUNTRY_CODES) {
                val unemployment = WorldBankClient.fetchUnemploymentPercent(code)
                val inflation = WorldBankClient.fetchInflationPercent(code)
                DatabaseClient.macroDataCache[code] = DatabaseClient.MacroData(unemployment, inflation)
                delay(400) // 🐢 art arda çok hızlı istek atıp hız sınırına takılmayalım diye küçük bir bekleme
            }
            println("✅ World Bank verisi güncellendi (${SUPPORTED_COUNTRY_CODES.size} ülke).")

            val realSalaries = StackOverflowSalaryClient.computeRealSalaries()
            if (realSalaries.isNotEmpty()) {
                DatabaseClient.realSalaryCache.clear()
                DatabaseClient.realSalaryCache.putAll(realSalaries)
                println("✅ Stack Overflow gerçek maaş verisi güncellendi (${realSalaries.size} ülke×rol kombinasyonu).")
            } else {
                println("⚠️ Stack Overflow verisi bu turda alınamadı, mevcut önbellek korunuyor.")
            }

            delay(30L * 24 * 60 * 60 * 1000) // 30 gün bekle, tekrar başa dön
        }
    }
}

fun main() {
    // 🌐 Bazı Mac/ağ kombinasyonlarında JVM önce IPv6 dener, yanıt gelmeyince uzun
    // süre bekleyip IPv4'e düşüyor — bu da dışarıya (World Bank vb.) yapılan
    // isteklerin "takılmış" gibi görünmesine sebep oluyor. Bunu en baştan
    // devre dışı bırakıp direkt IPv4 kullanmasını sağlıyoruz.
    System.setProperty("java.net.preferIPv4Stack", "true")

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    startMonthlyDataRefreshLoop()

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json()
        }

        routing {
            staticResources("/", "static", index = "index.html")

            // 🎭 Seçilebilir rol listesi — frontend'deki autocomplete bunu kullanıyor.
            get("/api/roles") {
                call.respond(DatabaseClient.fetchAllRoles())
            }

            // 👪 Seçilebilir hane tipi listesi (Bekar / Evli Çocuksuz / Evli 2 Çocuklu)
            get("/api/households") {
                call.respond(DatabaseClient.fetchAllHouseholds())
            }

            // 💰 ?role= ve ?household= parametreleriyle filtreleniyor, verilmezse
            // sırasıyla "Backend Developer" ve "single" varsayılan. Sonuç en yüksek
            // net kalan tutardan en düşüğe sıralı dönüyor.
            get("/api/countries") {
                val role = call.request.queryParameters["role"] ?: "Backend Developer"
                val household = call.request.queryParameters["household"] ?: "single"
                val countries = DatabaseClient.fetchAllCountries(role, household)
                call.respond(countries)
            }
        }
    }.start(wait = true)
}