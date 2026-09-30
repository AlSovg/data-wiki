# Data Wiki

Сервис личной базы знаний: импорт Markdown, полнотекстовый поиск на Lucene (BM25, TF-IDF, гибрид с весами), подсветка совпадений, кэш запросов в Redis, версии документов, JWT и изоляция данных по пользователю. Клиент — Vue 3 + Vite.

Документация: [System Design](docs/system-design.md) · [алгоритмы скоринга](docs/scoring.md) · [требования](docs/requirements.md) · [OpenAPI](docs/openapi.yaml) · [замер индексации](docs/benchmark.md) · [вопросы автору кейса](docs/questions-for-case-author.md).

## Запуск

```bash
cp .env.example .env        # задать POSTGRES_PASSWORD и JWT_SECRET (>= 32 символов); ADMIN_EMAIL — по желанию
docker compose up -d --build
curl localhost:$HTTP_PORT/actuator/health   # {"status":"UP", ...}
```

Интерфейс — `http://localhost:<HTTP_PORT>/`, Swagger UI — `/swagger-ui/index.html`. `HTTP_PORT` берётся из `.env` (по умолчанию 80).

## Демо-данные

В `demo/` — шесть Markdown-файлов с тегами и категориями. В интерфейсе: «Импорт» → «Папка» → `demo/`. Через API:

```bash
B=http://localhost:8088
curl -s -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"password123"}' $B/api/auth/register
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"password123"}' $B/api/auth/login | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

curl -s -H "Authorization: Bearer $TOKEN" $(for f in demo/*.md; do printf -- '-F files=@%s ' "$f"; done) $B/api/import

curl -s -G -H "Authorization: Bearer $TOKEN" --data-urlencode 'q=ранжирование' -d method=bm25 $B/api/search
curl -s -G -H "Authorization: Bearer $TOKEN" --data-urlencode 'q=индекс кэш' -d method=hybrid -d weights=bm25:0.7,tfidf:0.3 -d tags=redis $B/api/search
curl -s -H "Authorization: Bearer $TOKEN" $B/api/stats
```

Индексация фоновая: документ появляется в поиске примерно через секунду после импорта (`INDEX_POLL_MS`).

## Разработка

Нужны JDK 25, Maven 3.9, Docker (Testcontainers поднимает PostgreSQL и Redis) и Node 22 для клиента.

```bash
mvn verify                       # сборка + все тесты
mvn test -Dtest=IndexingBenchmarkTest   # замер индексации
cd frontend && npm install && npm run dev   # клиент на :5173, /api проксируется на :8080
```

Конфигурация — только переменные окружения (см. `.env.example` и `application.yml`).
