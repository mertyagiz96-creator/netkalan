# NetKalan

Aynı meslek için farklı ülkelerde net kalan geliri karşılaştıran araç.
İlk modül: **Senior Backend Developer**, 10 ülke.

## Çalıştırma (IntelliJ IDEA)
1. Bu klasörü IntelliJ'de "Open" ile aç (Gradle projesi olarak otomatik tanınır).
2. `Application.kt` içindeki `main()` fonksiyonunu çalıştır.
3. Tarayıcıda `http://localhost:8080` aç.
4. İlk çalıştırmada `netkalan.db` otomatik oluşur ve 10 ülkelik başlangıç verisiyle
   (seed data) doldurulur — elle bir şey yapmana gerek yok.

## Render.com'a deploy
TransferKolik ile aynı akış: repo'yu GitHub'a it, Render'da "New Web Service",
bu repoyu seç, Dockerfile otomatik algılanır.

## ⚠️ Veri durumu — ÖNEMLİ
`DatabaseClient.kt` içindeki `seedRows` listesi **başlangıç/tahmini veridir**.
Kaynaklar: Stack Overflow Developer Survey 2025 (maaş), OECD Taxing Wages
2025/2026 (vergi kaması — sadece OECD üyesi ülkeler için resmi), genel piyasa
gözlemi (kira/gider). Bir sonraki adımda bu satırları tek tek, kaynak linkiyle
birlikte doğrulayıp güncelleyeceğiz. Kira/gider verisi için WhereNext'in
(getwherenext.com) ücretsiz CC BY 4.0 API'sini entegre etmek sıradaki mantıklı adım.

## Sıradaki adımlar (öneri sırası)
1. `seedRows` verisini ülke ülke gerçek kaynaklarla doğrulamak
2. WhereNext API entegrasyonu (kira + yaşam maliyeti canlı çekilsin)
3. Ayda bir otomatik güncelleme için Render Cron Job / GitHub Actions kurulumu
4. Rol seçimi ekleme (şu an sabit: Senior Backend Developer)
5. TransferKolik'teki tema anahtarı (yeşil/gün batımı/gece/açık) sistemini taşımak