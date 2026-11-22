# Технический аудит Android-приложения ТСД (`ScannerKotlin`)

**Дата аудита:** 22.11.2026  
**Пакет приложения:** `com.example.scannerkotlin`  
**Технологический стек:** Kotlin 1.9.24, AGP 8.8.2, `minSdk = 24`, `targetSdk = 35`, `compileSdk = 35`, AndroidX AppCompat / Material / ConstraintLayout / RecyclerView, Coroutines, Retrofit 2.9.0 + Gson 2.10.1, JUnit 4 + Mockito 5.

---

## 1. Архитектура и распределение ответственности (Android vs Сервер Битрикс)

### 1.1. Модули Android-клиента
- **`MyApplication` + `utils/SessionManager`** — инициализация `SharedPreferences` (`AppSessionPrefs`) и безусловный старт `ScanService`.
- **`service/ScanService` + `utils/BaseScanActivity` + `utils/BarcodeScanGate`** — приём системного broadcast аппаратного сканера Xcheng (`com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST`) и ретрансляция во внутренний broadcast `Scan_data_received`.
- **Экраны (`activities/`)**: `ViewModel` в проекте не используется; всё состояние хранится в полях `Activity` и в RAM-синглтонах `ComingDocumentDraftManager` / `MovingDocumentDraftManager`.
  - Приход: `DocumentComingActivity` → `ProductsDocumentComingActivity` (`ProductComingAdapter`).
  - Перемещение: `DocumentMovingActivity` → `ProductsDocumentMovingActivity` (`ProductMovingAdapter`).
  - Инвентаризация: `ScanActivity` (`ProductScanAdapter`).
  - Приход СГП / списание комплектующих: `SgpActivity` (`SgpService`).
  - Контроль качества: `OtkActivity` (`OtkService`).
- **Сетевой слой (`config/`, `api/ApiBitrix`, `service/*`)**: интеграция с Bitrix24 REST API через три входящих вебхука (`BITRIX_WEBHOOK_CATALOG`, `BITRIX_WEBHOOK_BARCODE_SCAN`, `BITRIX_WEBHOOK_BARCODE_COMING`).

### 1.2. Что выполняется на Android, а что — на сервере Битрикс
Серверный модуль Битрикс (включая кастомные REST-методы `barcode.getproductid.json` и `barcode.setbyproductid.json`) расположен **вне данного репозитория** (на стороне портала Bitrix24). В Битрикс отсутствует единая серверная транзакция на цепочку «создание вариации → привязка штрихкода → добавление строк документа → проведение документа».

Фактическая последовательность запросов при проведении прихода (`CatalogDocumentComingService.conductDocumentSuspend`):
1. Для каждой позиции последовательно на клиенте:
   - `GET barcode.getproductid.json?barcode=...` — поиск существующего ID вариации по штрихкоду.
   - Если не найден (или при любой сетевой ошибке!) — `POST catalog.product.offer.add` (`iblockId = 15`, `parentId = product.id`) — создание торгового предложения в Битрикс.
   - `POST barcode.setbyproductid.json` (`productid`, `barcode`) — привязка штрихкода к созданной вариации.
2. Параллельно (`async` без ограничения конкурентности) для всех позиций:
   - `POST catalog.document.element.add` (`docId`, `storeFrom = 0`, `storeTo = 1`, `elementId`, `amount`, `purchasingPrice = 0`).
3. Последовательно для всех позиций:
   - `POST catalog.product.offer.update` (`weight`, `width`, `height`).
4. При наличии комментария/партий:
   - `POST catalog.userfield.document.update` (`field621`, `documentType = "A"`).
5. Финальный шаг:
   - `POST catalog.document.conduct` (`id = idDocument`) — изменение складских остатков на стороне Битрикс.

---

## 2. Подтверждённые проблемы в исходном коде

### P0 — Критичные (приводят к падениям, потере данных, дублям вариаций и двойному оприходованию)

