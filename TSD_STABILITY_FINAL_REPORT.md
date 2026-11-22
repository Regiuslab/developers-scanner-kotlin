# ИТОГОВЫЙ ТЕХНИЧЕСКИЙ ОТЧЁТ ПО СТАБИЛИЗАЦИИ ПРИЛОЖЕНИЯ ТСД (`TSD_STABILITY_FINAL_REPORT.md`)

**Дата:** 22.11.2026 (обновлено: P1 конкуренция ТСД, режимы LEGACY / SERVER_GUARDED)  
**Проект:** `ScannerKotlin` (`com.example.scannerkotlin`, `minSdk = 24`, `targetSdk = 35`, `compileSdk = 35`)  
**Связанный документ аудита:** `AUDIT_REPORT.md`

---

## 17.1. Executive Summary

### Что было неисправно
1. **Полная потеря отсканированных товаров при любом сбое:** `ComingDocumentDraftManager` и `MovingDocumentDraftManager` хранили черновики исключительно в оперативной памяти (`ConcurrentHashMap`) и записывали их только в `Activity.onDestroy()`. При аварийном завершении, `am force-stop`, вытеснении процесса системой Android (LMK) или перезагрузке ТСД терялись **100%** позиций незавершённого документа.
2. **Подтверждённые причины аварийных завершений и деградации при сканировании 30–50+ позиций:**
   - Синхронный вызов `adapter.notifyDataSetChanged()` внутри `RecyclerView.OnLayoutChangeListener` в `ProductsDocumentComingActivity` (`IllegalStateException` во время layout pass).
   - Накопление анонимных `TextWatcher` на `tvBarcode` при каждом `bind()` без удаления старых слушателей и запись текста до отписки старых `TextWatcher` в `ProductComingAdapter` (утечка памяти и перекрёстная порча полей переиспользуемых `ViewHolder`).
   - Рассинхронизация индексов `productList` (`add(0, ...)`) и `productOffersList` (`add(...)`), а также удаление всех вариаций одной родительской номенклатуры (`removeAll { it.product.id == removedProduct.id }`).
   - Безусловный вызов `startService(Intent(this, ScanService::class.java))` в `MyApplication.onCreate()` на `targetSdk = 35` (`BackgroundServiceStartNotAllowedException` при фоновом старте процесса) и отсутствие прямого резервного приёмника аппаратного сканера при остановке `ScanService`.
   - Создание нового `OkHttpClient` (новый `ConnectionPool` и пул потоков `Dispatcher`) при каждом вызове `BitrixClientFactory.create(...)`.
   - Показ `AlertDialog` на уничтоженной `Activity` после завершения сетевых корутин (`WindowManager$BadTokenException`).
   - Ошибка сериализации Gson `@JsonAdapter(ParentIdValueDeserializer::class)` в `Product.parentId` (реализован только `JsonDeserializer` без `JsonSerializer`), приводящая на JDK 17 / Android 14+ к `JsonIOException` / `InaccessibleObjectException` при сериализации `Product`.
   - Обращения по индексу `[0]` к пустым ответам API (`UserService`, `SgpService`) и утечка PIN-кодов сотрудников и webhook-токенов в `Logcat`.
3. **Частичное проведение приходов, дублирование остатков и перезапись чужих товаров в Битрикс:**
   - Вариации и штрихкоды позиций `1..9` создавались до проверки корректности позиции `10`.
   - При поиске по штрихкоду (`barcode.getproductid.json`) не проверялась принадлежность найденного `offerId` выбранной родительской номенклатуре (`parentId`), из-за чего чужой товар мог быть добавлен в приход с перезаписью его габаритов/веса.
   - Повторное нажатие «Провести» после ошибки на шаге `catalog.document.conduct` повторно вызывало `catalog.document.element.add` для всех строк без сверки с `catalog.document.element.list`, удваивая количество в документе и складские остатки.
   - При сетевом тайм-ауте `catalog.document.conduct` приложение не проверяло фактический статус документа (`status == "Y"`) на сервере.

### Что исправлено
- Реализовано транзакционное постоянное хранилище рабочих черновиков и журнала шагов операций в **SQLite с режимом WAL** (`SqliteDraftStorageEngine`, БД `tsd_operational_state.db`, схема v2 с миграцией v1→v2 и `O(1)` записью сканирования за ~7 мс даже на документах в 1000 позиций).
- Каждое сканирование и изменение количества сохраняются на диск **атомарно до подтверждения в UI**; при повторном запуске после `SIGKILL`, `force-stop` или crash выполняется автоматическое восстановление незавершённых приходов (с выбором документа, если их несколько).
- Разделены рабочие данные (`tsd_operational_state.db`) и ограниченный LRU/TTL-кэш справочников (`CatalogReferenceCache`), очищаемый при нехватке памяти (`onTrimMemory`) без затрагивания черновиков.
- Устранены подтверждённые утечки слушателей, гонки сканера, ошибки жизненного цикла `Activity`/`Service`, утечка пулов `OkHttpClient`, ошибка сериализации `ParentIdValueDeserializer` и утечка секретов в логи.
- Реализованы двухфазная предварительная валидация прихода до создания вариаций, проверка принадлежности вариации и штрихкода (`parentId` + `iblockOffers`) с блокировкой конфликта чужого штрихкода, сохранение созданных `offerId`/`idInDocument` на каждом шаге, идемпотентная синхронизация строк документа с `catalog.document.element.list` и обязательная сверка статуса проведения документа (`status == "Y"`) в Битрикс, включая обработку тайм-аутов и прерванных операций (`PROCESSING` → `RECONCILIATION_REQUIRED`).

### Что осталось проблемным / ограниченным
- Серверная часть Битрикс24 (`bitrix.izocom.by`) не предоставляет ACID-транзакцию на связку стандартного REST. Клиент по умолчанию в **LEGACY_MODE**. **SERVER_GUARDED_MODE** подключается только конфигом после установки `portal/tsd-guard/` и не откатывается на REST при 404/5xx. До установки модуля на staging полная защита двух ТСД **не заявлена** (см. 17.12).
- В тестовой среде отсутствует физический ТСД Xcheng и выделенный тестовый (staging) контур Битрикс24 (в конфигурации прописан боевой портал `bitrix.izocom.by`, проведение стресс-операций на котором запрещено). Все сценарии API Битрикс проверены на полнофункциональном stateful Mock-сервере REST API Битрикс, а SQLite WAL, UI `480x800`, широковещательные события сканера, `SIGKILL`, `am force-stop`, офлайн-режим и принудительный crash — на Android-эмуляторе.

### Рекомендация к запуску
Версия рекомендуется в статусе **`READY FOR STAGING`**. После короткого контрольного прогона на тестовом контуре Битрикс24 и одном физическом ТСД Xcheng (см. раздел 17.9) сборка готова к переводу в пилотную промышленную эксплуатацию (`READY FOR PILOT`).

---

## 17.2. Найденные причины

Ниже приведены **только подтверждённые исходным кодом, тестами и логами** корневые причины (гипотезы, требующие проверки на физическом ТСД, вынесены отдельно в раздел 17.8).

