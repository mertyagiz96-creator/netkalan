import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

// 💡 Bir ülkenin tam finansal profili — frontend bunu direkt render ediyor.
@Serializable
data class CountryFinancials(
    val countryCode: String,       // "US", "DE", "TR" ...
    val countryNameTr: String,     // "Amerika Birleşik Devletleri"
    val role: String,              // "Senior Backend Developer"
    val grossAnnualUsd: Double,    // Brüt yıllık maaş (USD)
    val taxWedgePercent: Double,   // Vergi kaması yüzdesi (0-100)
    val netAnnualUsd: Double,      // Net yıllık maaş (USD) — hesaplanmış
    val monthlyRentUsd: Double,    // Ortalama kira (1+1, şehir merkezi, USD)
    val monthlyExpenseUsd: Double, // Kira hariç ortalama aylık gider (market, ulaşım, fatura vs.)
    val monthlyNetRemainingUsd: Double, // = (netAnnual/12) - rent - expense
    val salarySource: String,      // Kaynak açıklaması
    val costSource: String,        // Kaynak açıklaması
    val lastUpdated: String,       // "2026-08"
    val cities: List<CityOption> = emptyList(), // 🏙️ Bu ülke için seçilebilir şehirler
    val unemploymentPercent: Double? = null, // 🌍 World Bank — GERÇEK, resmi, canlı veri
    val inflationPercent: Double? = null,    // 🌍 World Bank — GERÇEK, resmi, canlı veri
    val audiA3PriceUsd: Double? = null,      // 🚗 Standart Audi A3 fiyatı (6 ülke gerçek, diğerleri ölçekli tahmin)
    val audiA3PriceIsReal: Boolean = false
)

@Serializable
data class RoleInfo(val role: String, val note: String, val aliasEn: String = "")

@Serializable
data class CityOption(
    val cityNameTr: String,
    val rentMultiplier: Double,
    val expenseMultiplier: Double
)

@Serializable
data class ExperienceInfo(val key: String, val labelTr: String)

@Serializable
data class HouseholdInfo(val key: String, val labelTr: String)

@Serializable
data class DataCoverageRow(
    val countryCode: String,
    val role: String,
    val isRealSalary: Boolean,
    val salarySampleSize: Int?,
    val isRealCost: Boolean
)

@Serializable
data class QuizRecord(val playerName: String, val streak: Int, val achievedAt: String)

@Serializable
data class QuizRecordSubmission(val playerName: String, val streak: Int)

object DatabaseClient {

    // 🔄 Bu sayıyı, seed verisinin YAPISINI değiştiren her değişiklikte (yeni rol,
    // yeni ülke, yeni kolon vb.) elle 1 artırıyoruz. Uygulama açılışta bu sayıyı
    // veritabanındaki kayıtlı değerle karşılaştırıyor; uyuşmazsa netkalan.db'yi
    // ELLE SİLMEYE GEREK KALMADAN kendi kendine sıfırlayıp yeniden seed ediyor.
    private const val SCHEMA_VERSION = 13

    // 🌍 World Bank'ten arka planda çekilen GERÇEK işsizlik/enflasyon verisi burada
    // önbelleğe alınıyor (Application.kt başlangıçta doldurup güncelliyor).
    // Henüz çekilmemişse null kalır, UI "yakında" gösterir — uydurma değer yok.
    data class MacroData(val unemploymentPercent: Double?, val inflationPercent: Double?)
    val macroDataCache = java.util.concurrent.ConcurrentHashMap<String, MacroData>()

    // 📊 Stack Overflow Survey'den hesaplanan GERÇEK medyan maaşlar — key: "US|Backend Developer".
    // fetchAllCountries bunu bulursa, tahmini/ölçeklenmiş rakam yerine bunu kullanır.
    val realSalaryCache = java.util.concurrent.ConcurrentHashMap<String, StackOverflowSalaryClient.RealSalary>()
    private const val MIN_SAMPLE_SIZE_FOR_REAL_DATA = 10 // bundan az yanıtlı kombinasyonlar güvenilmez sayılıyor

    // 🏠 WhereNext'ten çekilen GERÇEK kira/gider verisi — key: ülke kodu ("US", "DE"...).
    val costOfLivingCache = java.util.concurrent.ConcurrentHashMap<String, WhereNextClient.CostOfLiving>()

