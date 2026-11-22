# Готовность ТСД 1.1.0 к передаче ответственному

Пакет **не отправлялся** сотрудникам склада. Передать вручную.

## Ссылка

- Релиз: https://github.com/Regiuslab/developers-scanner-kotlin/releases/tag/v1.1.0-tsd-pilot
- APK: https://github.com/Regiuslab/developers-scanner-kotlin/releases/download/v1.1.0-tsd-pilot/tsd-1.1.0-release.apk
- SHA-256 APK: `a8f24b44214e33fb7743cfa94c18ad88a84cdf5b015e4f576e7e827f1d29d6cb`

## Сборка

| Поле | Значение |
|---|---|
| Коммит | `2789954510dac029e4a906cc22fae697049cf409` |
| applicationId | `com.example.scannerkotlin` |
| versionCode / versionName | `2` / `1.1.0` |
| BITRIX_CONCURRENCY_MODE | **LEGACY** |
| Подпись | Android Debug, cert SHA-256 `96e5589a5760549d5d72d969bd05b52ba85afb06bcb676684cc6251d4b7866c0` |
| apksigner | Verified, v2 |

## Тесты (повторный прогон)

| | |
|---|---|
| PHP lint + `tests/run.php` | **50/50 PASS** |
| JVM unit-тесты | **73/73 PASS** |
| `assembleRelease` | **BUILD SUCCESSFUL** |
| Эмулятор Pixel_10_Pro_XL: 1.0 → 1.1.0 | `adb install -r` **Success**; черновик 462 `DRAFT` qty 7.5 **на месте** |
| `connectedDebugAndroidTest` | **NOT RUN** |
| Живой Битрикс | **NOT RUN** |

## Подпись относительно 1.0

Файл APK 1.0 со склада **не найден**. Для сравнения 1.0 собран с `e067c0b` на этой машине: сертификат **совпал**. Обновление на эмуляторе прошло без ошибки подписи.

Совместимость с ТСД, где 1.0 подписан **другим** debug-keystore, **не подтверждена**. Если установщик просит удалить приложение — остановиться.

## Ограничения для склада

Только Этап А (сканы, перезапуск, перезагрузка, черновик). Без «Провести». Без `SERVER_GUARDED`.
