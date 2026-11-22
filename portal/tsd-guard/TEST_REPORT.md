# Отчёт по серверному модулю tsd-guard

Дата: 22.11.2026  
Коммит-база: `e067c0b` + allowlist `tsd_staging_safety` (ручной перенос).

Живой Битрикс **не вызывался**. Успешная интеграция с порталом **не заявлена**.

## 1. Найденные проблемы и причины

| ID | Проблема | Причина в `e1bd7de` |
|---|---|---|
| P1 | `STARTED` зависает навсегда | При `state=STARTED` сразу `OPERATION_IN_PROGRESS`, даже если PHP-процесс уже умер и `GET_LOCK` свободен |
| P2 | Проведение при неизвестном статусе | `documentStatus()` возвращал `null` при пустом/битом ответе, блокировка была только на `'Y'` |
| P3 | Частичное проведение | `continue` при пустом `localItemId` / нулевом `offerId` |
| P4 | Дубль строки | `element.add` затем `mapElement`; обрыв между ними терял соответствие |

## 2. Исправления

- Журнал шагов (`tsd_operation_step`) + `step` / `progress_json` / `offer_id`.
- Resume `STARTED` только после захвата блокировки: сверка штрихкода/offerId/строк.
- Неизвестный результат мутации → `RECONCILIATION_REQUIRED`, без второй сущности.
- Документ: нет `documents[]`, `status=null`, HTTP 500, таймаут → `DOCUMENT_STATUS_UNVERIFIED`, 0 мутаций.
- Полная валидация позиций до изменений; ошибка → `INVALID_DOCUMENT_ITEM` с индексом.
- Строка восстанавливается только по `(operationId, localItemId, documentId)` или сохранённому `elementId`, не по одному `offerId`.

## 3. Изменённые файлы

- `portal/tsd-guard/lib/*` (движки, gateway, persistence, REST-адаптер)
- `portal/tsd-guard/install.sql`, `install_upgrade.sql`, `include.php`, `README.md`
- `portal/tsd-guard/tests/*`
- Android: `CatalogDocumentComingService.kt` (коды ошибок), `CatalogDocumentComingServiceTest.kt` (CX-13…15)

## 4. Тесты PHP

Команда: `php portal/tsd-guard/tests/run.php` (PHP 8.3.37).

**Результат: 50 passed, 0 failed.**  
`php -l` по всем файлам модуля: **No syntax errors**.

Покрыто: идемпотентность вариаций, resume STARTED, crash после offer.add/barcode/element.add, payload conflict, in-progress lock, null/пустой/500/timeout статуса, **status=X**, **нет docType**, **docType=M**, **пустой список складов**, **нет documentElements**, **documentElements не массив**, 10 позиций, ошибка 10-й, дубль localItemId, два `offerId` в разных партиях, 2/5/10 клиентов, 100 повторов, два ТСД на один документ.

## 5. Не проверено

- Установка на staging-портал
- Наличие `CRest` на конкретном коробочном Битрикс
- Реальные `GET_LOCK` под нагрузкой MySQL портала
- Права webhook на складские методы
- Android instrumentation / физический ТСД
- Production `bitrix.izocom.by`

## 6. Установка и откат

См. `portal/tsd-guard/README.md`.
