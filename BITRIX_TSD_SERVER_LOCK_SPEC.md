# Минимальное ТЗ: серверная защита конкурентных операций ТСД в Битрикс24

**Статус серверного модуля в этом репозитории:** исходники лежат в `portal/tsd-guard/`.  
Они **не установлены** на портал и **не integration-tested**. Android **не имитирует** `GET_LOCK`. Пока обработчики не развёрнуты, рабочий режим клиента — `LEGACY_MODE`.

## 1. Зачем

Стандартный REST Битрикс (`catalog.product.offer.add` + `barcode.setbyproductid.json` + `catalog.document.element.add` + `catalog.document.conduct`) не даёт одной транзакции и не принимает `operationId`.

Локальный `Mutex` в Android защищает только один процесс. Два физических ТСД могут:

- создать две вариации на один новый штрихкод;
- дважды добавить строку одного документа;
- дважды изменить остатки, даже если `conduct` вызван один раз (две строки × одно проведение).

Клиент в `LEGACY_MODE` сужает окно гонки (повторный lookup, сверка `status=Y`). Это **не заменяет** серверную атомарность.

## 2. Режимы клиента

Режим задаётся **только конфигурацией**, не ответом HTTP.

| Режим | Конфиг | Поведение | Гарантия нескольких ТСД |
|---|---|---|---|
| **LEGACY_MODE** | `bitrix.concurrency.mode=LEGACY` (по умолчанию) | Стандартный REST. Текущее поведение. | Нет. Гонки фиксируются тестами как ограничение. |
| **SERVER_GUARDED_MODE** | `bitrix.concurrency.mode=SERVER_GUARDED` | Только `tsd.variation.resolveOrCreate` и `tsd.document.syncAndConduct`. | Да, после установки модуля на портал. |

Правила `SERVER_GUARDED_MODE`:

1. Включать только после появления реальных endpoint на staging.
2. HTTP 404, 5xx, тайм-аут **не** переключают клиент на стандартный REST.
3. Изменяющие операции останавливаются с понятным сообщением до восстановления связи или сверки (`RECONCILIATION_REQUIRED`).
4. Режим пишется в диагностический лог (`concurrencyMode=SERVER_GUARDED_MODE`).

Нельзя заявлять полную защиту, пока приложение работает в `LEGACY_MODE`.

## 3. Инварианты

1. Один штрихкод → не более одной вариации.
2. Штрихкод чужого `parentId` не перезаписывается.
3. Один `documentId` проводится не более одного раза.
4. Повтор с тем же `operationId` и тем же содержимым не создаёт новые сущности и не двигает остатки.
5. Тот же `operationId` с **другим** содержимым после `COMMITTED` → `409 OPERATION_PAYLOAD_CONFLICT`.
6. Тайм-аут клиента после фактического успеха на сервере сверяется, а не повторяет мутацию.
7. Законные повторы одного `offerId` в документе (разные партии / разные `localItemId`) **не** схлопываются.

## 4. Идентичность строки документа

**Нельзя** вводить `UNIQUE(doc_id, element_id)`.

В складском документе Битрикс один и тот же товар (`element_id` / `offerId`) может законно встречаться несколько раз: разные партии, разные количества, повторное добавление после удаления, две независимые позиции одного SKU.

### Логическая позиция

Устойчивый ключ **одного действия одного ТСД**:

```
(operationId, localItemId, documentId)
```

- `operationId` — UUID черновика на устройстве, живёт между повторами и после crash.
- `localItemId` — UUID конкретной карточки в черновике. Копия позиции получает новый `localItemId`.
- `documentId` — ID документа Битрикс.

Сервер хранит сопоставление этого ключа с реальным `catalog.document.element.id`.

### Что считается повтором, а что — новой операцией

| Сценарий | Ключ | Ожидание |
|---|---|---|
| Повтор того же запроса / тайм-аут / retry | тот же `(operationId, localItemId, documentId)` | Вернуть существующую строку, не добавлять |
| Пользователь скопировал карточку | новый `localItemId`, тот же `operationId` | Новая законная строка, даже с тем же `offerId` |
| Две партии одного товара | разные `localItemId` (+ `batchNumber`) | Две строки |
| Удалили и добавили снова | новый `localItemId` (или тот же после удаления mapping) | Новая строка либо обновление, если ключ жив |
| Изменили количество до `COMMITTED` | тот же ключ, новый `quantity` | `UPDATE` строки, не conflict |
| Изменили количество **после** `COMMITTED` | тот же `operationId`, другой payload | `409 OPERATION_PAYLOAD_CONFLICT` |
| Два ТСД, один документ, разные `operationId`/`localItemId` | разные ключи | Сериализация `GET_LOCK(tsd_doc_{id})`: первый синхронизирует свои строки и проводит; второй видит `status=Y` и **не** добавляет свои строки |

Два устройства с разными локальными идентификаторами одного товара **не объединяются** по `offerId`. Защита от двойного прихода на один документ — блокировка документа и запрет изменений после `status=Y`, а не unique по товару.

## 5. Рекомендуемые ограничения БД портала

```sql
UNIQUE (barcode)  -- или эквивалент в b_catalog_store_barcode

CREATE TABLE tsd_operation (
  op_key       VARCHAR(128) PRIMARY KEY, -- 'V:{operationId}:{localItemId}' или 'D:{operationId}:{documentId}'
  operation_id VARCHAR(64) NOT NULL,
  kind         ENUM('VARIATION','DOCUMENT') NOT NULL,
  document_id  INT NULL,
  payload_hash VARCHAR(128) NOT NULL,
  state        ENUM('STARTED','COMMITTED','FAILED') NOT NULL,
  result_json  MEDIUMTEXT NULL,
  created_at   DATETIME,
  updated_at   DATETIME
);

CREATE TABLE tsd_document_item (
  operation_id     VARCHAR(64) NOT NULL,
  local_item_id    VARCHAR(64) NOT NULL,
  document_id      INT NOT NULL,
  bitrix_element_id INT NOT NULL,
  offer_id         INT NOT NULL,
  PRIMARY KEY (operation_id, local_item_id, document_id)
);
```

