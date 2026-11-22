# Портальный модуль tsd-guard

Статус исходников: **готовы к установке на staging**.  
Статус интеграции с живым Битрикс: **не проверено** (портал не вызывался).

Методы:

- `tsd.variation.resolveOrCreate`
- `tsd.document.syncAndConduct`

## Установка на staging

1. Бэкап БД портала.
2. Новый портал: выполнить `install.sql`. Уже ставили предыдущую версию: `install_upgrade.sql`.
3. Скопировать каталог в `/local/modules/regiuslab.tsdguard/` (или `/local/php_interface/tsd-guard/`).
4. В `/local/php_interface/init.php` **после** пролога Битрикс:

```php
require_once $_SERVER['DOCUMENT_ROOT'] . '/local/modules/regiuslab.tsdguard/include.php';
```

5. Проверить webhook:

```
POST /rest/1/{token}/tsd.variation.resolveOrCreate
POST /rest/1/{token}/tsd.document.syncAndConduct
```

6. На staging-сборке ТСД: `bitrix.concurrency.mode=SERVER_GUARDED` в `local.properties`.
7. Не включать `SERVER_GUARDED` на production до чек-листа из `BITRIX_TSD_SERVER_LOCK_SPEC.md`.

## Откат

1. На ТСД вернуть `bitrix.concurrency.mode=LEGACY` и пересобрать/поставить APK.
2. Убрать `require_once ... include.php` из `init.php`.
3. Методы `tsd.*` перестанут существовать; клиент в LEGACY снова ходит стандартным REST.
4. Таблицы `tsd_operation*` можно оставить (не мешают) или удалить после бэкапа.

## Восстановление STARTED

Запись `STARTED` **не удаляется по тайм-ауту**. Повтор с тем же `operationId`:

- если блокировка MySQL занята другим соединением → `OPERATION_IN_PROGRESS`;
- если блокировка свободна (процесс PHP умер, `GET_LOCK` снят) — сверка журнала и Битрикс;
- неизвестный результат `offer.add` / `element.add` → `RECONCILIATION_REQUIRED`, без второй сущности.

## Ограничения

- Внутренние вызовы идут через `CRest::call`, если класс доступен. На коробочном Битрикс24 без модуля rest/crest установка потребует наличия `CRest` или замены `TsdBitrixRestGateway`.
- Атомарность для клиентов — граница PHP-запроса + `GET_LOCK`, не одна InnoDB-транзакция на все таблицы каталога.
- При убийстве процесса PHP MySQL отпускает `GET_LOCK`; это используется для безопасного resume, а не для удаления `STARTED`.