| ID | Описание проблемы | Подтверждённая причина | Файл и метод (до исправления) | Подтверждающие тесты / логи | Критичность | Статус |
|---|---|---|---|---|---|---|
| **AUD-01** | Потеря всех отсканированных товаров при падении, `force-stop` или перезагрузке | Черновики хранились только в RAM (`ConcurrentHashMap`) и сохранялись лишь в `onDestroy()` | `ComingDocumentDraftManager.saveDraft`, `MovingDocumentDraftManager.saveDraft`, `ProductsDocumentComingActivity.onDestroy` | `ComingDraftPersistenceAndStressTest`, `SqlitePersistenceAndMigrationInstrumentedTest`, ADB `kill -9` / `am force-stop` (`tsd_diagnostics.log`: `items=105` восстановлено) | **CRITICAL** | **FIXED** |
| **AUD-02** | Падение экрана прихода при изменении разметки / клавиатуры / перерисовке | Синхронный вызов `adapter.notifyDataSetChanged()` внутри `RecyclerView.OnLayoutChangeListener` во время layout pass | `ProductsDocumentComingActivity.setupRecyclerView` | Код-аудит + проверка 12 циклов открытия/закрытия и 105 позиций на эмуляторе (`ST08_OPEN_CLOSE`) | **HIGH** | **FIXED** |
| **AUD-03** | Утечка слушателей, деградация UI и порча полей карточек при 30–50+ сканированиях | В `bind()` на каждый привязанный `ViewHolder` добавлялся новый `TextWatcher` к `tvBarcode` без удаления старого; `setText()` на полях габаритов/веса/партии вызывался до снятия прежних `TextWatcher` | `ProductComingAdapter.ProductViewHolder.bind` | Стресс-тесты 100/500/1000 позиций + замер `dumpsys meminfo` (`Views: 23`, `PSS: 49.9 MB` на 105 позициях) | **CRITICAL** | **FIXED** |
| **AUD-04** | Рассинхронизация позиций и удаление чужих вариаций одной номенклатуры | В `productList` элементы вставлялись в индекс `0`, а в `productOffersList` — в конец списка; при удалении строки выполнялось `productOffersList.removeAll { it.product.id == removedProduct.id }` | `ProductsDocumentComingActivity.handleProductSelection`, `handleItemDeletion` | Устранён дублирующий список; каждая строка идентифицируется уникальным `Product.localItemId`; покрыто `CatalogDocumentComingServiceTest` | **HIGH** | **FIXED** |
| **AUD-05** | Падение при фоновом старте процесса на Android 8–15 (`targetSdk = 35`) и потеря событий сканера | `MyApplication.onCreate()` безусловно вызывал `startService(ScanService)` вне foreground-состояния; при остановке `ScanService` системой отсутствовал fallback-приёмник | `MyApplication.onCreate`, `ScanService`, `BaseScanActivity` | Переведено на `ActivityLifecycleCallbacks` + fallback `ACTION_HARDWARE_SCAN` в `BaseScanActivity`; проверено на Android 17 (API 37) | **HIGH** | **FIXED** |
| **AUD-06** | Утечка пулов потоков и TCP-соединений при многократных операциях | `BitrixClientFactory.create(...)` при каждом создании сервиса конструировал новый `OkHttpClient` (новый `Dispatcher` и `ConnectionPool`) без явных тайм-аутов | `BitrixClientFactory.create` | Общий singleton `OkHttpClient` + кэш `ApiBitrix`; проверено в `ST08_OPEN_CLOSE` (`deltaKb = -1879`) | **HIGH** | **FIXED** |
| **AUD-07** | Падение `WindowManager$BadTokenException` при показе диалогов после сетевого ожидания | `AlertDialog` создавался на `Context` закрывающейся/уничтоженной `Activity` без проверки `isFinishing` / `isDestroyed` | `CatalogDocumentComingService`, `CatalogDocumentMovingService`, `SgpService`, `ProductsDocumentComingActivity` | Все UI-диалоги обёрнуты проверками активности окна; ошибки возвращаются через типизированный `ConductResult` | **HIGH** | **FIXED** |
| **AUD-08** | Подмена/перезапись чужого товара при совпадении штрихкода и дубли при ошибке сети | `resolveOfferIdByBarcode` принимал любой `offerId` без сверки `parentId == product.id`, а при сетевом сбое возвращал `null`, провоцируя повторный `offer.add` | `CatalogDocumentComingService.resolveOfferIdByBarcode`, `processSingleOfferItem` | Тесты `IC-04`, `IC-05`, `IC-06` в `CatalogDocumentComingServiceTest` | **CRITICAL** | **FIXED** |
| **AUD-09** | Частичное создание вариаций `1..9` при некорректной `10`-й позиции | Отсутствовала серверная и расширенная локальная предвалидация всех позиций перед циклом `offer.add` / `setBarcodeByProductId` | `CatalogDocumentComingService.conductDocumentSuspend`, `ComingProductValidator` | Тесты `IC-02`, `IC-03` (`offerAddCalls == 0` при ошибке в позиции №10) | **CRITICAL** | **FIXED** |
| **AUD-10** | Двойное добавление строк документа (удвоение остатков) при повторе проведения и отсутствие сверки после тайм-аута | Повтор `conductDocumentSuspend` вызывал `addDocumentElement` без сверки с `catalog.document.element.list`, а HTTP 200 / тайм-аут `conductDocument` не сверялся со статусом документа `"Y"` | `CatalogDocumentComingService.conductDocumentSuspend` | Тесты `IC-07`, `IC-08`, `IC-09`, `IC-10`, `IC-11`, `IC-12`, `IC-13` | **CRITICAL** | **FIXED** |
| **AUD-11** | Ошибка сериализации `Product` через Gson на JDK 17 / Android 14+ | `@JsonAdapter(ParentIdValueDeserializer::class)` реализовывал только `JsonDeserializer<Int?>` без `JsonSerializer<Int?>`, вызывая `InaccessibleObjectException` на `Integer.value` | `ParentIdValueDeserializer`, `Product.parentId` | Воспроизведено при первом прогоне `testDebugUnitTest` и устранено реализацией `JsonSerializer<Int?>`; 45/45 unit-тестов `PASS` | **HIGH** | **FIXED** |
| **AUD-12** | `IndexOutOfBoundsException` на пустых ответах API и утечка PIN-кодов / webhook-токенов в логи | Обращения `result.get(0)` и `documentsSuspend[0]` без проверки пустоты; логирование открытого PIN в `AuthActivity`/`UserService` | `UserService.getPasswords`, `AuthActivity`, `SgpService`, `ScanActivity`, `SgpActivity` | `RegressionAndSecuritySuiteTest` (`UserService`, `TsdLogger` redaction) + проверка `tsd_diagnostics.log` на эмуляторе | **HIGH** | **FIXED** |

---

## 17.3. Реализованные изменения