Блокировки MySQL на время обработчика:

- `GET_LOCK(CONCAT('tsd_barcode_', :barcode), 10)`
- `GET_LOCK(CONCAT('tsd_doc_', :documentId), 10)`

Всегда `RELEASE_LOCK` в `finally`.

## 6. Endpoint

Базовый путь: те же inbound webhook `/rest/{user}/{token}/`, методы REST:

- `tsd.variation.resolveOrCreate`
- `tsd.document.syncAndConduct`

Авторизация: тот же webhook, что использует ТСД. Все методы `POST application/json`.

Исходники для установки: `portal/tsd-guard/`.

### 6.1. `tsd.variation.resolveOrCreate`

```json
{
  "operationId": "uuid-from-android-draft",
  "parentProductId": 51,
  "barcode": "4600000000123",
  "localItemId": "uuid-of-card",
  "offerFields": {
    "iblockId": 15,
    "name": "Труба 63",
    "measure": 1
  }
}
```

Поведение:

1. Проверить запрос.
2. `GET_LOCK` по штрихкоду.
3. Если `operationId+localItemId` уже `COMMITTED` с тем же payload — вернуть сохранённый `offerId`.
4. Если тот же ключ `COMMITTED` с другим payload — **409** `OPERATION_PAYLOAD_CONFLICT`.
5. Если штрихкод у вариации с `parentId == parentProductId` — вернуть её.
6. Если штрихкод у другого `parentId` — **409** `BARCODE_FOREIGN_CONFLICT`, без `offer.update`.
7. Иначе создать вариацию, привязать штрихкод, сохранить результат.
8. `RELEASE_LOCK` в `finally`.

Успех `200`:

```json
{
  "result": {
    "offerId": 432,
    "barcode": "4600000000123",
    "created": false,
    "operationId": "uuid-from-android-draft"
  }
}
```

### 6.2. `tsd.document.syncAndConduct`

```json
{
  "operationId": "uuid-from-android-draft",
  "documentId": 812,
  "deletedRemoteElementIds": [],
  "items": [
    {
      "localItemId": "uuid-of-card",
      "offerId": 432,
      "quantity": 3,
      "storeTo": 1,
      "batchNumber": null
    }
  ]
}
```

Поведение:

1. `GET_LOCK` по `documentId`.
2. Если `operationId+documentId` уже `COMMITTED` с тем же payload — вернуть сохранённый результат, без `element.add` и без повторного `conduct`.
3. Если `COMMITTED` с другим payload — **409** `OPERATION_PAYLOAD_CONFLICT`.
4. Если документ уже `status=Y` — вернуть `alreadyConducted=true`, **без** добавления строк этого запроса и без повторного `conduct`.
5. Удалить `deletedRemoteElementIds`, если документ ещё черновик.
6. Для каждой позиции найти строку по `(operationId, localItemId, documentId)`: обновить количество или добавить новую. Не матчить только по `offerId`.
7. Вызвать проведение. Успех только при повторно прочитанном `status=Y`.
8. Зафиксировать `operationId = COMMITTED` и mapping строк.
9. `RELEASE_LOCK` в `finally`.

Успех:

```json
{
  "result": {
    "documentId": 812,
    "status": "Y",
    "alreadyConducted": false,
    "elementIds": [8801],
    "items": [
      { "localItemId": "uuid-of-card", "offerId": 432, "elementId": 8801, "quantity": 3 }
    ],
    "operationId": "uuid-from-android-draft"
  }
}
```

Ошибки:

| HTTP | code | Когда |
|---|---|---|
| 409 | `BARCODE_FOREIGN_CONFLICT` | штрихкод чужого товара |
| 409 | `OPERATION_PAYLOAD_CONFLICT` | тот же `operationId` с другим содержимым после commit |
| 422 | `PARENT_TYPE_UNSUPPORTED` | номенклатура не допускает вариации |
| 423 | `LOCK_TIMEOUT` | не удалось взять блокировку |
| 409 | `OPERATION_IN_PROGRESS` | тот же ключ ещё выполняется |
| 404 | `NOT_FOUND` | метод не установлен (клиент в GUARDED останавливается) |

## 7. Что делает Android-клиент

| Режим | Вариации | Строки и проведение |
|---|---|---|
| LEGACY | `offer.add` + `barcode.setbyproductid` | `element.add` + `catalog.document.conduct` |
| SERVER_GUARDED | `tsd.variation.resolveOrCreate` | `tsd.document.syncAndConduct` |

Локальные механизмы сохраняются в обоих режимах: SQLite-черновик, `operationId`, журнал шагов, process `Mutex`, `RECONCILIATION_REQUIRED`.

## 8. Проверка на staging-портале

Включать `SERVER_GUARDED` только на staging, не на `bitrix.izocom.by` production.

- Два REST-клиента одновременно `resolveOrCreate` с одним новым штрихкодом → один `offerId`.
- Повтор `syncAndConduct` с тем же `operationId` после тайм-аута → тот же результат, остатки не удваиваются.
- Два `localItemId` одного `offerId` → две строки.
- Конфликт чужого штрихкода → 409, чужая карточка без изменений.
- Выключить модуль при `SERVER_GUARDED` → клиент останавливается, стандартный REST не вызывается.
