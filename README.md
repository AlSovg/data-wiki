# Data Wiki

Сервис личной базы знаний: импорт Markdown, полнотекстовый поиск на Lucene (BM25, TF-IDF, гибрид), кэш в Redis, версии документов. Документация — в `docs/`.

## Запуск

```bash
cp .env.example .env        # задать POSTGRES_PASSWORD и JWT_SECRET
docker compose up -d --build
curl localhost/actuator/health   # {"status":"UP"}
```

Swagger UI: `http://localhost/swagger-ui/index.html`. Порт nginx — `HTTP_PORT` в `.env`.

## Разработка

Нужны JDK 25, Maven 3.9 и Docker (Testcontainers поднимает PostgreSQL и Redis).

```bash
mvn verify                  # сборка + тесты
```

Конфигурация — только переменные окружения (см. `.env.example` и `application.yml`).