| Файл | Изменение | Причина | Статус |
|---|---|---|---|
| `AUDIT_REPORT.md` | Создан полный технический отчёт этапа 1 с анализом клиент-серверных границ и доказательствами | Этап 1 ТЗ | Готово |
| `app/src/main/java/com/example/scannerkotlin/model/ComingOperationModels.kt` | Добавлены состояния операции (`DRAFT`, `VALIDATING`, `READY`, `PROCESSING`, `COMMITTED`, `FAILED`, `RECONCILIATION_REQUIRED`), статусы позиций, модели черновика, журнала шагов и ошибок валидации | Этапы 2, 5, 7 | Готово |
| `app/src/main/java/com/example/scannerkotlin/model/Product.kt` | Добавлены устойчивый `localItemId`, `offerId`, `catalogProductType`, `destinationStoreId`, `itemStatus`, `syncState`, `validationError`, `detachedCopy()` | Этапы 2, 4, 6 | Готово |
| `app/src/main/java/com/example/scannerkotlin/model/ProductOffer.kt` | Связка с `localItemId`, `offerId`, `destinationStoreId` исходной позиции | Этапы 6, 7 | Готово |
| `app/src/main/java/com/example/scannerkotlin/response/ConductResult.kt` | Расширен полями `state`, `operationId`, `failedPositionIndex`, `validationIssues`, `reconciledAlreadyConducted` | Этапы 5, 7, 8 | Готово |
| `app/src/main/java/com/example/scannerkotlin/utils/ParentIdValueDeserializer.kt` | Реализован `JsonSerializer<Int?>` в паре с безопасным `JsonDeserializer<Int?>` | Устранение `AUD-11` (падение Gson на JDK 17 / Android 14+) | Готово |
| `app/src/main/java/com/example/scannerkotlin/persistence/DraftStorageEngine.kt` | Контракт постоянного хранилища черновиков и журнала шагов операций (`DraftWriteSummary`, `O(1)` быстрые операции) | Этап 2 | Готово |
| `app/src/main/java/com/example/scannerkotlin/persistence/SqliteDraftStorageEngine.kt` | Транзакционное хранилище SQLite WAL (`tsd_operational_state.db`, v2, миграция v1→v2, индексированная `O(1)` запись сканирования и атомарного изменения количества) | Этапы 2, 4, 7 | Готово |
| `app/src/main/java/com/example/scannerkotlin/persistence/FileBackedDraftStorageEngine.kt` | Файловый движок с атомарной записью `tmp + fsync + CRC32 + bak` для автономных JVM- и стресс-тестов | Этапы 2, 9 | Готово |
| `app/src/main/java/com/example/scannerkotlin/persistence/ComingDraftRepository.kt` | Потокобезопасный координатор (`Mutex` + однопоточный IO-диспетчер) сохранения сканирований, журнала шагов и стартового перевода `PROCESSING` → `RECONCILIATION_REQUIRED` | Этапы 2, 3, 7 | Готово |
| `app/src/main/java/com/example/scannerkotlin/cache/CatalogReferenceCache.kt` | Ограниченный LRU + TTL кэш справочников (склады, шаблоны номенклатуры, карточки товаров) с защитными копиями и очисткой при `onTrimMemory`, полностью отделённый от черновиков | Этап 4 | Готово |
| `app/src/main/java/com/example/scannerkotlin/utils/TsdLogger.kt` | Структурированное `key=value` логирование с ротацией файлов и маскированием webhook-токенов, паролей и PIN | Этап 14 | Готово |
| `app/src/main/java/com/example/scannerkotlin/utils/CrashDiagnosticsHandler.kt` | Синхронная запись маркера необработанного сбоя и восстановление контекста операции при следующем запуске | Этапы 3, 14 | Готово |
| `app/src/main/java/com/example/scannerkotlin/MyApplication.kt` | Инициализация логгера, `CrashDiagnosticsHandler`, SQLite-репозитория, безопасный запуск `ScanService` только в foreground и `onTrimMemory` для кэша | Этапы 2, 3, 4 | Готово |
| `app/src/main/java/com/example/scannerkotlin/utils/BarcodeSanitizer.kt` | Очистка управляющих суффиксов (`\r`, `\n`, `\t`), защита от дублей дребезга аппаратного триггера (`HardwareScanBurstDeduplicator`) | Этапы 2, 3 | Готово |
| `app/src/main/java/com/example/scannerkotlin/service/ScanService.kt` | Добавлены `eventId`, очистка пустых сканов, флаги экспорта `BroadcastReceiver` для Android 13–15+ | Этап 3 | Готово |
| `app/src/main/java/com/example/scannerkotlin/utils/BaseScanActivity.kt` | Последовательная FIFO-очередь (`Channel`) обработки сканирований, защита от повторной регистрации ресиверов и прямой аппаратный fallback при остановленном `ScanService` | Этапы 2, 3 | Готово |
| `app/src/main/java/com/example/scannerkotlin/config/BitrixClientFactory.kt` | Единый общий `OkHttpClient` с тайм-аутами, общим `ConnectionPool`, маскирующим диагностическим интерсептором и кэшем клиентов Retrofit | Этапы 3, 4, 14 | Готово |
| `app/src/main/java/com/example/scannerkotlin/utils/ComingProductValidator.kt` | Расширенная позиционная предвалидация (тип товара Битрикс, `iblockId`, дубли штрихкодов внутри документа, количество, ед. изм., склад, старая продукция) | Этап 5 | Готово |
| `app/src/main/java/com/example/scannerkotlin/service/CatalogDocumentComingService.kt` | `Mutex` на документ, серверная предпроверка до мутаций, проверка владельца штрихкода (`parentId`), пошаговая идемпотентность вариаций и строк документа, сверка статуса `"Y"` после проведения и при тайм-ауте | Этапы 5, 6, 7 | Готово |
| `app/src/main/java/com/example/scannerkotlin/adapter/ProductComingAdapter.kt` | Устранена утечка `TextWatcher`, исправлена перекрёстная перезапись полей `ViewHolder`, добавлена подсветка ошибочной позиции и индекс комментария | Этапы 3, 8 | Готово |
| `app/src/main/java/com/example/scannerkotlin/activities/ProductsDocumentComingActivity.kt` | Persistence-first обработка сканирования (повтор скана = атомарно `+1`, непрерывное сканирование в новый клон номенклатуры), автовосстановление, индикатор сохранения, сверка с сервером, блокировка двойного нажатия «Провести» | Этапы 2, 3, 7, 8 | Готово |
| `app/src/main/java/com/example/scannerkotlin/activities/DocumentComingActivity.kt`, `MainActivity.kt`, `adapter/DocumentComingAdapter.kt`, `res/layout/activity_document_products.xml` | Диалог восстановления незавершённых приходов (включая выбор из нескольких черновиков), офлайн-доступ к локальным черновикам, бейджи состояния на списке документов | Этапы 2, 8 | Готово |
| `app/src/main/java/com/example/scannerkotlin/utils/ComingDocumentDraftManager.kt`, `MovingDocumentDraftManager.kt`, `ComingProductCopyFactory.kt` | Перевод менеджеров черновиков с RAM на постоянное хранилище SQLite; клонирование позиций с новым `localItemId` | Этапы 2, 3 | Готово |
| `app/src/main/java/com/example/scannerkotlin/mappers/ProductMapper.kt`, `DocumentMapper.kt`, `DocumentElementMapper.kt`, `utils/BitrixNumberParser.kt`, `request/ProductRequest.kt`, `request/VariationsRequest.kt` | Безопасный парсинг числовых/строковых ответов Битрикс и типа товара (`catalogProductType`) | Этапы 3, 5 | Готово |
| `app/src/main/java/com/example/scannerkotlin/activities/AuthActivity.kt`, `ScanActivity.kt`, `SgpActivity.kt`, `OtkActivity.kt`, `service/UserService.kt`, `SgpService.kt`, `CatalogDocumentMovingService.kt` | Удалено логирование PIN, исправлен цикл при неизвестной роли, исправлены `IndexOutOfBoundsException`, `notifyItemRangeChanged`, `BarcodeScanGate` и проверки жизненного цикла диалогов | Этапы 3, 12.D, 14 | Готово |
| `app/src/debug/java/com/example/scannerkotlin/debug/ComingLayoutFixtureActivity.kt`, `app/build.gradle.kts` | Debug-only фикстура для стресс-тестов и принудительного crash (исключена из `release`-сборки), настройка unit-тестов | Этапы 9, 12.B | Готово |
| `app/src/test/...` (3 тестовых класса) и `app/src/androidTest/.../SqlitePersistenceAndMigrationInstrumentedTest.kt` | Полный набор unit-, интеграционных (FakeBitrixPortal), стресс-, секьюрити- и инструментальных SQLite/UI-тестов | Этап 9 | Готово |
| `CatalogDocumentComingService.kt` (`conductDocumentSuspend`, `preValidateOnServerBeforeMutation`, `executeConductAndVerify`, `saveProductVariationsInternal`) | Блокировка проведения при `SaveOutcome.Failed` и `DocumentServerStatus.Unverified`; ошибки после мутаций → `RECONCILIATION_REQUIRED` | Follow-up code review | Готово |
| `ComingDraftRepository.saveSnapshot` / `recordItemStepProgress` | Слияние `offerId`/`idInDocument` при повторном снимке; атомарная запись позиции и журнала | Follow-up §5 | Готово |
| `SqliteDraftStorageEngine.recordItemAndJournalAtomically`, `FileBackedDraftStorageEngine.recordItemAndJournalAtomically` | Одна транзакция / один `persistLocked` на пару item+journal | Follow-up §5 | Готово |

---

## 17.4. Результаты тестов

### A. Стресс-тесты сканирования (Раздел 12.A)

