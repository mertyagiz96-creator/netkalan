FROM gradle:8.9-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle jar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# 💡 netkalan.db ilk açılışta DatabaseClient içinde otomatik oluşturulup
# seed verisiyle dolduruluyor — TransferKolik'in aksine burada dışarıdan
# hazır bir .db dosyası kopyalamaya gerek yok.
EXPOSE 8080
# 💡 512MB RAM'li free tier'da JVM'in kendi bellek yönetimi konteyner sınırını
# aşıp OS tarafından sessizce öldürülmesin diye açık bir üst sınır (-Xmx)
# koyuyoruz, biraz da OS/JVM overhead'i için pay bırakıyoruz.
CMD ["java", "-Xmx350m", "-jar", "app.jar"]