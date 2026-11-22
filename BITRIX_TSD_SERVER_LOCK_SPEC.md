# Минимальное ТЗ: серверная защита конкурентных операций ТСД в Битрикс24

**Статус:** серверный модуль **отсутствует** в репозитории `Regiuslab/developers-scanner-kotlin`.  
Android-клиент **не имитирует** серверные блокировки. Ниже — контракт, который нужно реализовать на портале.

## 1. Зачем

Стандартный REST Битрикс (`catalog.product.offer.add` + `barcode.setbyproductid.json` + `catalog.document.element.add` + `catalog.document.conduct`) не даёт одной транзакции и не принимает `operationId`.

Локальный `Mutex` в Android защищает только один процесс. Два физических ТСД могут:

- создать две вариации на один новый штрихкод;
- дважды добавить строку одного документа;
- дважды вызвать проведение, если оба увидели `status=N`.

Клиент уже умеет переиспользовать найденную вариацию, не трогать чужой товар и сверять `status=Y`. Это **не заменяет** серверную атомарность.

## 2. Инварианты

1. Один штрихкод → не более одной вариации.
2. Штрихкод чужого `parentId` не перезаписывается.
3. Один `documentId` проводится не более одного раза.
4. Повтор с тем же `operationId` не создаёт новые сущности и не двигает остатки.
5. Тайм-аут клиента после фактического успеха на сервере должен сверяться, а не повторять мутацию.

## 3. Рекомендуемые ограничения БД портала

```sql
-- уникальность штрихкода (или эквивалент в b_catalog_store_barcode / инфоблоке)
UNIQUE (barcode)

-- идемпотентность клиентской операции
CREATE TABLE tsd_operation (
  operation_id VARCHAR(64) PRIMARY KEY,
  document_id  INT NOT NULL,
  state        ENUM('STARTED','COMMITTED','FAILED') NOT NULL,
  created_at   DATETIME,
  updated_at   DATETIME
);

-- опционально: не более одной строки документа на вариацию
UNIQUE (doc_id, element_id)
```

Блокировки MySQL на время обработчика:

- `GET_LOCK(CONCAT('tsd_barcode_', :barcode), 10)`
- `GET_LOCK(CONCAT('tsd_doc_', :documentId), 10)`

Всегда `RELEASE_LOCK` в `finally`.

## 4. Endpoint

Базовый путь (пример): `/local/api/tsd/`  
Авторизация: тот же inbound webhook / токен REST, что использует ТСД.  
Все методы `POST application/json`.

### 4.1. `tsd.variation.resolveOrCreate`

Назначение: атомарно найти или создать вариацию и привязать штрихкод.

Запрос:

```json
{
  "operationId": "uuid-from-android-draft",
  "parentProductId": 51,
  "barcode": "4600000000123",
  "offerFields": {
    "iblockId": 15,
    "name": "Труба 63",
    "measure": 1
  }
}
```

Поведение:

1. `GET_LOCK` по штрихкоду.
2. Если штрихкод уже у вариации с `parentId == parentProductId` — вернуть её, ничего не создавая.
3. Если штрихкод у другого `parentId` — **409** `BARCODE_FOREIGN_CONFLICT`, без `offer.update`.
4. Иначе создать вариацию, привязать штрихкод, записать шаг `operationId`.
5. Повтор того же `operationId` возвращает ранее созданный `offerId`.

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

Ошибки:

| HTTP | code | Когда |
|---|---|---|
| 409 | `BARCODE_FOREIGN_CONFLICT` | штрихкод чужого товара |
| 422 | `PARENT_TYPE_UNSUPPORTED` | номенклатура не допускает вариации |
| 423 | `LOCK_TIMEOUT` | не удалось взять блокировку |
| 409 | `OPERATION_IN_PROGRESS` | тот же `operationId` ещё выполняется |

### 4.2. `tsd.document.syncAndConduct`

Назначение: идемпотентно синхронизировать строки и провести документ.

Запрос:

```json
{
  "operationId": "uuid-from-android-draft",
  "documentId": 812,
  "items": [
    { "offerId": 432, "quantity": 3, "storeTo": 1, "localItemId": "..." }
  ]
}
```

Поведение:

1. `GET_LOCK` по `documentId`.
2. Если документ уже `status=Y` — вернуть `alreadyConducted=true`, **без** `element.add` и без повторного `conduct`.
3. Если `operationId` уже `COMMITTED` — то же самое.
4. Сверить/добавить недостающие строки (`UNIQUE(doc_id, element_id)`).
5. Вызвать `catalog.document.conduct`.
6. Повторно прочитать `status`. Успех только при `Y`.
7. Пометить `operationId = COMMITTED`.

Успех:

```json
{
  "result": {
    "documentId": 812,
    "status": "Y",
    "alreadyConducted": false,
    "elementIds": [8801],
    "operationId": "uuid-from-android-draft"
  }
}
```

## 5. Что клиент сделает после появления API

1. Вызвать `tsd.variation.resolveOrCreate` вместо цепочки `offer.add` + `barcode.setbyproductid`.
2. Вызвать `tsd.document.syncAndConduct` вместо `element.add` + `conduct`.
3. Сохранить текущий fallback на стандартный REST, если методы недоступны (404) — с явной пометкой в логе `SERVER_LOCK_UNAVAILABLE`.

До внедрения этих методов **нельзя** заявлять полную защиту при работе двух физических ТСД.

## 6. Проверка на staging-портале

- Два REST-клиента одновременно `resolveOrCreate` с одним новым штрихкодом → один `offerId`.
- Повтор `syncAndConduct` с тем же `operationId` после тайм-аута → `alreadyConducted=true`, остатки не удваиваются.
- Конфликт чужого штрихкода → 409, чужая карточка без изменений.
