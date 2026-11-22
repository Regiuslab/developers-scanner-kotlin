# ТСД 1.1.0 — пакет контролируемого боевого тестирования

**Не production-релиз.** Серверный модуль `portal/tsd-guard/` на боевом портале **не установлен и не проверен**.  
Сборка: `LEGACY_MODE`. `SERVER_GUARDED` **не включать** до отдельного подтверждения интеграции.

| Поле | Значение |
|---|---|
| Базовый коммит | `2789954` |
| applicationId | `com.example.scannerkotlin` |
| versionCode | `2` |
| versionName | `1.1.0` |
| BITRIX_CONCURRENCY_MODE | `LEGACY` (debug и release) |
| Подпись | Android Debug (`CN=Android Debug`), SHA-256 сертификата `96e5589a5760549d5d72d969bd05b52ba85afb06bcb676684cc6251d4b7866c0` |
| SQLite | `tsd_operational_state.db`, schema v2 — при обновлении поверх 1.0 черновики сохраняются |

## APK

Положить на ТСД **release**-сборку (тот же debug-сертификат, что и прежние тестовые APK):

| Файл | SHA-256 |
|---|---|
| `release/1.1.0/tsd-1.1.0-release.apk` | `a8f24b44214e33fb7743cfa94c18ad88a84cdf5b015e4f576e7e827f1d29d6cb` |
| `release/1.1.0/tsd-1.1.0-debug.apk` | `c40fb00930a60b128e4303d1c1becaedd15e8f77c50364727e6a1ed41f7d7d92` |

Проверено `apksigner verify --print-certs`: оба APK подписаны **одним** сертификатом. Удалять приложение перед установкой **нельзя** — пропадут незавершённые приходы.

Установка поверх 1.0: `adb install -r tsd-1.1.0-release.apk` или копирование APK на устройство. Если установщик напишет про другую подпись — **остановиться**, не сносить данные.

## Что проверено в этой сборке

- PHP lint: без ошибок.
- PHP-тесты: **50/50 PASS** (в т.ч. `status=X`, нет `docType`, пустой список складов, нет/`не массив` `documentElements`).
- JVM unit-тесты: **73/73 PASS** (включая SQLite/file persistence ST-01… и CX-01…15).
- `assembleDebug` / `assembleRelease`: **BUILD SUCCESSFUL**.
- Instrumentation (`connectedDebugAndroidTest`): **NOT RUN**.
- Обновление 1.0 → 1.1.0 на эмуляторе Pixel_10_Pro_XL: `adb install -r` **Success**; черновик прихода 462 (`DRAFT`, 1 позиция, qty 7.5) сохранился.
- Исходный APK со склада (1.0 с устройства) **не найден**. 1.0 собран с `e067c0b` на этой машине; сертификат совпал с 1.1.0. Совместимость с **чужим** debug-keystore не подтверждена.
- Живой Битрикс / CRest / `GET_LOCK` на портале: **NOT RUN**.

## Этапы на складе

См. `TSD_WAREHOUSE_TEST_INSTRUCTION.md`.

- **Этап А** — сканирование, перезапуск, перезагрузка, офлайн. **Без кнопки «Провести».**
- **Этап Б** — один согласованный контрольный документ, только после проверки серверного модуля. Не массовые и не конкурентные проведения на боевых остатках.

P0 (вынос webhook-ключей, отдельный production-keystore) в этот пакет **не входил**.