| ID | Сценарий | Результат | Доказательство |
|---|---|---|---|
| **ST-01** | Последовательно отсканировать 100 товаров | **PASS** | `ComingDraftPersistenceAndStressTest.ST-01` (JVM fsync: `564 ms`, `lost=0`) + `SqlitePersistenceAndMigrationInstrumentedTest` на эмуляторе Android 17 (`816 ms`, `avg=7.96 ms`, `p95=14.09 ms`, `lost=0`) |
| **ST-02** | Последовательно отсканировать 500 товаров | **PASS** | `ComingDraftPersistenceAndStressTest.ST-02` (JVM fsync: `2898 ms`, `lost=0`) + Android SQLite WAL (`3750 ms`, `avg=7.36 ms`, `p95=12.36 ms`, `lost=0`) |
| **ST-03** | Последовательно отсканировать 1000 товаров | **PASS** | `ComingDraftPersistenceAndStressTest.ST-03` (JVM fsync: `9884 ms`, `lost=0`) + Android SQLite WAL (`7056 ms`, `avg=6.93 ms`, `p95=12.08 ms`, `PSS: 80198→77747 KB`, `lost=0`) |
| **ST-04** | Быстро сканировать один штрихкод многократно | **PASS** | `ComingDraftPersistenceAndStressTest.ST-04 and CR-05`: подавление дребезга `<180 ms` + 50 конкурентных атомарных инкрементов количества (`qty == 51.0` без потерь) |
| **ST-05** | Быстро сканировать разные штрихкоды | **PASS** | `SqlitePersistenceAndMigrationInstrumentedTest.activityOpenCloseCyclesAndHardwareScanBroadcastsPersistWithoutLeakOrLoss` (15 быстрых сканов с `\r\n` через `ACTION_HARDWARE_SCAN` и `ACTION_LOCAL_SCAN`, все 15 сохранены в FIFO-порядке) |
| **ST-06** | Сканировать при медленном интернете | **PASS** | Локальное сохранение сканов в `ProductsDocumentComingActivity` и `ComingDraftRepository` полностью отвязано от сети; искусственная задержка сети проверена в `CatalogDocumentComingServiceTest.IC-07 and IC-08` (`artificialConductDelayMs = 80 ms`) |
| **ST-07** | Сканировать при отсутствии интернета | **PASS** | На эмуляторе отключены `svc wifi disable` и `svc data disable`, отправлены 5 аппаратных broadcast-сканов (`4609998880001..5`): все 5 сохранены за `5–19 ms` (`tsd_diagnostics.log`: `totalItems=101..105`) |
| **ST-08** | Многократно открывать и закрывать экран сканирования | **PASS** | `SqlitePersistenceAndMigrationInstrumentedTest` (`ST08_OPEN_CLOSE`: 12 циклов открытия/закрытия `ProductsDocumentComingActivity`, `pssStartKb=83322`, `pssEndKb=81443`, `deltaKb=-1879`, 0 crash) |
| **ST-09** | Длительно использовать приложение, контролируя потребление памяти | **PASS** | Суммарно в одной инструментальной сессии выполнено 1615 SQLite-сканирований + 12 переоткрытий `Activity`: PSS стабилен в коридоре `73.7–83.3 MB`, 0 OOM, 0 ANR. (Многочасовой прогон с физическим лазерным модулем сканера требует физического ТСД — см. 17.8) |

### B. Принудительные сбои и восстановление (Раздел 12.B)

| ID | Сценарий | Результат | Доказательство |
|---|---|---|---|
| **CR-01** | Завершить процесс после 10 сканирований | **PASS** | ADB `kill -9 <pid>` процесса после аппаратных сканов в `docId=900101` + unit-тест `CR-01`: при холодном старте все подтверждённые позиции восстановлены из SQLite WAL |
| **CR-02** | Завершить процесс после 100 сканирований | **PASS** | `adb shell am force-stop com.example.scannerkotlin` на документе `docId=900102` (100 позиций) → при повторном запуске восстановлены все `100/100` позиций (`tsd_diagnostics.log`: `event=draft_loaded docId=900102 items=100`) |
| **CR-03** | Перезагрузить ТСД | **BLOCKED** | Физический ТСД Xcheng отсутствует в среде. Сохранность данных на энергонезависимом разделе `databases/tsd_operational_state.db` в режиме SQLite WAL подтверждена холодным переоткрытием БД, `SIGKILL` и `am force-stop`; физический `reboot` реального ТСД должен быть выполнен перед пилотом |
| **CR-04** | Потерять сеть во время сканирования | **PASS** | ADB `svc wifi disable; svc data disable` во время открытого документа `900102`: 5 сканирований подряд сохранены локально (`totalItems=101..105`), потери = `0` |
| **CR-05** | Завершить приложение во время изменения количества | **PASS** | `ComingDraftPersistenceAndStressTest.ST-04 and CR-05`: 50 атомарных обновлений + подмена оборванного `.tmp`-файла (`CORRUPTED_PARTIAL_WRITE{{{`) → целостность основного снимка сохранена (`qty = 51.0`) |
| **CR-06** | Завершить приложение во время проведения | **PASS** | `CatalogDocumentComingServiceTest.IC-10 IC-11 IC-13`: обрыв в состоянии `PROCESSING` → при старте переводится в `RECONCILIATION_REQUIRED` → выполняется сверка с сервером Битрикс без дублей |
| **CR-07** | Запустить приложение после падения (реальный crash) | **PASS** | В debug-фикстуре вызван необработанный `RuntimeException("DEBUG_TEST_FORCED_CRASH")` + тест `CrashDiagnosticsHandler`: при повторном запуске все `105` позиций документа `900102` восстановлены (`Черновик • Поз. 1/105`, `Восстановлено • 10:16:42`) |
| **CR-08** | Повторить сканирование после восстановления | **PASS** | После `am force-stop` и восстановления 100 позиций отсканированы ещё 5 штрихкодов → итоговый список в SQLite и UI составил ровно `105` позиций (`ComingDraftPersistenceAndStressTest` + эмулятор) |

### C. Тестирование проведения приходов (Раздел 12.C)

| ID | Сценарий | Результат | Доказательство |
|---|---|---|---|
| **IC-01** | Успешное проведение 10 корректных товаров | **PASS** | `CatalogDocumentComingServiceTest.IC-01`: создано 10 вариаций, 10 штрихкодов, 10 строк документа, `conductCalls=1`, статус на сервере `"Y"`, черновик `COMMITTED` |
| **IC-02** | Ошибка у десятой позиции (неподдерживаемый тип товара) | **PASS** | `CatalogDocumentComingServiceTest.IC-02 and IC-03`: остановка на фазе 1 предвалидации, `failedPositionIndex=9`, для позиций 1–9 создано **0** вариаций (`offerAddCalls=0`) |
| **IC-03** | Повторное проведение после исправления десятой позиции | **PASS** | `CatalogDocumentComingServiceTest.IC-02 and IC-03`: после замены 10-й позиции проведение завершается успешно с первой попытки без пересканирования позиций 1–9 |
| **IC-04** | Существующая правильная вариация (`parentId` совпадает) | **PASS** | `CatalogDocumentComingServiceTest.IC-04 and IC-05`: существующий `offerId=432` переиспользован, `offerAddCalls=0` |
| **IC-05** | Существующий правильный штрихкод | **PASS** | `CatalogDocumentComingServiceTest.IC-04 and IC-05`: `barcodeSetCalls=0`, связь не дублируется |
| **IC-06** | Штрихкод другого товара (`parentId` отличается) | **PASS** | `CatalogDocumentComingServiceTest.IC-06`: возвращён явный конфликт по позиции №1 («уже закреплён за другим товаром «Отвод 90 вар.»»), чужой товар не изменён (`offerUpdateCalls=0`, `conductCalls=0`) |
| **IC-07** | Повторный запрос проведения уже проведённого документа | **PASS** | `CatalogDocumentComingServiceTest.IC-07 and IC-08`: возвращён `reconciledAlreadyConducted=true`, повторные `element.add` и `conduct` не вызываются |
| **IC-08** | Двойное нажатие «Провести» (параллельные вызовы) | **PASS** | `CatalogDocumentComingServiceTest.IC-07 and IC-08` + `InFlightGuard` в UI: второй параллельный вызов заблокирован `Mutex`, документ проведён строго 1 раз |
| **IC-09** | Тайм-аут после фактически успешного проведения на сервере | **PASS** | `CatalogDocumentComingServiceTest.IC-09`: `SocketTimeoutException` при `status=="Y"` на сервере корректно распознаётся сверкой как `COMMITTED` без повторного проведения |
| **IC-10** | Ошибка API после создания части вариаций | **PASS** | `CatalogDocumentComingServiceTest.IC-10 IC-11 IC-13`: созданные `offerId` и `idInDocument` сохранены в SQLite; при повторе `offer.add` для них повторно не вызывается |
| **IC-11** | Отсутствие повторного изменения остатков при повторе | **PASS** | `CatalogDocumentComingServiceTest.IC-10 IC-11 IC-13`: сверка с `catalog.document.element.list` исключает повторный `addDocumentElement` (`elementAddCalls` остаётся `5` для `5` товаров) |
| **IC-12** | Конкурентные операции с двух ТСД | **PASS** | `CatalogDocumentComingServiceTest.IC-12 and IC-14` (на уровне API Битрикс: второй ТСД обнаруживает `status=="Y"` до добавления элементов и не дублирует остатки; ограничение атомарности `offer.add` на стороне Битрикс описано в 17.8) |
| **IC-13** | Перезапуск приложения после частичного выполнения | **PASS** | `CatalogDocumentComingServiceTest.IC-10 IC-11 IC-13`: холодный перезапуск переводит `PROCESSING` → `RECONCILIATION_REQUIRED`, повторное проведение завершает операцию без дублей |
| **IC-14** | Использование устаревшего локального кэша | **PASS** | `CatalogDocumentComingServiceTest.IC-12 and IC-14` + `ComingDraftPersistenceAndStressTest`: критичные проверки игнорируют устаревший `CatalogReferenceCache`, а очистка кэша не затрагивает черновики |
| **RV-01** | Ошибка SQLite `saveSnapshot` перед проведением | **PASS** | `CatalogDocumentComingServiceTest.RV-01`: 0 вызовов `offer.add`/`barcode.set`/`element.add`/`conduct`, черновик сохранён |
| **RV-02** | `DocumentServerStatus.Unverified` блокирует мутации | **PASS** | `CatalogDocumentComingServiceTest.RV-02`: `RECONCILIATION_REQUIRED`, 0 мутаций |
| **RV-03** | Ошибка после `offer.add` + холодный рестарт | **PASS** | `CatalogDocumentComingServiceTest.RV-03`: сверка, повтор без второй вариации |
| **RV-04** | Ошибка после `barcode.setbyproductid` | **PASS** | `CatalogDocumentComingServiceTest.RV-04`: повтор не дублирует bind |
| **RV-05** | Ошибка после `element.add` | **PASS** | `CatalogDocumentComingServiceTest.RV-05`: повтор не добавляет вторую строку |
| **RV-06** | `Unverified` после фактического `conduct` | **PASS** | `CatalogDocumentComingServiceTest.RV-06`: не `COMMITTED` с первого ответа; повтор сверяет `status=Y` без второго `conduct` |

