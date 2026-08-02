import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.Serializable

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
    val inflationPercent: Double? = null     // 🌍 World Bank — GERÇEK, resmi, canlı veri
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
data class HouseholdInfo(val key: String, val labelTr: String)

@Serializable
data class DataCoverageRow(
    val countryCode: String,
    val role: String,
    val isRealSalary: Boolean,
    val salarySampleSize: Int?,
    val isRealCost: Boolean
)

object DatabaseClient {

    // 🔄 Bu sayıyı, seed verisinin YAPISINI değiştiren her değişiklikte (yeni rol,
    // yeni ülke, yeni kolon vb.) elle 1 artırıyoruz. Uygulama açılışta bu sayıyı
    // veritabanındaki kayıtlı değerle karşılaştırıyor; uyuşmazsa netkalan.db'yi
    // ELLE SİLMEYE GEREK KALMADAN kendi kendine sıfırlayıp yeniden seed ediyor.
    private const val SCHEMA_VERSION = 3

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
        "US" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("New York", 1.6, 1.3), CityOption("Austin", 0.8, 0.9)),
        "DE" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Münih", 1.4, 1.15), CityOption("Berlin", 1.1, 1.0)),
        "GB" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Londra", 1.7, 1.3), CityOption("Manchester", 0.7, 0.85)),
        "NL" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Amsterdam", 1.3, 1.15), CityOption("Rotterdam", 0.9, 0.95)),
        "TR" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("İstanbul", 1.3, 1.15), CityOption("Ankara", 0.85, 0.9)),
        "IN" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Bangalore", 1.3, 1.1), CityOption("Pune", 0.9, 0.9)),
        "BR" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("São Paulo", 1.3, 1.1), CityOption("Florianópolis", 0.9, 0.9)),
        "CA" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Toronto", 1.4, 1.15), CityOption("Calgary", 0.85, 0.9)),
        "PL" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Varşova", 1.2, 1.1), CityOption("Wrocław", 0.85, 0.9)),
        "AU" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Sidney", 1.3, 1.15), CityOption("Adelaide", 0.8, 0.9)),
        "CH" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Zürih", 1.3, 1.15), CityOption("Cenevre", 1.25, 1.1)),
        "FR" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Paris", 1.5, 1.2), CityOption("Lyon", 0.85, 0.9)),
        "JP" to listOf(CityOption("Ülke Ortalaması", 1.0, 1.0), CityOption("Tokyo", 1.3, 1.15), CityOption("Osaka", 0.85, 0.9))
    )

    // 👪 Hane tipi ayarları — vergi kaması deltası (yüzde puan, OECD Taxing Wages'in
    // "single" vs "one-earner married" tablolarındaki genel farktan yaklaşık alınmıştır)
    // + OECD'nin resmi "modified equivalence scale" ile gider çarpanı (1 yetişkin=1,
    // +yetişkin=0.5, +çocuk=0.3) + kira için kabaca oda sayısı ihtiyacı çarpanı.
    private val householdSettings = linkedMapOf(
        "single" to HouseholdAdjustment("Bekar", taxWedgeDelta = 0.0, expenseMultiplier = 1.0, rentMultiplier = 1.0),
        "married_no_kids" to HouseholdAdjustment("Evli, Çocuksuz", taxWedgeDelta = -3.0, expenseMultiplier = 1.5, rentMultiplier = 1.3),
        "married_1kid" to HouseholdAdjustment("Evli, 1 Çocuklu", taxWedgeDelta = -5.5, expenseMultiplier = 1.8, rentMultiplier = 1.45),
        "married_2kids" to HouseholdAdjustment("Evli, 2 Çocuklu", taxWedgeDelta = -8.0, expenseMultiplier = 2.1, rentMultiplier = 1.6)
    )

    private data class HouseholdAdjustment(
        val labelTr: String, val taxWedgeDelta: Double, val expenseMultiplier: Double, val rentMultiplier: Double
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
        "Security Professional" to 0.794,
        "Engineering Manager" to 1.132
    )

    // 💰 Ülke bazlı KABA ortalama yıllık ücret tahmini (USD) — OECD Average Wage
    // ve genel bilgiye dayalı, resmi/kesin değil. Tech-olmayan mesleklerin
    // ölçekleneceği baz nokta bu (Backend Developer maaşı değil).
    private val nationalAvgWageUsd = mapOf(
        "US" to 65000.0, "DE" to 58000.0, "GB" to 48000.0, "NL" to 64000.0,
        "TR" to 15000.0, "IN" to 3000.0, "BR" to 10000.0, "CA" to 59000.0,
        "PL" to 24000.0, "AU" to 62000.0, "CH" to 72000.0, "FR" to 40000.0, "JP" to 35000.0
    )

    // 👔 Tech-olmayan meslekler için, dünya genelinde tipik olarak ulusal ortalama
    // ücrete göre kaç kat kazandıklarına dair KABA uluslararası genel bilgi.
    // Bu, tech rol çarpanlarından (Stack Overflow kaynaklı) daha az güvenilir —
    // ülkeye özel resmi veri değil, genel gözlem/tahmin.
    private val nonTechRoleMultipliers = linkedMapOf(
        "Doktor" to 2.5,
        "Hemşire" to 1.1,
        "Öğretmen" to 0.9,
        "Avukat" to 1.8,
        "Muhasebeci / Finans Uzmanı" to 1.2,
        "Pazarlama Yöneticisi" to 1.4,
        "İnşaat Mühendisi" to 1.15,
        "Mimar" to 1.2,
        "Perakende / Hizmet Çalışanı" to 0.65
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
        "Perakende / Hizmet Çalışanı" to "Retail / Service Worker"
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
                val avgWage = nationalAvgWageUsd[row.code] ?: row.gross * 0.5
                for ((role, multiplier) in nonTechRoleMultipliers) {
                    val scaledGross = avgWage * multiplier
                    val salarySrc = "Ülke ortalama ücreti × genel meslek oranı (KABA TAHMİN, ülkeye özel veri değil)"
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

    fun fetchAllHouseholds(): List<HouseholdInfo> {
        return householdSettings.map { (key, adj) -> HouseholdInfo(key, adj.labelTr) }
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
                val real = realSalaryCache["$country|$role"]
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

    fun fetchAllCountries(role: String = "Backend Developer", household: String = "single"): List<CountryFinancials> {
        val result = mutableListOf<CountryFinancials>()
        val resolvedRole = if (roleMultipliers.containsKey(role) || nonTechRoleMultipliers.containsKey(role)) role else "Backend Developer"
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

                            // 📊 Önce GERÇEK Stack Overflow verisine bakıyoruz. Yeterli örneklem
                            // varsa (N >= 10) onu kullanıyoruz, yoksa eski tahmini/ölçeklenmiş
                            // rakama düşüyoruz — ama kaynak metninde hangisi olduğunu net söylüyoruz.
                            val real = realSalaryCache["$countryCode|$roleForRow"]
                            val useReal = real != null && real.sampleSize >= MIN_SAMPLE_SIZE_FOR_REAL_DATA

                            val gross = if (useReal) real!!.medianUsd else rs.getDouble("gross_annual_usd")
                            val salarySourceFinal = if (useReal) {
                                "Stack Overflow Developer Survey 2025 — GERÇEK medyan (N=${real!!.sampleSize} yanıt)"
                            } else {
                                rs.getString("salary_source")
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
                            val costSourceFinal = if (realCost != null) {
                                "WhereNext Cost of Living Index 2026 — GERÇEK (World Bank ICP 2021 verisine dayanıyor)"
                            } else {
                                rs.getString("cost_source")
                            }

                            // 🏙️ Şehir çarpanları burada UYGULANMIYOR — frontend, ülke ortalaması
                            // baz alınarak gelen "cities" listesindeki çarpanlarla kendi tarafında
                            // anlık hesaplıyor (ekstra network isteği olmadan şehir değiştirebilsin diye).
                            // Burada sadece hane tipi (aile büyüklüğü) çarpanı sunucu tarafında uygulanıyor.
                            val rent = baseRent * adj.rentMultiplier
                            val expense = baseExpense * adj.expenseMultiplier

                            val netAnnual = gross * (1.0 - taxWedge / 100.0)
                            val monthlyNetRemaining = (netAnnual / 12.0) - rent - expense

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
                                    inflationPercent = macro?.inflationPercent
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