| ID | Проблема | Доказательство в коде (файл, метод, строки) | Последствие |
|---|---|---|---|
| **AUD-01** | Черновики приходов и перемещений хранятся исключительно в оперативной памяти (`ConcurrentHashMap`) и записываются только в `onDestroy()` | `ComingDocumentDraftManager.kt:10-33`, `MovingDocumentDraftManager.kt:10-35`, `ProductsDocumentComingActivity.kt:154-166` | При crash, `am force-stop`, выгрузке процесса LMK или перезагрузке ТСД `onDestroy()` не вызывается либо память процесса очищается — **100% отсканированных позиций теряется**. |
| **AUD-02** | Синхронный вызов `adapter.notifyDataSetChanged()` внутри `OnLayoutChangeListener` во время прохода layout | `ProductsDocumentComingActivity.kt:217-223` (`setupRecyclerView`) | Падение `IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling` при изменении высоты окна/инсетов/клавиатуры/диалогов после добавления товаров. |
| **AUD-03** | Неограниченное накопление анонимных `TextWatcher` на `tvBarcode` при каждом `bind()` ViewHolder без удаления предыдущих | `ProductComingAdapter.kt:560-579` (`ProductViewHolder.bind`) | Утечка памяти и каскадное срабатывание десятков слушателей на каждое обновление текста/скролл при 30–50+ сканированиях, лаги главного потока и порча `barcode` переиспользуемых карточек. |
| **AUD-04** | Рассинхронизация `productList` и `productOffersList` при добавлении и удалении позиций | `ProductsDocumentComingActivity.kt:295-298` (`productList.add(0, ...)` vs `productOffersList.add(...)` в конец), `:342` (`productOffersList.removeAll { it.product.id == removedProduct.id }`) | При удалении одной позиции удаляются **все** вариации той же родительской номенклатуры (`product.id`). При 30 трубах одной номенклатуры удаление 1 ошибочной строки стирает 29 остальных из проведения. |
| **AUD-05** | Старт фонового `ScanService` через `startService()` в `Application.onCreate()` на `targetSdk = 35` | `MyApplication.kt:15-18` | На Android 8–15 при фоновом создании процесса (backup, broadcast, перезапуск системой) выбрасывается `BackgroundServiceStartNotAllowedException` (crash на старте). При остановке фонового сервиса системой сканер перестаёт работать. |
| **AUD-06** | Создание нового `Retrofit` и нового `OkHttpClient` (собственный `Dispatcher` thread pool + `ConnectionPool`) на каждый экземпляр сервиса/адаптера | `BitrixClientFactory.kt:12-24`, `ProductMovingAdapter.kt:35`, `ProductsDocumentComingActivity.kt:171` | Утечка пулов потоков, сокетов и памяти при многократном открытии/закрытии экранов и длительной смене на ТСД. |
| **AUD-07** | Вызов `AlertDialog.Builder(context).show()` из сетевых сервисов без проверки `isFinishing` / `isDestroyed` и удержание `Activity` в suspend-методах | `CatalogDocumentComingService.kt:57,64,266,297,858`, `CatalogDocumentMovingService.kt:381,387,396`, `SgpService.kt:975` | Падение `WindowManager$BadTokenException` при завершении сетевого запроса после закрытия экрана или поворота; удержание `Activity` на время тайм-аута. |
| **AUD-08** | Отсутствие проверки принадлежности штрихкода выбранной номенклатуре и перезапись чужого товара | `CatalogDocumentComingService.kt:472-478, 349-366, 522-531` | `resolveOfferIdByBarcode` возвращает любой `offerId` с этим штрихкодом без сверки `parentId`. Чужой товар попадает в приход и перезаписывается его вес/диаметр. Сетевой сбой в `resolveOfferIdByBarcode` трактуется как `null` (штрихкода нет), вызывая создание дубликата вариации. |
| **AUD-09** | Частичное создание вариаций до валидации всех позиций и повторное добавление строк документа (`catalog.document.element.add`) при повторном нажатии «Провести» | `CatalogDocumentComingService.kt:254-286, 396-453, 460-515` | Если ошибка на 10-й позиции или сбой `conductDocument`, вариации 1–9 уже созданы, а при повторе `addProductsToDocSuspend` повторно добавляет строки без проверки `idInDocument` и без сверки с `catalog.document.element.list`, удваивая количество и складские остатки. |
| **AUD-10** | Отсутствие сверки статуса документа после тайм-аута или сетевого обрыва `conductDocument` | `CatalogDocumentComingService.kt:42-69`, `CatalogDocumentMovingService.kt:366-393` | При HTTP-тайм-ауте документ мог фактически провестись в Битрикс (`status = "Y"`), но приложение считает операцию неуспешной и разрешает повторную мутацию. |