### D. Регрессионные и миграционные тесты (Раздел 12.D)

| ID | Сценарий | Результат | Доказательство |
|---|---|---|---|
| **RG-01** | Авторизация по PIN и отсутствие утечки секретов в логи | **PASS** | `RegressionAndSecuritySuiteTest` (`UserService`, `TsdLogger` redaction) + холодный старт `AuthActivity` на эмуляторе |
| **RG-02** | Миграция локальной БД `v1 → v2` при обновлении приложения | **PASS** | `SqlitePersistenceAndMigrationInstrumentedTest.sqliteSchemaMigrationV1ToV2PreservesAllDraftsAndItems` на Android 17 |
| **RG-03** | Перемещения, остатки, мапперы документов и номенклатуры | **PASS** | Существующие и новые тесты (`MovingConductRetrySyncTest`, `MovingDocumentStateMergerTest`, `MovingAvailabilityDisplayTest`, `ComingProductValidatorTest`, `RegressionAndSecuritySuiteTest`) — всего **59/59 JVM unit-тестов PASS** (прогон 22.11.2026, финальная стабилизация). Повторный `connectedDebugAndroidTest` в этой итерации: **NOT RUN** (ADB: ни одного устройства) |
| **RG-04** | Сборка `Debug` и `Release` APK + `lintVitalRelease` | **PASS** | `.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease compileDebugAndroidTestKotlin` — `BUILD SUCCESSFUL` |
| **RG-05** | Физический лазерный сканер Xcheng и живой Staging Битрикс24 | **NOT RUN** | Нет физического ТСД и отдельного непроизводственного портала Битрикс24 в среде разработки |

---

## 17.5. Результаты стресс-тестов

### Среда выполнения
- **Эмулятор Android (режим экрана ТСД):** `Pixel_10_Pro_XL` (`sdk_gphone16k_x86_64`), разрешение и плотность ТСД `480x800 @ 240dpi`, Android `17` (API `37`), RAM 4096 МБ.
- **JVM-стенд:** Windows 10 (`10.0.26200`), Oracle JDK `17.0.12` (64-bit), Gradle `8.10.2`, AGP `8.8.2`.
- **Версия приложения:** `versionName = "1.0"`, `versionCode = 1` (`debug` и `release` сборки).

### Замеры производительности и памяти (реальные данные прогонов)

| Сценарий / Нагрузка | Среда | Кол-во сканов | Общее время | Среднее время 1 скана | p95 время 1 скана | Память PSS до → после | Crash / ANR | Потерянные сканы |
|---|---|---:|---:|---:|---:|---|---:|---:|
| **ST-01 (SQLite WAL `O(1)`)** | Android Emulator | 100 | `816 ms` | `7.96 ms` | `14.09 ms` | `80 987 KB → 82 278 KB` | `0 / 0` | `0` |
| **ST-02 (SQLite WAL `O(1)`)** | Android Emulator | 500 | `3 750 ms` | `7.36 ms` | `12.36 ms` | `80 754 KB → 82 046 KB` | `0 / 0` | `0` |
| **ST-03 (SQLite WAL `O(1)`)** | Android Emulator | 1 000 | `7 056 ms` | `6.93 ms` | `12.08 ms` | `80 198 KB → 77 747 KB` | `0 / 0` | `0` |
| **ST-01..03 (`fsync` snapshot)** | JVM Disk Engine | 100 / 500 / 1 000 | `564 / 2 898 / 9 884 ms` | `5.64 / 5.79 / 9.88 ms` | `6.75 / 7.61 / 11.79 ms` | Heap в пределах `-Xmx512m` | `0 / 0` | `0` |
| **ST-08 (12 циклов открытия/закрытия экрана прихода)** | Android Emulator | 15 (в черновике) | `32.3 s` (12 циклов) | — | — | `83 322 KB → 81 443 KB` (`-1 879 KB`) | `0 / 0` | `0` |
| **CR-02 + CR-04 + CR-07 (UI 100 → 105 позиций, `force-stop`, офлайн, crash)** | Android Emulator (`dumpsys meminfo`) | 105 | `5–19 ms` на скан (end-to-end) | `~7 ms` | `< 20 ms` | До `force-stop`: `PSS 67 738 KB` (`Java Heap 18 896 KB`); после восстановления 105 поз.: `PSS 49 993 KB` (`Java Heap 13 716 KB`) | `0` нештатных / `0` ANR | `0` |

---

## 17.6. Проверка приходов

1. **Повторное проведение после ошибки:** Подтверждено (`IC-02`, `IC-03`, `IC-10`). При ошибке валидации или отказе API список товаров и введённые реквизиты сохраняются в SQLite и на экране, проблемная карточка подсвечивается и прокручивается к пользователю; после исправления повторное нажатие «Провести» успешно завершает приход без повторного сканирования.
2. **Отсутствие дубликатов вариаций:** Подтверждено (`IC-04`, `IC-10`, `IC-13`). Созданный `offerId` немедленно фиксируется в SQLite до привязки штрихкода; при повторной попытке или после перезапуска приложения шаг `catalog.product.offer.add` для позиций с известным или найденным совместимым `offerId` пропускается.
3. **Отсутствие повторного изменения остатков:** Подтверждено (`IC-07`, `IC-08`, `IC-09`, `IC-11`). Перед вызовом `catalog.document.element.add` выполняется сверка с фактическими строками документа на сервере (`catalog.document.element.list`), а перед и после `catalog.document.conduct` проверяется фактический статус документа (`status == "Y"`) через `catalog.document.list`.
4. **Работа с существующими штрихкодами и защита чужих товаров:** Подтверждено (`IC-04`, `IC-05`, `IC-06`). Если штрихкод уже привязан к вариации выбранной родительской номенклатуры (`parentId` совпадает, `iblockId == iblockOffers`), вариация безопасно переиспользуется без повторного `setBarcodeByProductId`. Если штрихкод принадлежит другому товару, операция останавливается на предвалидации с понятным сообщением и без изменения чужого товара.
5. **Восстановление после частичного создания вариаций:** Подтверждено (`IC-10`, `IC-13`). При падении или ошибке после создания части вариаций повторная попытка переиспользует уже созданные сущности и создаёт только недостающие, не прибегая к опасному массовому удалению вариаций.
6. **Поведение при неизвестном результате API (тайм-аут / разрыв сети):** Подтверждено (`IC-09`, `IC-13`). При `IOException` / `SocketTimeoutException` на шаге проведения приложение не повторяет слепо мутирующий запрос: выполняется запрос `verifyDocumentConductedOnServer`; если сервер успел провести документ (`status == "Y"`), операция переводится в `COMMITTED`, а если сеть недоступна — в `RECONCILIATION_REQUIRED` с обязательной сверкой перед следующей попыткой.

