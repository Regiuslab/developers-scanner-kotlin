# Портальный модуль tsd-guard

Статус: **написан, не установлен, не integration-tested.**  
Не считать эти файлы работающим endpoint на `bitrix.izocom.by`.

Модуль регистрирует REST-методы:

- `tsd.variation.resolveOrCreate`
- `tsd.document.syncAndConduct`

Они сериализуют конкурентные ТСД через MySQL `GET_LOCK` и таблицу идемпотентности `tsd_operation`. Это не эмуляция на Android.

## Установка (Bitrix24 on-prem)

1. Прогнать `install.sql` на БД портала.
2. Скопировать каталог в `/local/modules/regiuslab.tsdguard/` **или** подключить `include.php` из `/local/php_interface/init.php`:

```php
require_once $_SERVER['DOCUMENT_ROOT'] . '/local/modules/regiuslab.tsdguard/include.php';
```

3. Проверить методы тем же webhook, что использует ТСД:

```
POST /rest/1/{token}/tsd.variation.resolveOrCreate
```

4. На staging-сборке ТСД выставить `bitrix.concurrency.mode=SERVER_GUARDED` в `local.properties`.
5. Не включать `SERVER_GUARDED` на production, пока не пройден чек-лист из `BITRIX_TSD_SERVER_LOCK_SPEC.md`.

## Ограничение

Внутренние вызовы каталога используют REST-обёртки Битрикс (`catalog.product.offer.add` и т.д.) **внутри уже взятого `GET_LOCK`**. Атомарность для клиентов — на границе PHP-запроса, не отдельная ACID-транзакция InnoDB на все таблицы каталога.
