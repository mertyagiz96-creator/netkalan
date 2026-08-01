FROM gradle:8.9-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle jar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# 💡 db ilk açılışta DatabaseClient içinde otomatik oluşturulup
# seed verisiyle dolduruluyor — TransferKolik'in aksine burada dışarıdan
# hazır bir .db dosyası kopyalamaya gerek yok.
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]