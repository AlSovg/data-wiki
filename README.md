# Data Wiki

Сервис личной базы знаний: импорт Markdown, полнотекстовый поиск на Lucene (BM25, TF-IDF, гибрид с весами), подсветка совпадений, кэш запросов в Redis, версии документов, шаринг по ссылке, JWT и изоляция данных по пользователю. Клиент — Vue 3 + Vite.

Документация: [System Design](docs/system-design.md) · [алгоритмы скоринга](docs/scoring.md) · [требования](docs/requirements.md) · [OpenAPI](docs/openapi.yaml) · [замер индексации](docs/benchmark.md) · [вопросы автору кейса](docs/questions-for-case-author.md).

## Архитектура

```
браузер → nginx → Spring Boot (REST + Vue SPA) ─┬→ PostgreSQL  документы, версии, пользователи, ссылки
                                                ├→ Redis       кэш поиска (ключ включает user id)
                                                └→ Lucene      индекс на томе, пересобирается из PostgreSQL
```

**Почему Lucene, а не Elasticsearch.** Elasticsearch построен на Lucene; здесь Lucene встроен в приложение напрямую. Для сервиса в одном экземпляре это даёт тот же поиск без отдельного кластера, а главное — выбор скоринга на уровне запроса (`BM25Similarity`, `ClassicSimilarity` = TF-IDF, гибрид с весами), `RussianAnalyzer` и `highlighter`. Ограничение — один экземпляр сервиса; путь масштабирования (вынос индекса в Elasticsearch/OpenSearch без миграции данных) описан в [access-and-scaling.md](docs/access-and-scaling.md).

## Запуск

```bash
cp .env.example .env        # задать POSTGRES_PASSWORD и JWT_SECRET (>= 32 символов); ADMIN_EMAIL — по желанию
docker compose up -d --build
curl localhost:$HTTP_PORT/actuator/health   # {"status":"UP", ...}
```

Интерфейс — `http://localhost:<HTTP_PORT>/`, Swagger UI — `/swagger-ui/index.html`. `HTTP_PORT` берётся из `.env` (по умолчанию 80).

## Импорт данных

Принимаются `.md`/`.markdown` и `.zip`-архивы с ними (до 1000 файлов в архиве). В интерфейсе «Импорт»: «Выбрать файлы» — один или несколько файлов либо архив, «Выбрать папку» — папка целиком. Через API — `POST /api/import`, multipart-поле `files` (можно повторять). Ответ — отчёт по каждому файлу: `created`, `duplicate` (тот же текст уже есть), `rejected` (ошибка разбора или размер), `skipped` (не Markdown). Лимиты: `IMPORT_MAX_FILE_BYTES` (1 МБ на файл), `IMPORT_MAX_REQUEST_SIZE` (50 МБ на запрос). YAML frontmatter (`title`, `tags`, `category`, `author`, `date`) становится метаданными.

## Демо-данные

В `demo/` — шесть Markdown-файлов с тегами и категориями. В интерфейсе: «Импорт» → «Выбрать папку» → `demo/`. Через API:

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

## Шаринг по ссылке

Владелец открывает документ → «Доступ по ссылке» → «Создать ссылку» для просмотра или для редактирования, копирует её и в любой момент отключает. Ссылка вида `/#s/<token>` открывается любым вошедшим пользователем; правка по ссылке на редактирование создаёт новую версию с автором-получателем. В поиск и список получателя расшаренный документ не попадает. Через API:

```bash
curl -s -X PUT -H "Authorization: Bearer $TOKEN" $B/api/documents/<id>/links/VIEW   # или EDIT; {"token": ...}
curl -s -H "Authorization: Bearer $OTHER_TOKEN" $B/api/shared/<token>              # документ + permission
curl -s -X DELETE -H "Authorization: Bearer $TOKEN" $B/api/documents/<id>/links/VIEW
```

## Разработка

Нужны JDK 25, Maven 3.9, Docker (Testcontainers поднимает PostgreSQL и Redis) и Node 22 для клиента.

```bash
mvn verify                       # сборка + все тесты
mvn test -Dtest=IndexingBenchmarkTest   # замер индексации
cd frontend && npm install && npm run dev   # клиент на :5173, /api проксируется на :8080
```

Конфигурация — только переменные окружения (см. `.env.example` и `application.yml`).
