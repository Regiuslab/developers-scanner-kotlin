# ScannerKotlin

Android-приложение для складского сканирования и работы с документами на базе Bitrix REST.
Поддерживает сканирование штрихкодов, создание и проведение документов прихода/перемещения,
а также отдельные рабочие сценарии (СГП и ОТК).

## Возможности

- Авторизация по 4‑значному PIN с проверкой через Bitrix REST.
- Сканирование штрихкодов через аппаратный сканер и обработка в приложении.
- Документы:
  - перемещение (docType `M`);
  - приход (docType `A`);
  - просмотр списков, редактирование позиций, проведение.
- Работа с номенклатурой и остатками по складам.
- Режимы СГП и ОТК для отдельных бизнес‑процессов.

## Основные сценарии

- `AuthActivity` — ввод PIN (4 цифры), запрос в Bitrix и сохранение сессии.
- Роль `storekeeper` (`75`) → `MainActivity` (сканирование, документы, СГП).
- Роль `otk` (`76`) → `OtkActivity` (контроль качества).
- Сканирование реализовано через `ScanService` + `BaseScanActivity`.

## Архитектурные заметки

- `MyApplication` инициализирует `SessionManager` и запускает `ScanService`.
- Документы перемещения: `DocumentMovingActivity` → позиции в `ProductsDocumentMovingActivity`.
- Документы прихода: `DocumentComingActivity` → позиции в `ProductsDocumentComingActivity`.
- Черновики позиций и комментариев хранятся в памяти приложения:
  - `ComingDocumentDraftManager` для приходов;
  - `MovingDocumentDraftManager` для перемещений.

## Технологии

- Kotlin 1.9.24, Android Gradle Plugin 8.8.2
- Android SDK: `minSdk 24`, `targetSdk 35`
- Retrofit + Gson

## Сборка и запуск

Требования: Android Studio, JDK 11, Android SDK.

```bash
./gradlew assembleDebug
```

Установка на устройство/эмулятор:

```bash
./gradlew installDebug
```

## Конфигурация Bitrix REST

В проекте используются **жестко заданные** REST‑адреса Bitrix. При переносе на другой контур
обновите базовые URL в следующих классах:

- `UserService` — авторизация по PIN.
- `CatalogDocumentMovingService`, `CatalogDocumentComingService`, `OtkService` — работа с документами/процессами.
- `CatalogDocumentComingService` — отдельный `barcodeBaseUrl` для операций со штрихкодами.
- `ScanActivity`, `SgpActivity`, `ProductsDocumentMovingActivity` — запросы по штрихкодам/товарам.

## Интеграция сканера

Сканирование работает через broadcast:

- Внешний сканер шлет `com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST`.
- `ScanService` принимает событие и рассылает локальный `Scan_data_received` с extra `scanData`.
- Активности наследуются от `BaseScanActivity` и получают данные в `onBarcodeScanned`.

## Структура проекта

- `app/src/main/java/com/example/scannerkotlin/activities` — экраны приложения
- `app/src/main/java/com/example/scannerkotlin/service` — сервисы и работа с API
- `app/src/main/java/com/example/scannerkotlin/api` — интерфейс Retrofit
- `app/src/main/java/com/example/scannerkotlin/adapter` — адаптеры списков
- `app/src/main/java/com/example/scannerkotlin/mappers` — мапперы API → модели
- `app/src/main/java/com/example/scannerkotlin/model` — модели данных
- `app/src/main/java/com/example/scannerkotlin/request` и `response` — DTO для API
- `app/src/main/java/com/example/scannerkotlin/utils` — утилиты, сессия, черновики

## Тесты

```bash
./gradlew test
```

Инструментальные тесты:

```bash
./gradlew connectedAndroidTest
```