    // 🏙️ Şehir çarpanları TAHMİNİDİR — genel piyasa gözlemine dayanıyor, ilk
    // eleman her zaman "Ülke Ortalaması" (çarpan 1.0), diğerleri örnek şehirler.
    private val cityOptionsByCountry = mapOf(
        "US" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("New York", 1.6, 1.3), CityOption("Austin", 0.8, 0.9), CityOption("Chicago", 1.2, 1.1)),
        "DE" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Münih", 1.4, 1.15), CityOption("Berlin", 1.1, 1.0), CityOption("Hamburg", 1.15, 1.0)),
        "GB" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Londra", 1.7, 1.3), CityOption("Manchester", 0.7, 0.85), CityOption("Birmingham", 0.75, 0.85)),
        "NL" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Amsterdam", 1.3, 1.15), CityOption("Rotterdam", 0.9, 0.95), CityOption("Lahey", 1.0, 0.95)),
        "TR" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("İstanbul", 1.3, 1.15), CityOption("Ankara", 0.85, 0.9), CityOption("İzmir", 0.95, 0.95)),
        "IN" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Bangalore", 1.3, 1.1), CityOption("Pune", 0.9, 0.9), CityOption("Mumbai", 1.5, 1.2)),
        "BR" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("São Paulo", 1.3, 1.1), CityOption("Florianópolis", 0.9, 0.9), CityOption("Rio de Janeiro", 1.2, 1.05)),
        "CA" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Toronto", 1.4, 1.15), CityOption("Calgary", 0.85, 0.9), CityOption("Vancouver", 1.5, 1.2)),
        "PL" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Varşova", 1.2, 1.1), CityOption("Wrocław", 0.85, 0.9), CityOption("Kraków", 0.9, 0.9)),
        "AU" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Sidney", 1.3, 1.15), CityOption("Adelaide", 0.8, 0.9), CityOption("Melbourne", 1.15, 1.05)),
        "CH" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Zürih", 1.3, 1.15), CityOption("Cenevre", 1.25, 1.1), CityOption("Bern", 0.9, 0.95)),
        "FR" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Paris", 1.5, 1.2), CityOption("Lyon", 0.85, 0.9), CityOption("Marsilya", 0.75, 0.85)),
        "JP" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Tokyo", 1.3, 1.15), CityOption("Osaka", 0.85, 0.9), CityOption("Yokohama", 1.1, 1.0)),
        "AE" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Dubai", 1.4, 1.2), CityOption("Abu Dabi", 1.1, 1.0)),
        "CN" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Şangay", 1.5, 1.2), CityOption("Pekin", 1.4, 1.15), CityOption("Shenzhen", 1.35, 1.1)),
        "RU" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Moskova", 1.6, 1.3), CityOption("St. Petersburg", 1.2, 1.1)),
        "DK" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Kopenhag", 1.4, 1.15), CityOption("Aarhus", 0.9, 0.95))
    )

    // 👪 Hane tipi ayarları — vergi kaması deltası (yüzde puan, OECD Taxing Wages'in
    // "single" vs "one-earner married" tablolarındaki genel farktan yaklaşık alınmıştır)
    // + OECD'nin resmi "modified equivalence scale" ile gider çarpanı (1 yetişkin=1,
    // +yetişkin=0.5, +çocuk=0.3) + kira için kabaca oda sayısı ihtiyacı çarpanı.
    private val householdSettings = linkedMapOf(
        "single" to HouseholdAdjustment("Bekar", taxWedgeDelta = 0.0, expenseMultiplier = 1.0, rentMultiplier = 1.0, numberOfKids = 0),
        "married_no_kids" to HouseholdAdjustment("Evli, Çocuksuz", taxWedgeDelta = -3.0, expenseMultiplier = 1.5, rentMultiplier = 1.3, numberOfKids = 0),
        "married_1kid" to HouseholdAdjustment("Evli, 1 Çocuklu", taxWedgeDelta = -5.5, expenseMultiplier = 1.8, rentMultiplier = 1.45, numberOfKids = 1),
        "married_2kids" to HouseholdAdjustment("Evli, 2 Çocuklu", taxWedgeDelta = -8.0, expenseMultiplier = 2.1, rentMultiplier = 1.6, numberOfKids = 2)
    )

    private data class HouseholdAdjustment(
        val labelTr: String, val taxWedgeDelta: Double, val expenseMultiplier: Double,
        val rentMultiplier: Double, val numberOfKids: Int
    )

    // 💡 Stack Overflow Developer Survey 2025 GLOBAL ortalamalarından türetilmiş
    // çarpanlar (baseline: Backend Developer = 1.0). Ülke bazlı gerçek rol verisi
    // olmadığı için mevcut backend maaşını bu oranla ölçekliyoruz — TAHMİNİ bir
    // yöntem, gerçek ülke-bazlı anket verisi değil. UI'da açıkça belirtiliyor.
    private val roleMultipliers = linkedMapOf(
        "Backend Developer" to 1.0,
        "Frontend Developer" to 0.794,
        "Full-Stack Developer" to 0.779,
        "Mobile Developer" to 1.088,
        "DevOps Engineer" to 0.853,
        "Cloud Engineer" to 0.971,
        "Data Engineer" to 0.882,
        "Data Scientist / ML Engineer" to 0.941,
        "Siber Güvenlik Uzmanı" to 0.794,
        "Engineering Manager" to 1.132
    )

    // 💰 Ülke bazlı KABA ortalama yıllık ücret tahmini (USD) — OECD Average Wage
    // ve genel bilgiye dayalı, resmi/kesin değil. Tech-olmayan mesleklerin
    // ölçekleneceği baz nokta bu (Backend Developer maaşı değil).
    private val nationalAvgWageUsd = mapOf(
        "US" to 65000.0, "DE" to 58000.0, "GB" to 48000.0, "NL" to 64000.0,
        "TR" to 15000.0, "IN" to 3000.0, "BR" to 10000.0, "CA" to 59000.0,
        "PL" to 24000.0, "AU" to 62000.0, "CH" to 95000.0, "FR" to 40000.0, "JP" to 35000.0,
        "AE" to 40000.0, "CN" to 18000.0, "RU" to 12000.0, "DK" to 82000.0
    )

    // 👔 Tech-olmayan meslekler için, dünya genelinde tipik olarak ulusal ortalama
    // ücrete göre kaç kat kazandıklarına dair KABA uluslararası genel bilgi.
    // Bu, tech rol çarpanlarından (Stack Overflow kaynaklı) daha az güvenilir —
    // ülkeye özel resmi veri değil, genel gözlem/tahmin.
    // 👔 Tech-olmayan meslekler için ulusal ortalamaya göre çarpan — ABD Bureau
    // of Labor Statistics (BLS) resmi maaş verisiyle çapraz kontrol edilip
    // düzeltildi (2026-08). Örn. Doktor: BLS $238,380 / ulusal ortalama $65,000
    // = 3.67x. Bu ABD'ye özel oranı diğer ülkelere de uyguluyoruz — meslekler
    // arası GÖRECELİ farkın ülkeden ülkeye kabaca benzer kaldığı varsayımıyla
    // (yine de tam kesin değil, ülkeye özel resmi veri değil).
    private val nonTechRoleMultipliers = linkedMapOf(
        "Doktor" to 3.67,
        "Hemşire" to 1.44,
        "Öğretmen" to 0.95,
        "Avukat" to 2.09,
        "Muhasebeci / Finans Uzmanı" to 1.23,
        "Pazarlama Yöneticisi" to 2.15,
        "İnşaat Mühendisi" to 1.48,
        "Mimar" to 1.44,
        "Perakende / Hizmet Çalışanı" to 0.54,
        "Makine Mühendisi" to 1.35
    )

    // 🔧 Makine Mühendisi için GERÇEK, ERI SalaryExpert'ten (consultancy-grade
    // maaş anketi) tek tek doğrulanmış yıllık brüt maaşlar (USD). Kanada ve
    // İsviçre için ikincil bir kaynaktan (instarem.com), biraz daha az kesin.
    // Avustralya, Fransa, Çin, Rusya için gerçek veri bulunamadı — onlar hâlâ
    // tahmini (ulusal ortalama × meslek çarpanı) yöntemiyle hesaplanıyor.
    private val realMechanicalEngineerSalaryUsd = mapOf(
        "US" to 115437.0,
        "DE" to 92666.0,
        "GB" to 82725.0,
        "NL" to 85126.0,
        "TR" to 27038.0,
        "PL" to 47700.0,
        "JP" to 58115.0,
        "AE" to 76157.0,
        "IN" to 18077.0,
        "BR" to 30881.0,
        "CA" to 74160.0,
        "CH" to 93225.0
    )

    // 🩺 Doktor için GERÇEK maaş — 16 ülkenin HEPSİ! Kaynak: worldpopulationreview.com
    // "Doctor Pay by Country 2026" (ERI SalaryExpert + Medic Footprints + World of
    // Statistics kaynaklarını birleştiren güvenilir toplu tablo).
    private val realDoctorSalaryUsd = mapOf(
        "US" to 268083.0, "CH" to 266900.0, "AU" to 199071.0, "CA" to 181623.0,
        "AE" to 178500.0, "NL" to 171400.0, "DE" to 168700.0, "GB" to 163200.0,
        "FR" to 155000.0, "JP" to 142000.0, "CN" to 92600.0, "PL" to 61400.0,
        "TR" to 63100.0, "BR" to 51984.0, "RU" to 41300.0, "IN" to 31200.0
    )

    // ⚖️ Avukat için GERÇEK veri — dürüst not: bu meslekte kaynaklar arası
    // TUTARSIZLIK çok yüksek (örn. Hollanda'da bile kaynaklar €49K-€108K arası
    // değişiyor, 2 katından fazla fark). Bu yüzden sadece ERI SalaryExpert'in
    // (tutarlı metodoloji, diğer rollerde de kullandığımız kaynak) verdiği ve
    // makul gördüğüm 6 ülkeyi ekliyoruz. ABD için ERI yerine resmi BLS rakamı
    // kullanıldı (daha güvenilir, devlet kaynaklı).
    private val realLawyerSalaryUsd = mapOf(
        "US" to 135740.0,   // BLS resmi medyan
        "CH" to 215378.0,   // ERI (€187,285)
        "GB" to 118791.0,   // ERI (€103,297)
        "NL" to 124502.0,   // ERI (€108,263)
        "FR" to 105375.0,   // ERI (€91,630)
        "TR" to 28631.0     // ERI (€24,897)
    )

    // 👩‍⚕️ Hemşire için GERÇEK veri — 4 ülke, birden fazla kaynağın kabaca
    // örtüştüğü (BLS + Credenza + Seven Seas + diğerleri) rakamlar.
    private val realNurseSalaryUsd = mapOf(
        "US" to 101420.0,  // BLS resmi
        "CH" to 115000.0,  // birden fazla kaynağın ortak aralığı
        "AU" to 82000.0,
        "CA" to 70000.0
    )

    // 🍎 Öğretmen için GERÇEK veri — OECD'nin resmi "Education at a Glance"
    // raporundan (Statista aktarımı, PPP-ayarlı, ilkokul öğretmeni ortalaması).
    private val realTeacherSalaryUsd = mapOf(
        "US" to 68153.0,
        "PL" to 55407.0,
        "DE" to 92000.0,
        "NL" to 91000.0,
        "CH" to 90000.0
    )

    // 💼 Muhasebeci için GERÇEK veri.
    private val realAccountantSalaryUsd = mapOf(
        "US" to 79880.0,   // BLS resmi
        "GB" to 72511.0,   // mpeslearning ortalama (£54,000)
        "AU" to 80000.0    // salarybyrole (aggregated)
    )

    // 🏛️ Mimar için GERÇEK veri (PayScale, tek kaynak — Doktor kadar çapraz
    // doğrulanmadı, ama yine de gerçek anket verisi).
    private val realArchitectSalaryUsd = mapOf(
        "DE" to 46920.0,   // PayScale (€40,800)
        "GB" to 49067.0    // PayScale (£36,533)
    )

    // 🛍️ Perakende/Hizmet Çalışanı için GERÇEK veri (BLS resmi + worldsalaries.com).
    private val realRetailSalaryUsd = mapOf(
        "US" to 37460.0,   // BLS resmi medyan
        "DE" to 33845.0    // worldsalaries.com ortalama
    )

    // 🌍 Türkçe/İngilizce ikili arama için — tech roller zaten İngilizce, sadece
    // tech-olmayan (Türkçe) rollerin İngilizce karşılığı gerekiyor.
    private val nonTechRoleAliasEn = mapOf(
        "Doktor" to "Doctor / Physician",
        "Hemşire" to "Nurse",
        "Öğretmen" to "Teacher",
        "Avukat" to "Lawyer",
        "Muhasebeci / Finans Uzmanı" to "Accountant / Finance",
        "Pazarlama Yöneticisi" to "Marketing Manager",
        "İnşaat Mühendisi" to "Civil Engineer",
        "Mimar" to "Architect",
        "Perakende / Hizmet Çalışanı" to "Retail / Service Worker",
        "Makine Mühendisi" to "Mechanical Engineer"
    )

    private const val POOL_SIZE = 4
    private val connectionPool: java.util.concurrent.BlockingQueue<Connection> by lazy { createConnectionPool() }

    private fun createConnectionPool(): java.util.concurrent.BlockingQueue<Connection> {
        val pool: java.util.concurrent.BlockingQueue<Connection> = java.util.concurrent.ArrayBlockingQueue(POOL_SIZE)
        repeat(POOL_SIZE) { pool.put(createConnection()) }
        return pool
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        val conn = connectionPool.take()
        try {
            return block(conn)
        } finally {
            connectionPool.put(conn)
        }
    }

    private fun createConnection(): Connection {
        val dbFile = File("netkalan.db")
        val isNew = !dbFile.exists()
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL;")
            stmt.execute("PRAGMA busy_timeout=5000;")
        }

        // 🏆 NOT: Tahmin modu liderlik tablosu artık burada (SQLite'ta) DEĞİL,
        // Supabase'in ücretsiz Postgres'inde tutuluyor (aşağıda fetchTopQuizRecord/
        // submitQuizRecordIfBeatsCurrent fonksiyonlarında) — Render free tier'da
        // kalıcı disk olmadığı için SQLite'taki rekor her uyku sonrası sıfırlanıyordu.

        if (isNew) {
            initSchemaAndSeed(conn)
            setSchemaVersion(conn)
        } else if (getStoredSchemaVersion(conn) != SCHEMA_VERSION) {
            // 🔄 Şema/seed yapısı değişmiş — eski tabloları silip sıfırdan kuruyoruz.
            // Bu sayede DatabaseClient.kt'yi güncelleyip çalıştırdığında netkalan.db'yi
            // elle silmene HİÇ gerek kalmıyor, otomatik algılanıp yenileniyor.
            println("🔄 Şema versiyonu değişmiş, netkalan.db otomatik olarak yeniden oluşturuluyor...")
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS country_financials")
                stmt.execute("DROP TABLE IF EXISTS schema_info")
            }
            initSchemaAndSeed(conn)
            setSchemaVersion(conn)
        }

        return conn
    }

    private fun getStoredSchemaVersion(conn: Connection): Int {
        return try {
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE IF NOT EXISTS schema_info (version INTEGER)")
                val rs = stmt.executeQuery("SELECT version FROM schema_info LIMIT 1")
                if (rs.next()) rs.getInt("version") else -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun setSchemaVersion(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_info (version INTEGER)")
            stmt.execute("DELETE FROM schema_info")
            stmt.execute("INSERT INTO schema_info (version) VALUES ($SCHEMA_VERSION)")
        }
    }

    private fun initSchemaAndSeed(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS country_financials (
                    country_code TEXT NOT NULL,
                    country_name_tr TEXT NOT NULL,
                    role TEXT NOT NULL,
                    gross_annual_usd REAL NOT NULL,
                    tax_wedge_percent REAL NOT NULL,
                    monthly_rent_usd REAL NOT NULL,
                    monthly_expense_usd REAL NOT NULL,
                    salary_source TEXT NOT NULL,
                    cost_source TEXT NOT NULL,
                    last_updated TEXT NOT NULL,
                    PRIMARY KEY (country_code, role)
                )
                """.trimIndent()
            )
        }

        // 💡 BAŞLANGIÇ VERİSİ (TAHMİNİ): Stack Overflow Developer Survey 2025 (maaş
        // referansı), OECD Taxing Wages 2025/2026 (vergi kaması, sadece OECD üyesi
        // ülkeler için resmi), ve genel piyasa gözlemleri (kira/gider, WhereNext /
        // Numbeo tarzı kaynaklarla çapraz kontrol edilecek). Bu rakamlar İLK SÜRÜM —
        // her ülke için tek tek kaynak linkiyle doğrulanana kadar "tahmini" kabul
        // edilmeli. role şu an tüm satırlarda sabit: "Senior Backend Developer".
        val seedRows = listOf(
            // code, nameTr, gross, taxWedge%, rentUsd, expenseUsd, salarySrc, costSrc, updated
            SeedRow("US", "Amerika Birleşik Devletleri", 150000.0, 29.0, 1800.0, 1200.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("DE", "Almanya", 85000.0, 47.9, 1100.0, 900.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("GB", "İngiltere", 75000.0, 31.0, 1500.0, 1000.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("NL", "Hollanda", 80000.0, 35.0, 1400.0, 950.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("TR", "Türkiye", 30000.0, 40.0, 400.0, 400.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("IN", "Hindistan", 25000.0, 20.0, 300.0, 350.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("BR", "Brezilya", 35000.0, 27.0, 500.0, 450.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("CA", "Kanada", 95000.0, 28.0, 1600.0, 1100.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("PL", "Polonya", 45000.0, 35.0, 700.0, 600.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("AU", "Avustralya", 110000.0, 28.0, 1700.0, 1300.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("CH", "İsviçre", 130000.0, 22.0, 2200.0, 1400.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("FR", "Fransa", 55000.0, 47.2, 1200.0, 950.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("JP", "Japonya", 70000.0, 32.0, 1300.0, 1000.0,
                "Stack Overflow Dev Survey 2025 (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("AE", "Birleşik Arap Emirlikleri", 120000.0, 0.0, 1800.0, 1200.0,
                "Genel piyasa gözlemi (yaklaşık, gelir vergisi %0 resmi/kesin)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("CN", "Çin", 45000.0, 25.0, 700.0, 500.0,
                "Genel piyasa gözlemi (yaklaşık)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
            SeedRow("RU", "Rusya", 19000.0, 13.0, 500.0, 400.0,
                "Genel piyasa gözlemi (yaklaşık, vergi oranı %13 sabit/resmi)", "Genel piyasa gözlemi (yaklaşık, veri güncelliği kısıtlı olabilir)", "2026-08"),
            SeedRow("DK", "Danimarka", 95000.0, 35.0, 1400.0, 1000.0,
                "ERI SalaryExpert 2026 (yaklaşık, gerçek DKK 649,949/yıl rakamından çevrilmiş)", "Genel piyasa gözlemi (yaklaşık)", "2026-08"),
        )

        val insertSql = """
            INSERT INTO country_financials
            (country_code, country_name_tr, role, gross_annual_usd, tax_wedge_percent, monthly_rent_usd, monthly_expense_usd, salary_source, cost_source, last_updated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        conn.prepareStatement(insertSql).use { stmt ->
            for (row in seedRows) {
                // 💻 Tech roller — mevcut mantık: bu ülkedeki backend maaşına göre ölçekleniyor.
                for ((role, multiplier) in roleMultipliers) {
                    val scaledGross = row.gross * multiplier
                    val salarySrc = if (role == "Backend Developer") {
                        row.salarySrc
                    } else {
                        "${row.salarySrc} — '${role}' için SO Survey global oranıyla ölçeklendi (tahmini)"
                    }
                    insertRow(stmt, row, role, scaledGross, salarySrc)
                }

                // 👔 Tech-olmayan roller — ülkenin ortalama ücretine göre ölçekleniyor,
                // daha kaba bir tahmin olduğu kaynak metninde açıkça belirtiliyor.
                // İSTİSNA: Makine Mühendisi için 12 ülkede ERI SalaryExpert'ten
                // GERÇEK maaş verisi varsa, tahmini formül yerine o kullanılıyor.
                val avgWage = nationalAvgWageUsd[row.code] ?: row.gross * 0.5
                for ((role, multiplier) in nonTechRoleMultipliers) {
                    val realOverride = when (role) {
                        "Makine Mühendisi" -> realMechanicalEngineerSalaryUsd[row.code]
                        "Doktor" -> realDoctorSalaryUsd[row.code]
                        "Avukat" -> realLawyerSalaryUsd[row.code]
                        "Hemşire" -> realNurseSalaryUsd[row.code]
                        "Öğretmen" -> realTeacherSalaryUsd[row.code]
                        "Muhasebeci / Finans Uzmanı" -> realAccountantSalaryUsd[row.code]
                        "Mimar" -> realArchitectSalaryUsd[row.code]
                        "Perakende / Hizmet Çalışanı" -> realRetailSalaryUsd[row.code]
                        else -> null
                    }
                    val scaledGross = realOverride ?: (avgWage * multiplier)
                    val salarySrc = if (realOverride != null) {
                        when (role) {
                            "Doktor" -> "worldpopulationreview.com 'Doctor Pay by Country 2026' — GERÇEK (SalaryExpert/Medic Footprints kaynaklı)"
                            "Avukat" -> "ERI SalaryExpert / BLS 2026 — GERÇEK (not: bu meslekte kaynaklar arası tutarsızlık normalden yüksek)"
                            "Hemşire" -> "BLS / çoklu kaynak ortalaması 2026 — GERÇEK"
                            "Öğretmen" -> "OECD 'Education at a Glance' (Statista aktarımı) — GERÇEK, resmi"
                            "Muhasebeci / Finans Uzmanı" -> "BLS / çoklu kaynak 2026 — GERÇEK"
                            "Mimar" -> "PayScale 2026 — GERÇEK (tek kaynak, çapraz doğrulanmadı)"
                            "Perakende / Hizmet Çalışanı" -> "BLS / worldsalaries.com 2026 — GERÇEK"
                            else -> "ERI SalaryExpert 2026 — GERÇEK (profesyonel maaş anketi verisi)"
                        }
                    } else {
                        "Ülke ortalama ücreti × BLS-türetilmiş meslek oranı (ABD verisine dayalı tahmin, ülkeye özel veri değil)"
                    }
                    insertRow(stmt, row, role, scaledGross, salarySrc)
                }
            }
            stmt.executeBatch()
        }
    }

    private fun insertRow(stmt: java.sql.PreparedStatement, row: SeedRow, role: String, gross: Double, salarySrc: String) {
        stmt.setString(1, row.code)
        stmt.setString(2, row.nameTr)
        stmt.setString(3, role)
        stmt.setDouble(4, gross)
        stmt.setDouble(5, row.taxWedge)
        stmt.setDouble(6, row.rent)
        stmt.setDouble(7, row.expense)
        stmt.setString(8, salarySrc)
        stmt.setString(9, row.costSrc)
        stmt.setString(10, row.updated)
        stmt.addBatch()
    }

    fun fetchAllRoles(): List<RoleInfo> {
        val techRoles = roleMultipliers.keys.map { role ->
            RoleInfo(
                role = role,
                note = if (role == "Backend Developer") "Doğrudan anket verisi" else "Backend'e göre ölçeklenmiş tahmin"
            )
        }
        val otherRoles = nonTechRoleMultipliers.keys.map { role ->
            RoleInfo(role = role, note = "Kaba tahmin — ülkeye özel veri değil", aliasEn = nonTechRoleAliasEn[role] ?: "")
        }
        return techRoles + otherRoles
    }

    private data class SeedRow(
        val code: String, val nameTr: String, val gross: Double, val taxWedge: Double,
        val rent: Double, val expense: Double, val salarySrc: String, val costSrc: String, val updated: String
    )

    // 🎓 Her ülkenin flagship şehri için okul ücreti çapası (USD/yıl). Bazıları
    // GERÇEK/isim verilmiş kaynaklardan (US, DE, NL, BR, CH, JP, GB — WhereNext,
    // ischooladvisor.com, housingjapan.com raporları), bazıları (TR, IN, CA, PL,
    // AU, FR) doğrudan bir kaynak bulamadığım GENEL TAHMİN (benzer pazarlarla
    // kıyaslanarak). fetchSchoolCostSourceLabel bu ayrımı UI'da açıkça belirtiyor.
    private val schoolCostAnchorsUsd = mapOf(
        "US" to 43003.0,  // New York (GERÇEK: doris.school)
        "DE" to 24700.0,  // Münih (GERÇEK: ischooladvisor.com)
        "GB" to 27500.0,  // Londra (GERÇEK: WhereNext 2026)
        "NL" to 22000.0,  // Amsterdam (GERÇEK: ischooladvisor.com, ISA fiyat listesi)
        "TR" to 10000.0,  // İstanbul (tahmini, doğrudan kaynak bulunamadı)
        "IN" to 7000.0,   // Bangalore (tahmini)
        "BR" to 24000.0,  // São Paulo (GERÇEK: ischooladvisor.com)
        "CA" to 20000.0,  // Toronto (tahmini)
        "PL" to 10000.0,  // Varşova (GERÇEK aralığa dayalı: WhereNext "Poland <$12K")
        "AU" to 18000.0,  // Sidney (tahmini)
        "CH" to 37500.0,  // Zürih (GERÇEK: WhereNext 2026, $30-45K aralığı)
        "FR" to 17000.0,  // Paris (tahmini)
        "JP" to 16300.0   // Tokyo (GERÇEK: housingjapan.com/ischooladvisor.com)
    )

    // 🎓 Şehir seçimi frontend'de (JS) yapılıyor, backend'in hangi şehrin seçili
    // olduğu bilgisi yok. Bu yüzden burada "Ülke Ortalaması" için bir değer
    // hesaplıyoruz (flagship çapasını, flagship'in ülke ortalamasına göre ne kadar
    // pahalı olduğu oranıyla aşağı ölçekleyerek) — frontend zaten seçili şehrin
    // genel expenseMultiplier'ını bu değere ayrıca uyguluyor (rent/gider için
    // yaptığı gibi), yani şehir seçince okul ücreti de dolaylı olarak değişiyor.
    private fun estimateAnnualSchoolCostUsd(countryCode: String): Pair<Double, Boolean>? {
        val anchor = schoolCostAnchorsUsd[countryCode] ?: return null
        val cities = cityOptionsByCountry[countryCode] ?: return null
        val flagshipMultiplier = cities.maxOfOrNull { it.rentMultiplier } ?: 1.0
        val countryAverageValue = if (flagshipMultiplier > 0) anchor / flagshipMultiplier else anchor
        // Çapa flagship şehir için gerçekti; ülke ortalamasına indirgemek bir
        // ölçekleme olduğu için bunu her zaman "tahmini" olarak işaretliyoruz.
        return Pair(countryAverageValue, false)
    }

    // 🚗 Audi A3 (standart/taban model) fiyatı — 6 ülke için GERÇEK, resmi üretici
    // fiyatlarından (2026, güncel döviz kuruyla USD'ye çevrilmiş). Diğer ülkeler
    // için gerçek fiyat bulunamadı — Almanya'nın GERÇEK fiyatı, elimizdeki GERÇEK
    // WhereNext yaşam maliyeti endeksiyle ORANTILI ölçekleniyor (okul ücretiyle
    // aynı yöntem). Kaynaklar: Audi resmi siteleri, Audi Türkiye, Audi Kanada,
    // audi.de, güncel Temmuz/Ağustos 2026 fiyat listeleri.
    private val realAudiA3PriceUsd = mapOf(
        "US" to 41395.0,
        "DE" to 37570.0,
        "TR" to 79550.0,
        "CA" to 33860.0,
        "CH" to 53280.0,
        "FR" to 41060.0,
        "DK" to 88325.0  // Danimarka'nın GERÇEK, resmi kayıt vergisi formülü (motorst.dk) Audi'nin Almanya fiyatına uygulanarak hesaplandı — dünyanın en yüksek araç vergilerinden biri
    )

    data class CarPriceEstimate(val priceUsd: Double, val isReal: Boolean)

    // 🚗 ÖNEMLİ DÜZELTME: Daha önce burada WhereNext yaşam maliyeti endeksiyle
    // ölçekleyen bir "tahmini" dal vardı — bu YANLIŞTI, çünkü araba ithal bir mal,
    // yerel yaşam maliyetiyle ilgisi yok (Hindistan'da $6K gibi saçma sonuçlar
    // veriyordu). Artık SADECE gerçek fiyatını bildiğimiz 6 ülke için değer
    // döndürüyoruz, diğerlerinde null (UI kartı hiç göstermiyor) — yanlış
    // tahminden, veri olmamasını tercih ediyoruz.
    private fun estimateAudiA3PriceUsd(countryCode: String): CarPriceEstimate? {
        val real = realAudiA3PriceUsd[countryCode] ?: return null
        return CarPriceEstimate(real, true)
    }

    // 🚗 ARAÇ FİYATI KARŞILAŞTIRMA MODÜLÜ — Audi A3'ün 6 ülkedeki GERÇEK fiyatından
    // türetilen "ülke araç fiyat endeksi"ni (ithalat vergisi/ÖTV, kâr marjı gibi
    // ülkeye özgü farkları yakalıyor) diğer popüler modellere uyguluyoruz. Yani her
    // modeli her ülke için ayrı ayrı aramak yerine, ABD'deki GERÇEK taban fiyatını
    // bu endeksle ölçekliyoruz. Sadece ABD fiyatı %100 gerçek; diğerleri Audi
    // endeksinden türetilmiş TAHMİNDİR — UI'da net belirtiliyor.
    // 🚗 GERÇEK, tek tek araştırılmış model+ülke fiyatları — endeks tahmininin
    // ÖNÜNE geçiyor. Bu bir API'den otomatik gelmiyor (böyle bir kaynak yok),
    // ben elle arayıp doğruladıkça buraya ekleniyor. Şu an sadece birkaçı var,
    // zamanla genişleyecek. Kaynak: ilgili markanın Türkiye resmi fiyat listesi
    // / güncel haber siteleri, Ağustos 2026.
    private val realCarPricesByModelAndCountry = mapOf(
        "Toyota Corolla" to mapOf("TR" to 2284000.0 / 45.0),  // ~2.284M TL, Toyota TR resmi liste
        "Honda CR-V" to mapOf("TR" to 2200000.0 / 45.0),       // ~2.2M TL, xenotomotiv/segment tutarlılığı
        "Hyundai Tucson" to mapOf("TR" to 2361262.0 / 45.0),   // 2.361.262 TL, Hyundai TR resmi liste
        "Volkswagen Golf" to mapOf("DK" to 400000.0 / 6.85)    // ~400.000 DKK (380-420K aralığı ortası), exploringdenmark.com gerçek örnek hesap
    )

    data class CarModel(val name: String, val category: String, val usBasePriceUsd: Double)

    val carModelsList = listOf(
        CarModel("Audi A3", "Kompakt Lüks Sedan", 41395.0),
        CarModel("BMW 3 Series", "Lüks Sedan", 45000.0),
        CarModel("Chevrolet Equinox", "SUV (Kompakt)", 28000.0),
        CarModel("Ford Escape", "SUV (Kompakt)", 31845.0),
        CarModel("Honda CR-V", "SUV (Kompakt)", 32370.0),
        CarModel("Hyundai Tucson", "SUV (Kompakt)", 28500.0),
        CarModel("Kia Sportage", "SUV (Kompakt)", 30285.0),
        CarModel("Mazda CX-5", "SUV (Kompakt)", 31485.0),
        CarModel("Mercedes-Benz C-Class", "Lüks Sedan", 47000.0),
        CarModel("Nissan Rogue", "SUV (Kompakt)", 30585.0),
        CarModel("Nissan Sentra", "Sedan (Ekonomik)", 21000.0),
        CarModel("Tesla Model 3", "Elektrikli Sedan", 42000.0),
        CarModel("Toyota Camry", "Sedan (Orta Segment)", 29600.0),
        CarModel("Toyota Corolla", "Sedan (Ekonomik)", 23125.0),
        CarModel("Toyota RAV4", "SUV (Kompakt Hibrit)", 33350.0),
        CarModel("Volkswagen Golf", "Hatchback", 21845.0)
    )

    // 🚗 ÖNEMLİ DÜZELTME: WhereNext'in yaşam maliyeti endeksini araba fiyatı
    // ölçeklemek için kullanmak YANLIŞTI — araba ithal/üretilmiş bir mal, yerel
    // kira/market fiyatlarıyla (WhereNext'in ölçtüğü şey) ilgisi yok. Bu yüzden
    // Hindistan gibi düşük yaşam maliyetli ülkelerde saçma derecede düşük ($6K
    // gibi) rakamlar çıkıyordu. Artık SADECE gerçek Audi A3 fiyatı bildiğimiz
    // 6 ülke için endeks hesaplıyoruz — yanlış tahmin üretmek yerine, veri
    // olmayan ülkede hiç göstermiyoruz.
    private fun computeCarPriceIndex(countryCode: String): Double? {
        val realPrice = realAudiA3PriceUsd[countryCode] ?: return null
        val usPrice = realAudiA3PriceUsd["US"] ?: return null
        if (usPrice <= 0) return null
        return realPrice / usPrice
    }

    @Serializable
    data class CarModelInfo(val name: String, val category: String)

    @Serializable
    data class CarPriceResult(val countryCode: String, val countryNameTr: String, val priceUsd: Double, val isReal: Boolean)

    private val countryNamesTr = mapOf(
        "US" to "Amerika Birleşik Devletleri", "DE" to "Almanya", "GB" to "İngiltere",
        "NL" to "Hollanda", "TR" to "Türkiye", "IN" to "Hindistan", "BR" to "Brezilya",
        "CA" to "Kanada", "PL" to "Polonya", "AU" to "Avustralya", "CH" to "İsviçre",
        "FR" to "Fransa", "JP" to "Japonya", "AE" to "Birleşik Arap Emirlikleri",
        "CN" to "Çin", "RU" to "Rusya", "DK" to "Danimarka"
    )

    fun fetchCarModels(): List<CarModelInfo> = carModelsList.map { CarModelInfo(it.name, it.category) }

    fun fetchCarPricesForModel(modelName: String): List<CarPriceResult> {
        val model = carModelsList.find { it.name == modelName } ?: return emptyList()

        // 🚗 Audi A3 özel durum: bu model için zaten 6 ülkede GERÇEK fiyat
        // biliyoruz (endeksin kendisi bundan türetiliyor) — dolaylı endeks
        // hesabına gerek yok, doğrudan gerçek/ölçeklenmiş fonksiyonu kullanıyoruz.
        if (modelName == "Audi A3") {
            return cityOptionsByCountry.keys.mapNotNull { countryCode ->
                val estimate = estimateAudiA3PriceUsd(countryCode) ?: return@mapNotNull null
                CarPriceResult(countryCode, countryNamesTr[countryCode] ?: countryCode, estimate.priceUsd, estimate.isReal)
            }.sortedByDescending { it.priceUsd }
        }

        return cityOptionsByCountry.keys.mapNotNull { countryCode ->
            // 📌 Önce gerçek, tek tek araştırılmış fiyata bakıyoruz.
            val realOverride = realCarPricesByModelAndCountry[modelName]?.get(countryCode)
            if (realOverride != null) {
                return@mapNotNull CarPriceResult(countryCode, countryNamesTr[countryCode] ?: countryCode, realOverride, true)
            }
            val index = computeCarPriceIndex(countryCode) ?: return@mapNotNull null
            val isReal = countryCode == "US"
            CarPriceResult(countryCode, countryNamesTr[countryCode] ?: countryCode, model.usBasePriceUsd * index, isReal)
        }.sortedByDescending { it.priceUsd }
    }
    // 📅 Deneyim kademeleri — tech rollerde SO anketinin GERÇEK YearsCodePro
    // verisinden hesaplanıyor. Gerçek veri yetersizse (küçük örneklem/non-tech
    // rol) bu ÇARPANLAR devreye giriyor — "all" (tüm deneyimler, mevcut
    // tahmine dokunmuyor) baz alınarak ölçekleniyor.
    private val experienceTiers = linkedMapOf(
        "all" to "Tüm Deneyim Seviyeleri",
        "0-5" to "0-5 Yıl",
        "5-10" to "5-10 Yıl",
        "10+" to "10+ Yıl"
    )
    private val experienceFallbackMultipliers = mapOf(
        "all" to 1.0, "0-5" to 0.75, "5-10" to 1.0, "10+" to 1.35
    )

    fun fetchAllExperienceTiers(): List<ExperienceInfo> {
        return experienceTiers.map { (key, label) -> ExperienceInfo(key, label) }
    }

    fun fetchAllHouseholds(): List<HouseholdInfo> {
        return householdSettings.map { (key, adj) -> HouseholdInfo(key, adj.labelTr) }
    }

    // 🏆 Global liderlik tablosu — artık SQLite'ta DEĞİL, Supabase'in ücretsiz
    // Postgres'inde tutuluyor (REST API üzerinden). Render'ın free tier'ı kalıcı
    // disk sunmadığı için SQLite'taki rekor her uyku/deploy sonrası sıfırlanıyordu
    // — Supabase gerçekten kalıcı, ücretsiz bir dış servis.
    private val supabaseUrl = System.getenv("SUPABASE_URL")?.trimEnd('/')
    private val supabaseKey = System.getenv("SUPABASE_KEY")

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    suspend fun fetchTopQuizRecord(): QuizRecord? {
        if (supabaseUrl == null || supabaseKey == null) {
            println("⚠️ SUPABASE_URL / SUPABASE_KEY ayarlanmamış, liderlik tablosu devre dışı.")
            return null
        }
        return try {
            val response: HttpResponse = httpClient.get("$supabaseUrl/rest/v1/quiz_records") {
                header("apikey", supabaseKey)
                header("Authorization", "Bearer $supabaseKey")
                parameter("select", "player_name,streak,achieved_at")
                parameter("order", "streak.desc,achieved_at.asc")
                parameter("limit", "1")
            }
            val body = response.bodyAsText()
            val rows = Json.parseToJsonElement(body).jsonArray
            if (rows.isEmpty()) return null
            val row = rows[0].jsonObject
            QuizRecord(
                playerName = row["player_name"]?.jsonPrimitive?.content ?: "Anonim",
                streak = row["streak"]?.jsonPrimitive?.int ?: 0,
                achievedAt = row["achieved_at"]?.jsonPrimitive?.content ?: ""
            )
        } catch (e: Exception) {
            println("🔥 Supabase liderlik tablosu okuma hatası: ${e.message}")
            null
        }
    }

    suspend fun submitQuizRecordIfBeatsCurrent(playerName: String, streak: Int): QuizRecord {
        val current = fetchTopQuizRecord()
        if (supabaseUrl == null || supabaseKey == null) {
            return current ?: QuizRecord(playerName, streak, "")
        }
        if (current == null || streak > current.streak) {
            val safeName = playerName.trim().take(24).ifBlank { "Anonim" }
            try {
                httpClient.post("$supabaseUrl/rest/v1/quiz_records") {
                    header("apikey", supabaseKey)
                    header("Authorization", "Bearer $supabaseKey")
                    header("Content-Type", "application/json")
                    setBody(Json.encodeToString(QuizRecordSubmission.serializer(), QuizRecordSubmission(safeName, streak)))
                }
            } catch (e: Exception) {
                println("🔥 Supabase liderlik tablosu yazma hatası: ${e.message}")
            }
            return QuizRecord(safeName, streak, "")
        }
        return current
    }

    // 🔍 TANI ENDPOINT'İ: her ülke×rol kombinasyonu için maaşın gerçek mi tahmini
    // mi olduğunu ve varsa Stack Overflow örneklem büyüklüğünü listeler. Kira/gider
    // için de WhereNext kapsamında olup olmadığını gösterir. "Hangi maaş gerçek
    // değil" sorusuna elle bakmak yerine bunu kullan.
    fun fetchDataCoverage(): List<DataCoverageRow> {
        val allRoles = roleMultipliers.keys + nonTechRoleMultipliers.keys
        val allCountries = cityOptionsByCountry.keys

        return allCountries.flatMap { country ->
            allRoles.map { role ->
                val real = realSalaryCache["$country|$role|all"]
                DataCoverageRow(
                    countryCode = country,
                    role = role,
                    isRealSalary = real != null && real.sampleSize >= MIN_SAMPLE_SIZE_FOR_REAL_DATA,
                    salarySampleSize = real?.sampleSize,
                    isRealCost = costOfLivingCache.containsKey(country)
                )
            }
        }.sortedWith(compareBy({ it.countryCode }, { it.role }))
    }

    fun fetchAllCountries(role: String = "Backend Developer", household: String = "single", experience: String = "all"): List<CountryFinancials> {
        val result = mutableListOf<CountryFinancials>()
        val resolvedRole = if (roleMultipliers.containsKey(role) || nonTechRoleMultipliers.containsKey(role)) role else "Backend Developer"
        val resolvedExperience = if (experienceTiers.containsKey(experience)) experience else "all"
        val adj = householdSettings[household] ?: householdSettings.getValue("single")
        val sql = """
            SELECT country_code, country_name_tr, role, gross_annual_usd, tax_wedge_percent,
                   monthly_rent_usd, monthly_expense_usd, salary_source, cost_source, last_updated
            FROM country_financials
            WHERE role = ?
        """.trimIndent()

        try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, resolvedRole)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val countryCode = rs.getString("country_code")
                            val roleForRow = rs.getString("role")
                            val macro = macroDataCache[countryCode]
                            val expMultiplier = experienceFallbackMultipliers[resolvedExperience] ?: 1.0

                            // 📊 KADEMELİ arama: 1) bu deneyim kademesi için GERÇEK medyan var mı?
                            // 2) yoksa "tüm deneyimler" GERÇEK medyanı var mı, varsa deneyim
                            // çarpanıyla ölçekle. 3) o da yoksa eski tahmini rakama deneyim
                            // çarpanını uygula. Her durumda kaynak metninde HANGİSİ olduğu net yazıyor.
                            val realForTier = realSalaryCache["$countryCode|$roleForRow|$resolvedExperience"]
                            val realForAll = realSalaryCache["$countryCode|$roleForRow|all"]

                            val gross: Double
                            val salarySourceFinal: String

                            if (realForTier != null && realForTier.sampleSize >= MIN_SAMPLE_SIZE_FOR_REAL_DATA) {
                                gross = realForTier.medianUsd
                                salarySourceFinal = "Stack Overflow Developer Survey 2025 — GERÇEK medyan, ${experienceTiers[resolvedExperience]} (N=${realForTier.sampleSize} yanıt)"
                            } else if (resolvedExperience != "all" && realForAll != null && realForAll.sampleSize >= MIN_SAMPLE_SIZE_FOR_REAL_DATA) {
                                gross = realForAll.medianUsd * expMultiplier
                                salarySourceFinal = "Stack Overflow Survey — tüm deneyimler GERÇEK medyanı (N=${realForAll.sampleSize}) × deneyim çarpanı (tahmini ayarlama)"
                            } else if (realForAll != null && realForAll.sampleSize >= MIN_SAMPLE_SIZE_FOR_REAL_DATA) {
                                gross = realForAll.medianUsd
                                salarySourceFinal = "Stack Overflow Developer Survey 2025 — GERÇEK medyan (N=${realForAll.sampleSize} yanıt)"
                            } else {
                                gross = rs.getDouble("gross_annual_usd") * expMultiplier
                                salarySourceFinal = rs.getString("salary_source") + if (resolvedExperience != "all") " × deneyim çarpanı (tahmini)" else ""
                            }

                            // 👪 Hane tipi vergi kamasını düşürüyor (aile avantajları), 0'ın
                            // altına inmesin diye alt sınır koyuyoruz.
                            val taxWedge = (rs.getDouble("tax_wedge_percent") + adj.taxWedgeDelta).coerceAtLeast(0.0)

                            // 🏠 Önce GERÇEK WhereNext verisine bakıyoruz. Varsa kira/gideri
                            // onunla değiştiriyoruz (hane tipi çarpanı yine üstüne uygulanıyor).
                            // Yoksa (WhereNext'te kapsam dışı bir ülkeyse) eski tahminî
                            // rakama düşüyoruz — kaynak metninde bu açıkça belirtiliyor.
                            val realCost = costOfLivingCache[countryCode]
                            val baseRent = realCost?.monthlyRentUsd ?: rs.getDouble("monthly_rent_usd")
                            val baseExpense = realCost?.monthlyExpenseUsd ?: rs.getDouble("monthly_expense_usd")

                            // 🏙️ Şehir çarpanları burada UYGULANMIYOR — frontend, ülke ortalaması
                            // baz alınarak gelen "cities" listesindeki çarpanlarla kendi tarafında
                            // anlık hesaplıyor (ekstra network isteği olmadan şehir değiştirebilsin diye).
                            // Burada sadece hane tipi (aile büyüklüğü) çarpanı sunucu tarafında uygulanıyor.
                            val rent = baseRent * adj.rentMultiplier
                            var expense = baseExpense * adj.expenseMultiplier

                            // 🎓 Çocuklu hane tiplerinde okul maliyetini aylık gidere ekliyoruz.
                            var schoolCostNote = ""
                            if (adj.numberOfKids > 0) {
                                val schoolEstimate = estimateAnnualSchoolCostUsd(countryCode)
                                if (schoolEstimate != null) {
                                    val (annualSchoolCost, _) = schoolEstimate
                                    val monthlySchoolCost = (annualSchoolCost * adj.numberOfKids) / 12.0
                                    expense += monthlySchoolCost
                                    schoolCostNote = " + okul ücreti (flagship şehir gerçek/araştırılmış verisinden ülke ortalamasına ölçeklenmiş tahmin)"
                                }
                            }

                            val costSourceFinal = (if (realCost != null) {
                                "WhereNext Cost of Living Index 2026 — GERÇEK (World Bank ICP 2021 verisine dayanıyor)"
                            } else {
                                rs.getString("cost_source")
                            }) + schoolCostNote

                            val netAnnual = gross * (1.0 - taxWedge / 100.0)
                            val monthlyNetRemaining = (netAnnual / 12.0) - rent - expense

                            val carEstimate = estimateAudiA3PriceUsd(countryCode)

                            result.add(
                                CountryFinancials(
                                    countryCode = countryCode,
                                    countryNameTr = rs.getString("country_name_tr"),
                                    role = rs.getString("role"),
                                    grossAnnualUsd = gross,
                                    taxWedgePercent = taxWedge,
                                    netAnnualUsd = netAnnual,
                                    monthlyRentUsd = rent,
                                    monthlyExpenseUsd = expense,
                                    monthlyNetRemainingUsd = monthlyNetRemaining,
                                    salarySource = salarySourceFinal,
                                    costSource = costSourceFinal,
                                    lastUpdated = rs.getString("last_updated"),
                                    cities = cityOptionsByCountry[countryCode] ?: emptyList(),
                                    unemploymentPercent = macro?.unemploymentPercent,
                                    inflationPercent = macro?.inflationPercent,
                                    audiA3PriceUsd = carEstimate?.priceUsd,
                                    audiA3PriceIsReal = carEstimate?.isReal ?: false
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("🔥 fetchAllCountries HATASI: ${e.message}")
        }

        return result.sortedByDescending { it.monthlyNetRemainingUsd }
    }
}