---

### P1 — Высокий приоритет (гонки сканера, падения отдельных экранов, валидация, безопасность логов)

| ID | Проблема | Доказательство в коде | Последствие |
|---|---|---|---|
| **AUD-11** | Отсутствие `BarcodeScanGate` и последовательной очереди сканирования в Приходе, Инвентаризации, СГП и ОТК | `ProductsDocumentComingActivity.kt:72, 458`, `ScanActivity.kt:61, 108`, `SgpActivity.kt:49, 166`, `OtkActivity.kt:47, 93` | Дребезг аппаратного сканера или быстрое сканирование вызывает гонки корутин, дубли карточек, наложение модальных диалогов выбора позиции. |
| **AUD-12** | Неверный `itemCount` в `notifyItemRangeChanged(position, products.size)` и обращение к `documentsSuspend[0]` без проверки пустоты | `ScanActivity.kt:270`, `SgpActivity.kt:351`, `SgpService.kt:425`, `UserService.kt:41` | `IndexOutOfBoundsException` в `SgpService` при отсутствии документа за дату, в `UserService` при неверном PIN, и риск рассинхронизации `RecyclerView`. |
| **AUD-13** | Неполная предварительная валидация прихода (`quantity == null` / `<= 0` превращается в `0.0`, не проверяются дубли штрихкодов внутри документа, `iblockId` номенклатуры, склад) | `ComingProductValidator.kt:6-31`, `CatalogDocumentComingService.kt:412, 421` | Невалидные строки доходят до мутирующих вызовов API Битрикс. |
| **AUD-14** | Запись PIN-кода пользователя в открытом виде в `Logcat` и зацикливание при неизвестной роли | `AuthActivity.kt:116, 172-179`, `UserService.kt:48, 52, 56, 61` | Утечка секретов в системный журнал; при неизвестной роли сессия уже сохранена и `AuthActivity` бесконечно перезапускает себя. |
| **AUD-15** | Нерабочие unit-тесты в текущем `main` (3 из 28 падают) | `CatalogDocumentComingServiceTest.kt:24, 45, 70`, `app/build.gradle.kts` | `RuntimeException: Method d in android.util.Log not mocked` и `NullPointerException` на `Mockito.any()` для non-null типов Kotlin. |

---

### P2 — Производительность и кэширование

| ID | Проблема | Доказательство в коде | Последствие |
|---|---|---|---|
| **AUD-16** | Отсутствие кэша справочников (номенклатура прихода, склады) и сброс всех страниц пагинации при сбое на одной странице | `CatalogDocumentComingService.kt:697-766`, `CatalogDocumentMovingService.kt:82-103` | Каждый клик «Добавить товар» заново выгружает каталог по сети; нет разграничения между вытесняемым кэшем справочников и постоянными черновиками документов. |
| **AUD-17** | Пересчёт `productList.filter` на каждый символ ввода и `notifyItemChanged(0)` при изменении комментария | `ProductsDocumentComingActivity.kt:560`, `ProductComingAdapter.kt:49` | Лишние перерисовки и аллокации на списках 100–1000 позиций; `notifyItemChanged(0)` обновляет 1-й товар вместо ячейки комментария (`productList.size`). |
| **AUD-18** | Заглушка `TODO("Not yet implemented")` в `ProductsDocumentComingActivity.onItemClick` | `ProductsDocumentComingActivity.kt:599-601` | Потенциальный `NotImplementedError`. |