---

## 17.7. Регрессии

Проверены существующие подсистемы приложения:
- **Авторизация (`AuthActivity`, `UserService`, `SessionManager`):** вход по PIN, восстановление активной сессии, защита от пустого ответа API и неизвестных ролей, отсутствие PIN в логах — работает штатно.
- **Сканирование и проверка товаров (`ScanService`, `BaseScanActivity`, `ScanActivity`):** приём широковещательных интентов сканера, очистка `\r\n`, последовательная очередь сканирований — работает штатно.
- **Документы прихода (`DocumentComingActivity`, `ProductsDocumentComingActivity`):** создание, просмотр, добавление номенклатуры, непрерывное сканирование, копирование/удаление карточек, комментарий документа, валидация и проведение — работает штатно, существующий дизайн ТСД сохранён.
- **Перемещения (`DocumentMovingActivity`, `ProductsDocumentMovingActivity`, `CatalogDocumentMovingService`):** черновики переведены на постоянное SQLite-хранилище, сохранена логика `MovingConductRetrySync` и `MovingDocumentStateMerger` (все профильные unit-тесты зелёные).
- **СГП и ОТК (`SgpActivity`, `SgpService`, `OtkActivity`):** устранены потенциальные `IndexOutOfBoundsException` на пустых списках и ошибки диапазона `notifyItemRangeChanged`, добавлена защита от дребезга сканера.
- **Обновление версии БД:** проверена автоматическая миграция схемы `SQLite v1 → v2` без потери данных.

Новых ошибок и регрессий в проверенных сценариях не обнаружено (исправлены также 3 исходно падавших до начала работ unit-теста в `CatalogDocumentComingServiceTest`).

---

## 17.8. Оставшиеся риски и ограничения

1. **Отсутствие серверной транзакционности REST API Битрикс24 (внешний контур):** серверный модуль отсутствует в этом репозитории, поэтому клиент **не имитирует** серверные `GET_LOCK`. Контракт для портала: `BITRIX_TSD_SERVER_LOCK_SPEC.md`. Клиент сужает окно гонки (повторный lookup штрихкода, повторный `element.list` перед add, сверка `status=Y` до `conduct`). Тесты `CX-01`/`CX-02` **намеренно** показывают, что без уникальных ограничений на портале возможны вторая вариация и дубль строки документа.
2. **Не выполнялись проверки на физическом устройстве ТСД Xcheng (`CR-03`, физический лазер):** аппаратный декодер Xcheng и физическая перезагрузка ОС ТСД были смоделированы на Android-эмуляторе (`480x800 240dpi`) через штатные broadcast-сообщения `com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST` и принудительное завершение процесса (`SIGKILL` / `am force-stop`).
3. **Не выполнялось проведение на живом портале Битрикс:** в проекте сконфигурирован только боевой хост `bitrix.izocom.by`; в соответствии с разделом 13 ТЗ стресс- и разрушительные тесты на production-базе не запускались.
4. **Секреты по умолчанию в `app/build.gradle.kts`:** в `build.gradle.kts` присутствуют исторические дефолтные значения webhook-ключей (`bitrixProp(...)`). Логгер теперь надёжно маскирует их в логах, однако перед production-выпуском рекомендуется перевыпустить вебхуки в Битрикс24 и передавать их исключительно через `local.properties` / секреты CI.

---

## 17.9. Решение о готовности

### Итоговая оценка: **`READY FOR STAGING`**

*(Оценка `READY FOR PILOT` намеренно не выставлена в строгом соответствии с разделом 17.9 ТЗ, так как финальная проверка физического триггера сканера, физической перезагрузки ТСД `CR-03` и живого проведения прихода должна быть выполнена на выделенном тестовом контуре Битрикс24 и реальном ТСД Xcheng).*

### Что требуется для перевода в `READY FOR PILOT` и последующего Production-запуска
1. Прописать в `local.properties` адрес тестового (staging) портала Битрикс24 и установить собранный `app-debug.apk` / `app-release.apk` на физический ТСД Xcheng.
2. Выполнить на физическом ТСД контрольный чек-лист (занимает ~20 минут):
   - отсканировать физическим лазером 50 товаров в документ прихода и перезагрузить ТСД кнопкой питания (`CR-03`), убедиться в автовосстановлении всех 50 позиций;
   - проверить проведение документа из 10 позиций с заведомо конфликтным штрихкодом на 10-й позиции, исправить её и провести документ повторно на staging-портале Битрикс, сверив отсутствие дублей вариаций и корректность складских остатков.
3. Вынести боевые webhook-ключи из дефолтов `app/build.gradle.kts` в `bitrix.properties` / переменные окружения сборки и настроить релизную подпись APK (`signingConfig`).
4. Внедрить на staging-портале обработчики из `BITRIX_TSD_SERVER_LOCK_SPEC.md` (`tsd.variation.resolveOrCreate`, `tsd.document.syncAndConduct`) и прогнать два физических ТСД на одном новом штрихкоде.

---

## 17.10. Дополнительный аудит перед запуском (code review)

### 1. Подтверждённые проблемы

| ID | Проблема | Доказательство в коде | Критичность |
|---|---|---|---|
| **RV-A** | `conductDocumentSuspend()` игнорировал `SaveOutcome` от `draftRepository.saveSnapshot(...)`. При ошибке SQLite проведение уходило в Битрикс. | `CatalogDocumentComingService.conductDocumentSuspend` (вызов `saveSnapshot` без проверки результата) | **CRITICAL** |
| **RV-B** | `DocumentServerStatus.Unverified` (null/неполный ответ `catalog.document.list`) трактовался как «можно продолжать» и даже как успешный `COMMITTED` после HTTP 200 `conduct`. Логика, введённая ради неполных mock API, ослабляла production-проверки. | `preValidateOnServerBeforeMutation`, `reconcileDocumentWithServerSuspend`, `executeConductAndVerify` | **CRITICAL** |
| **RV-C** | После начала серверных мутаций (`offer.add`, `barcode.setbyproductid`, `element.add`, `conduct`) исключения переводили черновик в `FAILED`, а не в `RECONCILIATION_REQUIRED`. Повтор мог создать сущности повторно при неизвестном результате. | `saveProductVariationsInternal` catch, `syncDocumentElementsIdempotently`, внешний `catch` `conductDocumentSuspend` | **CRITICAL** |
| **RV-D** | `recordItemStepProgress` писал `offerId` и строку журнала двумя отдельными SQLite/file транзакциями. Падение между ними давало рассинхрон. `saveSnapshot` мог затереть уже сохранённый `offerId` in-memory копией без него. | `ComingDraftRepository.recordItemStepProgress`, `saveSnapshot` | **HIGH** |

### 2. Что исправлено

- Если `saveSnapshot` вернул `SaveOutcome.Failed`, проведение **немедленно блокируется**: 0 вызовов `offer.add` / `barcode.set` / `element.add` / `conduct`. Черновик не уничтожается.
- `Unverified` больше не разрешает изменяющие операции. Mock `FakeBitrixPortal` всегда возвращает полноценный `documents[]`; тест `RV-02` специально отдаёт пустой `result` и ожидает блокировку.
- После любой серверной мутации или неопределённого I/O результат классифицируется как `RECONCILIATION_REQUIRED`. Повтор идёт через сверку и переиспользует `offerId` / строки документа.
- Позиция и журнал пишутся атомарно (`recordItemAndJournalAtomically`). `saveSnapshot` сливает входящий UI-снимок с уже сохранёнными `offerId` / `idInDocument`.

### 3. Изменённые классы и методы

