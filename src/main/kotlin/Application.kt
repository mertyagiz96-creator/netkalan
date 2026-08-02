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
import kotlinx.coroutines.withTimeoutOrNull

// 🌍 Şu an desteklenen tüm ülke kodları — World Bank verisi bunlar için çekiliyor.
private val SUPPORTED_COUNTRY_CODES = listOf("US", "DE", "GB", "NL", "TR", "IN", "BR", "CA", "PL", "AU", "CH", "FR", "JP", "AE", "CN", "RU", "DK")

// 🔄 Üç veri kaynağı (World Bank, Stack Overflow, WhereNext) artık BİRBİRİNDEN
// BAĞIMSIZ 3 AYRI döngüde çalışıyor — biri ağ seviyesinde takılıp kalsa bile
// (nadir ama görüldü) diğer ikisi etkilenmiyor. Her birine ayrıca bir ÜST ZAMAN
// SINIRI (withTimeoutOrNull) koyduk: bu süreyi aşan bir adım otomatik olarak
// iptal edilip bir sonraki 30 günlük turda tekrar denenir, sonsuza kadar
// donmuyor. Hiçbiri sunucu başlangıcını bloklamıyor.
@OptIn(DelicateCoroutinesApi::class)
private fun startMonthlyDataRefreshLoop() {
    // 1) World Bank — işsizlik + enflasyon
    GlobalScope.launch {
        while (true) {
            val completed = withTimeoutOrNull(5 * 60 * 1000L) {
                for (code in SUPPORTED_COUNTRY_CODES) {
                    val unemployment = WorldBankClient.fetchUnemploymentPercent(code)
                    val inflation = WorldBankClient.fetchInflationPercent(code)
                    DatabaseClient.macroDataCache[code] = DatabaseClient.MacroData(unemployment, inflation)
                    delay(400)
                }
                true
            }
            if (completed == true) {
                println("✅ World Bank verisi güncellendi (${SUPPORTED_COUNTRY_CODES.size} ülke).")
            } else {
                println("⏱️ World Bank turu 5 dakikayı aştı, iptal edildi — bir sonraki turda tekrar denenecek.")
            }
            delay(30L * 24 * 60 * 60 * 1000)
        }
    }

    // 2) Stack Overflow — gerçek medyan maaşlar
    GlobalScope.launch {
        while (true) {
            val realSalaries = withTimeoutOrNull(8 * 60 * 1000L) {
                StackOverflowSalaryClient.computeRealSalaries()
            }
            if (!realSalaries.isNullOrEmpty()) {
                DatabaseClient.realSalaryCache.clear()
                DatabaseClient.realSalaryCache.putAll(realSalaries)
                println("✅ Stack Overflow gerçek maaş verisi güncellendi (${realSalaries.size} ülke×rol kombinasyonu).")
            } else {
                println("⏱️ Stack Overflow verisi bu turda alınamadı (zaman aşımı ya da boş sonuç), mevcut önbellek korunuyor.")
            }
            delay(30L * 24 * 60 * 60 * 1000)
        }
    }

    // 3) WhereNext — kira/gider
    GlobalScope.launch {
        while (true) {
            val costOfLiving = withTimeoutOrNull(2 * 60 * 1000L) {
                WhereNextClient.fetchCostOfLiving()
            }
            if (!costOfLiving.isNullOrEmpty()) {
                DatabaseClient.costOfLivingCache.clear()
                DatabaseClient.costOfLivingCache.putAll(costOfLiving)
                println("✅ WhereNext kira/gider verisi güncellendi (${costOfLiving.size} ülke).")
            } else {
                println("⏱️ WhereNext verisi bu turda alınamadı, mevcut önbellek korunuyor.")
            }
            delay(30L * 24 * 60 * 60 * 1000)
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

            // 🔍 Hangi ülke+rol kombinasyonunun GERÇEK, hangisinin tahmini olduğunu
            // gösteren tanı endpoint'i — "hangi maaş yok" sorusuna cevap için.
            get("/api/data-coverage") {
                call.respond(DatabaseClient.fetchDataCoverage())
            }

            // 🏆 Tahmin modu GLOBAL liderlik tablosu — herkes aynı rekoru görüyor.
            get("/api/quiz-leaderboard") {
                val record = DatabaseClient.fetchTopQuizRecord()
                call.respond(record ?: QuizRecord("—", 0, ""))
            }

            post("/api/quiz-leaderboard") {
                val submission = call.receive<QuizRecordSubmission>()
                val updated = DatabaseClient.submitQuizRecordIfBeatsCurrent(submission.playerName, submission.streak)
                call.respond(updated)
            }

            // 🚗 Araç fiyatı karşılaştırma modülü — model listesi ve seçilen modelin
            // 13 ülkedeki tahmini/gerçek fiyatları.
            get("/api/car-models") {
                call.respond(DatabaseClient.fetchCarModels())
            }

            get("/api/car-prices") {
                val model = call.request.queryParameters["model"] ?: ""
                call.respond(DatabaseClient.fetchCarPricesForModel(model))
            }

            // 📅 Seçilebilir deneyim kademeleri (0-5 / 5-10 / 10+ / Tüm Deneyimler)
            get("/api/experience-tiers") {
                call.respond(DatabaseClient.fetchAllExperienceTiers())
            }

            // 💰 ?role=, ?household= ve ?experience= parametreleriyle filtreleniyor.
            // sırasıyla "Backend Developer" ve "single" varsayılan. Sonuç en yüksek
            // net kalan tutardan en düşüğe sıralı dönüyor.
            get("/api/countries") {
                val role = call.request.queryParameters["role"] ?: "Backend Developer"
                val household = call.request.queryParameters["household"] ?: "single"
                val experience = call.request.queryParameters["experience"] ?: "all"
                val countries = DatabaseClient.fetchAllCountries(role, household, experience)
                call.respond(countries)
            }
        }
    }.start(wait = true)
}