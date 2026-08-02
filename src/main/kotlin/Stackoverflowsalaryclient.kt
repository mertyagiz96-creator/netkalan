import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

// 📊 Stack Overflow Developer Survey'in RESMİ, herkese açık, ücretsiz ham verisi.
// Kaynak: StackExchange'in kendi GitHub reposu (uydurma değil, doğrudan gerçek
// yanıt satırları). Her satır: bir katılımcının ülkesi, rolü (DevType) ve
// USD'ye çevrilmiş yıllık maaşı (ConvertedCompYearly). Biz bunları ülke+rol
// bazında GRUPLAYIP MEDYAN alıyoruz — global bir ortalamayı ölçeklemek değil,
// gerçek dağılımdan gerçek bir sayı çıkarmak.
object StackOverflowSalaryClient {
    private const val CSV_URL = "https://github.com/StackExchange/Survey/raw/refs/heads/main/packages/archive/2025/results.csv"
    private val localCacheFile = File("so_survey_2025.csv")
    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 360_000 }
    }

    data class RealSalary(val medianUsd: Double, val sampleSize: Int)

    // 🌍 Anketteki ülke isimlerini bizim ISO kodlarımıza çeviriyor. SO'nun kullandığı
    // tam metin format değişebiliyor, bu yüzden birkaç varyant ekliyoruz.
    private val countryNameToCode = mapOf(
        "United States of America" to "US",
        "Germany" to "DE",
        "United Kingdom of Great Britain and Northern Ireland" to "GB",
        "Netherlands" to "NL",
        "Turkey" to "TR", "Türkiye" to "TR",
        "India" to "IN",
        "Brazil" to "BR",
        "Canada" to "CA",
        "Poland" to "PL",
        "Australia" to "AU",
        "Switzerland" to "CH",
        "France" to "FR",
        "Japan" to "JP"
    )

    // 💻 SO'nun "DevType" (çoklu seçim, noktalı virgülle ayrılmış metin) alanındaki
    // metinleri bizim rol isimlerimize eşliyoruz. BİREBİR string eşleşmesi yerine
    // ANAHTAR KELİME içeriyor mu diye bakıyoruz — çünkü anket yıldan yıla ifadeleri
    // hafifçe değiştirebiliyor (örn. "DevOps specialist" → "DevOps Engineer" gibi),
    // tam eşleşme bunu kaçırıyordu (DevOps/Security 0 yanıt buluyordu).
    private val roleKeywords = listOf(
        "back-end" to "Backend Developer",
        "front-end" to "Frontend Developer",
        "full-stack" to "Full-Stack Developer",
        "mobile" to "Mobile Developer",
        "devops" to "DevOps Engineer",
        "dev ops" to "DevOps Engineer",
        "cloud infrastructure" to "Cloud Engineer",
        "data engineer" to "Data Engineer",
        "engineer, data" to "Data Engineer",
        "machine learning" to "Data Scientist / ML Engineer",
        "data scientist" to "Data Scientist / ML Engineer",
        "security" to "Security Professional",
        "engineering manager" to "Engineering Manager"
    )

    private fun matchRoleFromDevType(devType: String): String? {
        val lower = devType.lowercase()
        for ((keyword, role) in roleKeywords) {
            if (lower.contains(keyword)) return role
        }
        return null
    }

    // Dönen map key formatı: "US|Backend Developer"
    suspend fun computeRealSalaries(): Map<String, RealSalary> {
        val file = downloadIfStale() ?: return emptyMap()

        return withContext(Dispatchers.IO) {
            val salaryLists = mutableMapOf<String, MutableList<Double>>()

            try {
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    val parser = CSVParser(reader, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())
                    for (record in parser) {
                        val country = try { record.get("Country") } catch (e: Exception) { null }
                            ?.let { countryNameToCode[it] } ?: continue
                        val devTypeRaw = try { record.get("DevType") } catch (e: Exception) { null } ?: continue
                        val compRaw = try { record.get("ConvertedCompYearly") } catch (e: Exception) { null } ?: continue
                        val comp = compRaw.toDoubleOrNull() ?: continue
                        if (comp < 5000 || comp > 2_000_000) continue // 🧹 uç/hatalı veriyi ele

                        for (dt in devTypeRaw.split(";").map { it.trim() }) {
                            val role = matchRoleFromDevType(dt) ?: continue
                            salaryLists.getOrPut("$country|$role") { mutableListOf() }.add(comp)
                        }
                    }
                }
            } catch (e: Exception) {
                println("🔥 SO Survey parse hatası: ${e.message}")
                return@withContext emptyMap<String, RealSalary>()
            }

            salaryLists.mapValues { (_, values) ->
                val sorted = values.sorted()
                val median = if (sorted.size % 2 == 0) {
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
                } else {
                    sorted[sorted.size / 2]
                }
                RealSalary(medianUsd = median, sampleSize = sorted.size)
            }
        }
    }

    // 💾 CSV'yi diske önbelleğe alıyoruz — her sunucu yeniden başlatmasında onlarca
    // MB indirmeyelim diye. 30 günden eskiyse (ya da hiç yoksa) tekrar indiriyor.
    //
    // ⚠️ ÖNEMLİ: Dosyayı ASLA tamamen hafızaya (ByteArray) almıyoruz — 512MB RAM'li
    // ücretsiz sunucularda 40-100MB'lık bir dosyayı bir kerede hafızaya yığmak
    // OOM (bellek yetersizliği) riski taşıyor. Bunun yerine HTTP yanıtını küçük
    // parçalar (chunk) halinde OKUYUP DOĞRUDAN DİSKE YAZIYORUZ — aynı anda
    // hafızada sadece birkaç KB'lık bir arabellek tutuluyor.
    private suspend fun downloadIfStale(): File? {
        val isStale = !localCacheFile.exists() ||
                Instant.now().minus(30, ChronoUnit.DAYS).isAfter(Instant.ofEpochMilli(localCacheFile.lastModified()))

        if (!isStale) return localCacheFile

        return try {
            println("📥 Stack Overflow anket verisi indiriliyor (gerçek streaming modunda)...")

            withContext(Dispatchers.IO) {
                // ⚠️ ÖNEMLİ: client.get(url) KULLANMIYORUZ — Ktor o fonksiyonda yanıtı
                // önce tamamen hafızaya "kaydediyor" (SavedCall mekanizması), bizim
                // streaming döngümüz devreye girmeden OOM oluyordu. prepareGet +
                // execute { } gerçek, hiç buferlemeyen streaming sağlıyor.
                client.prepareGet(CSV_URL).execute { httpResponse ->
                    val channel: ByteReadChannel = httpResponse.bodyAsChannel()
                    localCacheFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var totalBytes = 0L
                        while (!channel.isClosedForRead) {
                            val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                            if (bytesRead == -1) break
                            if (bytesRead > 0) {
                                output.write(buffer, 0, bytesRead)
                                totalBytes += bytesRead
                            }
                        }
                        println("✅ Stack Overflow anket verisi indirildi (~${totalBytes / 1_000_000} MB, gerçek streaming).")
                    }
                }
            }
            localCacheFile
        } catch (e: Exception) {
            println("🔥 SO Survey indirme hatası: ${e.message}")
            if (localCacheFile.exists()) localCacheFile else null
        }
    }
}