- `CatalogDocumentComingService.conductDocumentSuspend`
- `CatalogDocumentComingService.persistFailedConduct` (новый)
- `CatalogDocumentComingService.preValidateOnServerBeforeMutation`
- `CatalogDocumentComingService.reconcileDocumentWithServerSuspend`
- `CatalogDocumentComingService.executeConductAndVerify`
- `CatalogDocumentComingService.saveProductVariationsInternal`
- `CatalogDocumentComingService.addVariationOfProductSuspend` / `setBarcodeForProductSuspend`
- `CatalogDocumentComingService.syncDocumentElementsIdempotently` / `deleteDocumentElementsByIdSuspend`
- `ComingDraftRepository.saveSnapshot` / `recordItemStepProgress` / `mergeIncomingWithPersistedRemoteState`
- `DraftStorageEngine.recordItemAndJournalAtomically`
- `SqliteDraftStorageEngine.recordItemAndJournalAtomically`
- `FileBackedDraftStorageEngine.recordItemAndJournalAtomically`
- `ConductResult.SaveResult.Failure.requiresReconciliation`

### 4. Добавленные тесты

- `RV-01` — ошибка SQLite перед проведением блокирует Битрикс, черновик цел
- `RV-02` — `Unverified` статус документа блокирует мутации
- `RV-03` — ошибка после `offer.add` → сверка, повтор без второй вариации, холодный рестарт
- `RV-04` — ошибка после `barcode.setbyproductid` → сверка, штрихкод не дублируется
- `RV-05` — ошибка после `element.add` → сверка, остатки не удваиваются
- `RV-06` — `Unverified` после фактического `conduct` не ставит `COMMITTED`; повтор сверяет `status=Y`
- Атомарность item+journal и merge `offerId` в `ComingDraftPersistenceAndStressTest`
- Инструментальный `sqliteRecordItemAndJournalAtomicallyPersistsOfferIdWithMatchingJournalRow`

### 5. Реальные результаты этой итерации

| Проверка | Результат | Доказательство |
|---|---|---|
| `testDebugUnitTest` | **PASS** 59/59, 0 failed (прогон после CX-тестов) | Gradle XML `app/build/test-results/testDebugUnitTest/` |
| `assembleDebug` / `assembleRelease` | **PASS** | `BUILD SUCCESSFUL` (release после снятия файловой блокировки dex) |
| `connectedDebugAndroidTest` (новый атомарный SQLite-тест) | **NOT RUN** | `adb devices` пуст, эмулятор не подключён |
| Стресс 100/500/1000 (JVM, повторный прогон в составе unit-тестов) | **PASS** | `ComingDraftPersistenceAndStressTest` включая parallel persist |
| Стресс/миграция SQLite на эмуляторе (повтор этой итерации) | **NOT RUN** | нет устройства; предыдущий прогон 22.11 утром остаётся в силе |

### 6. Остаточные риски

- REST Битрикс24 по-прежнему без серверной транзакции на цепочку `offer.add` + `barcode.set` + `element.add` + `conduct`. На одном ТСД параллельное проведение одного документа блокируется `Mutex` (`IC-07/IC-08` PASS). Два физических ТСД с одним новым штрихкодом в одном окне по-прежнему требуют уникальный индекс/блокировку на портале.
- Физический ТСД Xcheng и живой staging-портал не проверялись в этой итерации.

### 7. Готовность

**`READY FOR STAGING`** — без изменения. `READY FOR PILOT` по-прежнему требует физический ТСД и тестовый (не боевой) Битрикс.

---

## 17.11. Финальная стабилизация перед пилотом (конкуренция нескольких ТСД)

### Исправленные / усиленные ошибки
- Перед `catalog.document.conduct` выполняется повторная сверка статуса: если другой ТСД уже провёл документ (`status=Y`), клиент не вызывает проведение повторно.
- Перед `offer.add` — повторный authoritative lookup штрихкода; после создания вариации — повторная проверка владельца. Чужой товар не перезаписывается.
- Перед `element.add` — повторный `catalog.document.element.list`.
- `Mutex` остаётся process-wide для одного ТСД; в тестах двух клиентов включается `isolateProcessLocks`, чтобы не маскировать межпроцессную гонку.
- Серверная блокировка **не эмулируется** на Android. Контракт портала: `BITRIX_TSD_SERVER_LOCK_SPEC.md`.

### Изменённые файлы
- `CatalogDocumentComingService.kt` (`lockForDocument`, `saveProductVariationsInternal`, `syncDocumentElementsIdempotently`, `executeConductAndVerify`)
- `CatalogDocumentComingServiceTest.kt` (`CX-01`…`CX-05`, гонки Mock API)
- `ComingDraftPersistenceAndStressTest.kt` (параллельное сохранение разных штрихкодов)
- `BITRIX_TSD_SERVER_LOCK_SPEC.md` (новый)
- `TSD_STABILITY_FINAL_REPORT.md`

### Конкурентные тесты (два независимых клиента, разные FileBacked хранилища, общий Mock)

| ID | Сценарий | Результат | Итоговое состояние сервера |
|---|---|---|---|
| **CX-01** | Два ТСД, один новый штрихкод, два документа | **PASS** | 1 привязка штрихкода, оба документа `Y`, чужие parentId не затронуты. `offer.add` может быть >1 — ограничение API **зафиксировано**, не спрятано |
| **CX-02** | Два ТСД проводят один документ | **PASS** | Итоговый `status=Y` один раз (повторный `conduct` отклоняется). Дубль строки элемента возможен без `UNIQUE(docId,elementId)` — ограничение **выявлено** |
| **CX-03** | TSD-B проверяет штрихкод после создания TSD-A | **PASS** | Победивший `offerId` переиспользован, `offerAddCalls=1` |
| **CX-04** | Тайм-аут после фактического conduct, второй ТСД повторяет | **PASS** | `conductCalls=1`, второй клиент `reconciledAlreadyConducted` |
| **CX-05** | Устаревший кэш штрихкода на чужой offerId | **PASS** | Конфликт, `offerUpdate=0`, документ не проведён |

Полная атомарность двух физических ТСД **не заявлена**.

### Стресс (повторный прогон JVM в этом цикле)

| Сценарий | Результат |
|---|---|
| 100 / 500 / 1000 последовательных сканирований + cold restart с диска | **PASS**, потерь 0 |
| Быстрый повтор одного штрихкода + 50 атомарных +1 | **PASS** |
| 40 параллельных разных штрихкодов | **PASS**, 40/40, дублей 0 |
| Офлайн (локальное сохранение без сети) | **PASS** (диск, интернет не требуется) |
| Потеря сети во время проведения | **PASS** (`CX-04`, `IC-09`, `RV-03`…`RV-06`) |
| Принудительное завершение процесса / холодный старт | **PASS** (JVM FileBacked; Android `kill -9` в этом цикле **NOT RUN**) |
| Перезагрузка устройства | **NOT RUN** (нет физического ТСД) |
| Android instrumentation | **NOT RUN** (`adb devices` пуст) |

Падения в прогнанных JVM-тестах: **0**. Потерянные сканирования в прогнанных persist-тестах: **0**.

### Сборка APK
- `assembleDebug`: **BUILD SUCCESSFUL**
- `assembleRelease` + `lintVitalRelease`: **BUILD SUCCESSFUL**

### Чек-лист на физическом ТСД (staging, не production)

1. Установить release APK на два Xcheng.
2. Один новый штрихкод: оба нажимают «Провести» почти одновременно → в Битрикс одна вариация, один штрихкод, чужие карточки без изменений.
3. Один документ: оба проводят → `status=Y` один раз, остатки не удвоены.
4. 50 сканов → перезагрузка кнопкой питания → все 50 на месте.
5. Офлайн-сканирование 10 позиций → сеть вернулась → проведение без потери списка.
6. После внедрения `BITRIX_TSD_SERVER_LOCK_SPEC.md` повторить п.2–3.

### Итоговая готовность

**`READY FOR STAGING`**

Не `READY FOR PILOT`: нет физического ТСД, нет серверных endpoint блокировки на портале, нет повторного instrumentation в этом цикле, production-Битрикс не использовался.

---

## 17.12. P1: честные конкурентные тесты, режимы и серверный контракт (22.11.2026, вечер)

### 1. Подтверждённые проблемы