---

## 3. План необходимых изменений

1. **Постоянное хранилище рабочих данных (SQLite):**
   - Реализовать надёжное транзакционное SQLite-хранилище (`TsdPersistenceDatabase`) для черновиков прихода (заголовок документа, `operationId`/`idempotencyKey`, состояние автомата `DRAFT` → `VALIDATING` → `READY` → `PROCESSING` → `COMMITTED` / `FAILED` / `RECONCILIATION_REQUIRED`, склад назначения, список разрешённой номенклатуры `field620Values`, комментарий), позиций прихода (с уникальным `localItemId`, штрихкодом, `productId`, `offerId`, `idInDocument`, количеством, единицей измерения, габаритами, партией, статусом синхронизации позиции, текстом ошибки, `updatedAt`) и журнала шагов проведения (`operation_step_journal`), а также сохранение черновиков перемещений.
   - Атомарно сохранять каждое сканирование и изменение поля без блокировки UI; очищать черновик только после подтверждённого `COMMITTED`.
   - Реализовать автоматическое обнаружение и восстановление незавершённых документов при перезапуске приложения (включая выбор документа, если активных несколько, и безопасную сверку прерванного `PROCESSING`).
2. **Устранение причин падений и утечек:**
   - Перевести `BitrixClientFactory` на единый пул `OkHttpClient` с настроенными тайм-аутами и безопасным диагностическим логированием без секретов.
   - Исправить старт `ScanService` и прямую/резервную доставку событий сканера в `BaseScanActivity` с защитой от дублирования и последовательной очередью обработки сканов.
   - Убрать накопление `TextWatcher` на `tvBarcode`, синхронный `notifyDataSetChanged()` из `OnLayoutChangeListener`, удержание `Context` в сервисах, рассинхронизацию `productList`/`productOffersList` и `IndexOutOfBoundsException` в `SgpService`/`UserService`/`ScanActivity`.
   - Установить глобальный обработчик неперехваченных исключений (`CrashDiagnosticsHandler`) для сохранения стека сбоя без подавления стандартного механизма завершения процесса.
3. **Кэширование справочников:**
   - Ввести отдельный ограниченный LRU+TTL кэш справочников (`CatalogReferenceCache`: номенклатура, склады, карточки товаров) с адресной инвалидацией, полностью изолированный от БД рабочих черновиков.
4. **Предварительная валидация и идемпотентное проведение приходов:**
   - Двухфазная валидация до любых мутаций в Битрикс: локальная проверка всех позиций + серверная проверка статуса документа, существования/типа родительских товаров и принадлежности всех штрихкодов.
   - Безопасный поиск и переиспользование вариаций: проверка `parentId` найденного по штрихкоду предложения, поиск существующей вариации у родительского товара, отказ с понятным конфликтом при чужом штрихкоде (без модификации чужого товара), сохранение `offerId` сразу после `offer.add` в журнал для исключения дублей при сбое привязки штрихкода.
   - Сверка строк документа через `catalog.document.element.list` и статуса документа через `catalog.document.list` перед добавлением строк и после тайм-аутов `conductDocument`.
5. **Улучшения UI ТСД и тестирование:**
   - Компактная строка статуса документа и сохранения на экране прихода, подсветка и переход к проблемной позиции, защита от двойного нажатия, восстановление черновиков.
   - Комплексные unit-, интеграционные (Mock API), стресс-тесты (100 / 500 / 1000 позиций) и эмуляторные ADB-проверки (`force-stop`, восстановление, профилирование памяти).