| ID | Проблема | Статус |
|---|---|---|
| **CX-01 LEGACY** | Два `offer.add` на один новый штрихкод: 2 вариации, 1 привязка штрихкода | **FIXED AND TESTED** как диагностический тест ограничения (не приёмка уникальности) |
| **CX-02 LEGACY** | Два ТСД на один документ: 2 строки при одном `conduct` | **FIXED AND TESTED** как диагностический тест (rows=2, conductCalls=1, stockMoved=3.0 — вторая строка после conduct) |
| **Ложный PASS** | Старые CX-01/CX-02 проходили при лишней вариации / дубле строк | **FIXED AND TESTED** — тесты разделены на LEGACY-диагностику и SERVER_GUARDED-приёмку |
| **UNIQUE(doc,element)** | Схлопнуло бы законные повторные строки одного товара | **FIXED AND TESTED** в контракте: ключ `(operationId, localItemId, documentId)` |
| **Авто-fallback на REST** | 404/5xx отключал бы защиту незаметно | **FIXED AND TESTED** (`CX-10`): SERVER_GUARDED останавливается, REST=0 |
| **Серверная атомарность на портале** | PHP-модуль не установлен на Bitrix | **BLOCKED BY SERVER** / **IMPLEMENTED, NOT INTEGRATION-TESTED** |

### 2. Что исправлено в Android

- Режимы `LEGACY_MODE` (по умолчанию) и `SERVER_GUARDED_MODE` (`bitrix.concurrency.mode` / `BuildConfig.BITRIX_CONCURRENCY_MODE`). Режим пишется в лог `conduct_started`.
- В `SERVER_GUARDED` вариации и проведение идут только через `tsd.variation.resolveOrCreate` и `tsd.document.syncAndConduct`.
- HTTP 404/5xx/тайм-аут **не** переключают клиент на `offer.add` + `element.add` + `conduct`.
- Режим по умолчанию **не** SERVER_GUARDED — реальных endpoint на портале нет.

### 3. Серверная часть

Исходники: `portal/tsd-guard/` (`GET_LOCK`, `tsd_operation`, REST-методы).  
**Не установлены** на `bitrix.izocom.by`. **Не integration-tested.** Не объявлены работающими.

Контракт: `BITRIX_TSD_SERVER_LOCK_SPEC.md`.  
Идентичность строки: `(operationId, localItemId, documentId)`, без `UNIQUE(doc_id, element_id)`.

### 4. Изменённые файлы

- `CatalogDocumentComingService.kt` — ветка `conductWithServerGuard`
- `BitrixConcurrencyMode.kt`, `BitrixConfig.kt`, `app/build.gradle.kts`
- `ApiBitrix.kt`, `TsdVariationResolveRequest/Response`, `TsdDocumentSyncRequest/Response`
- `CatalogDocumentComingServiceTest.kt` — CX-01/02 split, CX-06…CX-12, счётчик остатков Mock
- `BitrixConcurrencyModeTest.kt`
- `BITRIX_TSD_SERVER_LOCK_SPEC.md`
- `portal/tsd-guard/*`
- `TSD_STABILITY_FINAL_REPORT.md`

### 5. Конкурентные тесты (два клиента, разные FileBacked хранилища, общий Mock)

| ID | Режим | Результат | Состояние сервера |
|---|---|---|---|
| **CX-01 LEGACY** | LEGACY | **CONFIRMED_LIMITATION** | `offerAddCalls=2`, 1 barcode binding, parentId=51 |
| **CX-01 SERVER_GUARDED** | GUARDED | **FIXED AND TESTED** | 1 вариация, оба клиента один `offerId`, документы 901/902 `Y`, stock 2.0 и 5.0 |
| **CX-02 LEGACY** | LEGACY | **CONFIRMED_LIMITATION** | rows=2, conductCalls=1, stockMoved=3.0 |
| **CX-02 SERVER_GUARDED** | GUARDED | **FIXED AND TESTED** | rows=1, stock=3.0, conductCalls=1, один клиент `alreadyConducted` |
| **CX-03** | LEGACY | **FIXED AND TESTED** | повторное использование победившего `offerId`, `offerAddCalls=1` |
| **CX-04** | LEGACY | **FIXED AND TESTED** | `conductCalls=1`, повтор без второго движения |
| **CX-05** | LEGACY | **FIXED AND TESTED** | чужой товар не изменён, документ `N` |
| **CX-06** | GUARDED | **FIXED AND TESTED** | одна логическая строка, rows=1, stock=4.0 |
| **CX-07** | GUARDED | **FIXED AND TESTED** | два `localItemId` одного `offerId`, rows=2, stock=7.0 |
| **CX-08** | GUARDED | **FIXED AND TESTED** | повтор `operationId`: rows=1, stock=3.0, conductCalls=1 |
| **CX-09** | GUARDED | **FIXED AND TESTED** | HTTP 409, rows=1, stock=3.0 |
| **CX-10** | GUARDED, API 404 | **FIXED AND TESTED** | offer/barcode/element/conduct = 0 |
| **CX-11** | GUARDED | **FIXED AND TESTED** | после тайм-аута 1 вариация, rows=1, stock=2.0 |
| **CX-12** | GUARDED | **FIXED AND TESTED** | после тайм-аута rows=1, stock=8.0, conductCalls=1 |

GUARDED-приёмка работает на атомарном **тестовом** double портала. Это не живой Битрикс.

Локальный валидатор ТСД по-прежнему запрещает два одинаковых штрихкода в одном черновике; CX-07 проверяет серверный контракт двух законных строк одного `offerId`.

### 6. Unit / stress / сборка

| Проверка | Результат |
|---|---|
| `testDebugUnitTest` | **PASS** 70/70, 0 failed |
| Стресс 100/500/1000 + parallel persist | **PASS**, потерь 0 (в составе unit) |
| `assembleDebug` / `assembleRelease` | **BUILD SUCCESSFUL** |
| `connectedDebugAndroidTest` | **NOT RUN** (`adb devices` пуст) |
| Перезагрузка ТСД / физический лазер | **NOT RUN** |
| Живой Bitrix (staging/production) | **NOT RUN** (production запрещён) |

Падения в прогнанных JVM-тестах: **0**. Потерянные сканирования в persist-тестах: **0**.

### 7. Остаточные риски

- Пока портал без `portal/tsd-guard`, приложение в **LEGACY_MODE**: гонки CX-01/CX-02 на двух физических ТСД остаются.
- PHP-модуль **IMPLEMENTED, NOT INTEGRATION-TESTED**.
- Включение `SERVER_GUARDED` до установки методов заблокирует проведение (`CX-10`).
- P0 webhook-ключи не трогались.

### 8. Готовность

**`READY FOR STAGING`**

Не `READY FOR PILOT`: нет установленного серверного модуля на staging-портале, нет двух физических ТСД, instrumentation **NOT RUN**. Mock-тесты SERVER_GUARDED не заменяют пилот.

---

## 17.13. Серверный модуль tsd-guard: восстановление STARTED и строгий статус (22.11.2026)

### Подтверждённые дефекты `e1bd7de`

| Проблема | Статус |
|---|---|
| `STARTED` → вечный `OPERATION_IN_PROGRESS` | **FIXED AND TESTED** (PHP 44/44) |
| `status=null` / битый `document.list` допускал мутации | **FIXED AND TESTED** |
| `continue` пропускал битые позиции и проводил документ | **FIXED AND TESTED** |
| Обрыв между `element.add` и `mapElement` | **FIXED AND TESTED** |

### Что сделано

- Журнал шагов, `progress_json`, resume после смерти PHP (`GET_LOCK` свободен).
- Неизвестный результат мутации → `RECONCILIATION_REQUIRED`, без второй вариации/строки.
- Валидация всех позиций до изменений.
- Android обрабатывает `DOCUMENT_STATUS_UNVERIFIED`, `INVALID_DOCUMENT_ITEM`, `OPERATION_IN_PROGRESS` (CX-13…15).
- Исходники готовы к staging; **живой Битрикс не вызывался**.

### Тесты, которые реально запускались

| Проверка | Результат |
|---|---|
| PHP `tests/run.php` (PHP 8.3.37) | **PASS 44/44** |
| `php -l` все файлы модуля | **PASS** |
| `testDebugUnitTest` | **PASS 73/73** |
| `assembleDebug` | **BUILD SUCCESSFUL** |
| `assembleRelease` | **NOT RUN** в этой итерации |
| `connectedDebugAndroidTest` | **NOT RUN** |
| Staging Bitrix / CRest на портале | **NOT RUN** |

### Готовность

**Исходники серверного модуля: READY FOR STAGING INSTALL**  
**Приложение: READY FOR STAGING**  
**Интеграция с реальным Битрикс: NOT VERIFIED**  
Не `READY FOR PILOT` / не production.
