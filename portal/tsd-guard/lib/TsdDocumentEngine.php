<?php

class TsdDocumentEngine
{
    /** @var TsdOperationStore */
    private $store;
    /** @var TsdLockManager */
    private $locks;
    /** @var TsdBitrixGateway */
    private $bitrix;

    public function __construct(TsdOperationStore $store, TsdLockManager $locks, TsdBitrixGateway $bitrix)
    {
        $this->store = $store;
        $this->locks = $locks;
        $this->bitrix = $bitrix;
    }

    public function syncAndConduct(array $query): array
    {
        $operationId = trim((string)($query['operationId'] ?? ''));
        $documentId = (int)($query['documentId'] ?? 0);
        $items = is_array($query['items'] ?? null) ? $query['items'] : [];
        $deleted = is_array($query['deletedRemoteElementIds'] ?? null) ? $query['deletedRemoteElementIds'] : [];

        if ($operationId === '' || $documentId <= 0) {
            throw new TsdGuardException('Invalid request', 'INVALID_REQUEST', 400);
        }
        if ($items === []) {
            throw new TsdGuardException('Document items are required', 'INVALID_REQUEST', 400);
        }

        $this->validateItems($items);

        $payloadParts = [];
        foreach ($items as $item) {
            $payloadParts[] = ($item['localItemId'] ?? '') . ':' . ($item['offerId'] ?? '') . ':' .
                ($item['quantity'] ?? '') . ':' . ($item['storeTo'] ?? '') . ':' . ($item['batchNumber'] ?? '');
        }
        sort($payloadParts);
        $payloadHash = sha1(implode(';', $payloadParts));
        $opKey = 'D:' . $operationId . ':' . $documentId;
        $lockName = 'tsd_doc_' . $documentId;

        if ($this->locks->isHeldByOther($lockName)) {
            throw new TsdGuardException(
                'Проведение этого документа ещё выполняется другим процессом.',
                'OPERATION_IN_PROGRESS',
                409
            );
        }
        if (!$this->locks->acquire($lockName)) {
            throw new TsdGuardException('Lock timeout', 'LOCK_TIMEOUT', 423);
        }
        try {
            $existing = $this->store->loadOperation($opKey);
            if ($existing && $existing['state'] === 'COMMITTED') {
                if ($existing['payload_hash'] !== $payloadHash) {
                    throw new TsdGuardException(
                        'operationId reused with different payload',
                        'OPERATION_PAYLOAD_CONFLICT',
                        409
                    );
                }
                $saved = json_decode($existing['result_json'] ?? 'null', true);
                if (!is_array($saved) || ($saved['status'] ?? '') !== 'Y') {
                    throw new TsdGuardException(
                        'Сохранённый результат проведения повреждён. Требуется сверка.',
                        'RECONCILIATION_REQUIRED',
                        409
                    );
                }
                return $saved;
            }
            if ($existing && $existing['payload_hash'] !== $payloadHash) {
                throw new TsdGuardException(
                    'operationId reused with different payload',
                    'OPERATION_PAYLOAD_CONFLICT',
                    409
                );
            }

            $doc = $this->loadDocument($documentId);
            if ($doc['status'] === 'Y') {
                $result = $this->alreadyConductedResult($operationId, $documentId, $items);
                $this->saveCommitted($opKey, $operationId, $documentId, $payloadHash, $result);
                return $result;
            }
            if ($doc['status'] === 'C') {
                throw new TsdGuardException(
                    'Документ отменён в Битрикс и не может быть проведён.',
                    'DOCUMENT_CANCELLED',
                    409
                );
            }
            // Explicit allowlist: never mutate a document with an unknown state.
            // Bitrix catalog.document.status used by this client: N=draft, Y=conducted, C=cancelled.
            if ($doc['status'] !== 'N') {
                throw new TsdGuardException(
                    'Недопустимый статус документа для проведения: ' . $doc['status'],
                    'DOCUMENT_STATUS_UNVERIFIED',
                    409
                );
            }

            if (!$existing) {
                $this->writeStarted($opKey, $operationId, $documentId, $payloadHash, 'STARTED', ['items' => []]);
                $this->step($opKey, 'STARTED', 'SUCCEEDED', null, null, 'document operation registered');
            } elseif (($existing['step'] ?? '') === 'ELEMENT_ADD_PENDING') {
                $progress = json_decode($existing['progress_json'] ?? 'null', true) ?: [];
                $pending = $progress['pendingLocalItemId'] ?? null;
                $pendingHasId = $pending && !empty($progress['items'][$pending]['elementId']);
                if ($pending && !$pendingHasId) {
                    $serverRows = $this->listElements($documentId);
                    $mappedIds = [];
                    foreach ($items as $item) {
                        $mapped = $this->store->findMappedElement($operationId, (string)$item['localItemId'], $documentId);
                        if ($mapped) {
                            $mappedIds[(int)$mapped['bitrix_element_id']] = true;
                        }
                    }
                    $unmapped = 0;
                    foreach ($serverRows as $row) {
                        $rid = (int)($row['id'] ?? 0);
                        if ($rid > 0 && empty($mappedIds[$rid])) {
                            $unmapped++;
                        }
                    }
                    if ($unmapped > 0) {
                        throw new TsdGuardException(
                            'Предыдущее добавление строки прервалось с неизвестным результатом. Повторный element.add заблокирован до сверки.',
                            'RECONCILIATION_REQUIRED',
                            409
                        );
                    }
                }
            }

            return $this->mutateAndConduct($opKey, $operationId, $documentId, $payloadHash, $items, $deleted);
        } finally {
            $this->locks->release($lockName);
        }
    }

    private function validateItems(array $items): void
    {
        $storeIds = $this->loadStoreIds();
        $seen = [];
        foreach ($items as $index => $item) {
            $pos = $index + 1;
            $localItemId = trim((string)($item['localItemId'] ?? ''));
            $offerId = (int)($item['offerId'] ?? 0);
            $qty = isset($item['quantity']) ? (float)$item['quantity'] : null;
            $storeTo = (int)($item['storeTo'] ?? 0);
            $batch = $item['batchNumber'] ?? null;

            if ($localItemId === '') {
                $this->invalidItem($pos, $localItemId, 'отсутствует корректный localItemId.');
            }
            if (isset($seen[$localItemId])) {
                $this->invalidItem($pos, $localItemId, 'дублирование localItemId.');
            }
            $seen[$localItemId] = true;
            if ($offerId <= 0) {
                $this->invalidItem($pos, $localItemId, 'отсутствует корректный идентификатор вариации.');
            }
            if ($qty === null || !is_finite($qty) || $qty <= 0) {
                $this->invalidItem($pos, $localItemId, 'количество должно быть больше 0.');
            }
            if ($storeTo <= 0) {
                $this->invalidItem($pos, $localItemId, 'не указан склад назначения.');
            }
            if ($batch !== null && $batch !== '' && !is_scalar($batch)) {
                $this->invalidItem($pos, $localItemId, 'некорректная партия.');
            }

            $offer = $this->bitrix->call('catalog.product.get', ['id' => (string)$offerId]);
            if (!$offer['ok'] || !$offer['verified']) {
                $this->invalidItem($pos, $localItemId, 'не удалось проверить вариацию ID ' . $offerId . '.');
            }
            $product = $offer['result']['product'] ?? $offer['result'];
            if (!$product) {
                $this->invalidItem($pos, $localItemId, 'вариация ID ' . $offerId . ' не найдена.');
            }

            if (!in_array($storeTo, $storeIds, true)) {
                $this->invalidItem($pos, $localItemId, 'склад ID ' . $storeTo . ' не существует.');
            }
        }
    }

    /** @return int[] Empty verified list means no valid destination stores. */
    private function loadStoreIds(): array
    {
        $store = $this->bitrix->call('catalog.store.list', []);
        if (!$store['ok'] || !$store['verified']) {
            throw new TsdGuardException(
                'Не удалось проверить склады Битрикс.',
                'DOCUMENT_STATUS_UNVERIFIED',
                409
            );
        }
        $stores = $store['result']['stores'] ?? $store['result'];
        $storeIds = [];
        if (is_array($stores)) {
            foreach ($stores as $row) {
                if (is_array($row) && isset($row['id'])) {
                    $storeIds[] = (int)$row['id'];
                }
            }
        }
        return $storeIds;
    }

    private function invalidItem(int $pos, string $localItemId, string $reason): void
    {
        throw new TsdGuardException(
            $reason,
            'INVALID_DOCUMENT_ITEM',
            400,
            ['positionIndex' => $pos, 'localItemId' => $localItemId]
        );
    }

    /**
     * @return array{status:string,docType:string}
     */
    private function loadDocument(int $documentId): array
    {
        $resp = $this->bitrix->call('catalog.document.list', [
            'filter' => ['id' => $documentId, 'docType' => 'A'],
        ]);
        if (!$resp['ok'] || !$resp['verified']) {
            throw new TsdGuardException(
                'Не удалось достоверно подтвердить статус документа. Строки и проведение заблокированы.',
                'DOCUMENT_STATUS_UNVERIFIED',
                409
            );
        }
        if (!is_array($resp['result']) || !array_key_exists('documents', is_array($resp['result']) ? $resp['result'] : [])) {
            throw new TsdGuardException(
                'Ответ catalog.document.list не содержит documents. Изменения заблокированы.',
                'DOCUMENT_STATUS_UNVERIFIED',
                409
            );
        }
        $docs = $resp['result']['documents'];
        if (!is_array($docs) || $docs === []) {
            throw new TsdGuardException('Документ не найден в Битрикс.', 'DOCUMENT_NOT_FOUND', 404);
        }
        $doc = $docs[0];
        if (!is_array($doc) || !array_key_exists('status', $doc)) {
            throw new TsdGuardException(
                'Статус документа в ответе Битрикс отсутствует.',
                'DOCUMENT_STATUS_UNVERIFIED',
                409
            );
        }
        $status = $doc['status'];
        if ($status === null || $status === '') {
            throw new TsdGuardException(
                'Статус документа неизвестен (null).',
                'DOCUMENT_STATUS_UNVERIFIED',
                409
            );
        }
        $docType = (string)($doc['docType'] ?? '');
        if ($docType !== 'A') {
            throw new TsdGuardException('Тип документа не является приходом.', 'INVALID_REQUEST', 400);
        }
        return ['status' => strtoupper((string)$status), 'docType' => $docType];
    }

    private function mutateAndConduct(
        string $opKey,
        string $operationId,
        int $documentId,
        string $payloadHash,
        array $items,
        array $deleted
    ): array {
        foreach ($deleted as $delId) {
            $del = (int)$delId;
            if ($del <= 0) {
                continue;
            }
            $this->requireOk('catalog.document.element.delete', [
                'id' => $del,
                'fields' => ['docId' => $documentId],
            ], 'DELETE_ELEMENT_FAILED');
        }

        $serverRows = $this->listElements($documentId);
        $progress = ['items' => []];
        $existingOp = $this->store->loadOperation($opKey);
        if ($existingOp && !empty($existingOp['progress_json'])) {
            $decoded = json_decode($existingOp['progress_json'], true);
            if (is_array($decoded)) {
                $progress = $decoded + ['items' => []];
            }
        }

        $synced = [];
        $elementIds = [];
        foreach ($items as $index => $item) {
            $localItemId = (string)$item['localItemId'];
            $offerId = (int)$item['offerId'];
            $qty = (float)$item['quantity'];
            $storeTo = (int)$item['storeTo'];

            $mapped = $this->store->findMappedElement($operationId, $localItemId, $documentId);
            $knownId = $mapped ? (int)$mapped['bitrix_element_id'] : (int)($progress['items'][$localItemId]['elementId'] ?? 0);

            if ($knownId <= 0) {
                $knownId = $this->recoverElementId($operationId, $localItemId, $documentId, $offerId, $serverRows, $items);
            }

            if ($knownId > 0) {
                $this->requireOk('catalog.document.element.update', [
                    'id' => $knownId,
                    'fields' => [
                        'docId' => $documentId,
                        'storeTo' => $storeTo,
                        'amount' => (string)$qty,
                    ],
                ], 'UPDATE_ELEMENT_FAILED');
                $this->store->mapElement($operationId, $localItemId, $documentId, $knownId, $offerId);
                $rowId = $knownId;
            } else {
                $progress['pendingLocalItemId'] = $localItemId;
                $this->writeStarted($opKey, $operationId, $documentId, $payloadHash, 'ELEMENT_ADD_PENDING', $progress);
                $this->step($opKey, 'ELEMENT_ADD_PENDING', 'STARTED', null, $localItemId, 'calling catalog.document.element.add');

                $added = $this->requireOk('catalog.document.element.add', [
                    'fields' => [
                        'docId' => $documentId,
                        'storeFrom' => 0,
                        'storeTo' => $storeTo,
                        'elementId' => $offerId,
                        'amount' => (string)$qty,
                        'purchasingPrice' => 0,
                    ],
                ], 'ADD_ELEMENT_FAILED');
                $rowId = (int)($added['documentElement']['id'] ?? $added['id'] ?? 0);
                if ($rowId <= 0) {
                    throw new TsdGuardException(
                        'element.add не вернул id строки. Требуется сверка.',
                        'RECONCILIATION_REQUIRED',
                        409
                    );
                }
                $progress['items'][$localItemId] = ['elementId' => $rowId, 'offerId' => $offerId, 'mapped' => false];
                unset($progress['pendingLocalItemId']);
                $this->writeStarted($opKey, $operationId, $documentId, $payloadHash, 'ELEMENT_CREATED', $progress);
                $this->step($opKey, 'ELEMENT_CREATED', 'SUCCEEDED', $rowId, $localItemId, 'row created');
                $this->store->mapElement($operationId, $localItemId, $documentId, $rowId, $offerId);
                $progress['items'][$localItemId]['mapped'] = true;
                $this->writeStarted($opKey, $operationId, $documentId, $payloadHash, 'ELEMENT_MAPPED', $progress);
                $this->step($opKey, 'ELEMENT_MAPPED', 'SUCCEEDED', $rowId, $localItemId, 'mapping stored');
            }

            $elementIds[] = $rowId;
            $synced[] = [
                'localItemId' => $localItemId,
                'offerId' => $offerId,
                'elementId' => $rowId,
                'quantity' => $qty,
            ];
        }

        $this->writeStarted($opKey, $operationId, $documentId, $payloadHash, 'CONDUCT_PENDING', $progress);
        $this->requireOk('catalog.document.conduct', ['id' => $documentId], 'CONDUCT_FAILED');
        $final = $this->loadDocument($documentId);
        if ($final['status'] !== 'Y') {
            throw new TsdGuardException(
                'После conduct статус документа не подтверждён как Y.',
                'CONDUCT_UNCONFIRMED',
                500
            );
        }
        $result = [
            'documentId' => $documentId,
            'status' => 'Y',
            'alreadyConducted' => false,
            'elementIds' => $elementIds,
            'items' => $synced,
            'operationId' => $operationId,
        ];
        $this->saveCommitted($opKey, $operationId, $documentId, $payloadHash, $result);
        return $result;
    }

    private function recoverElementId(
        string $operationId,
        string $localItemId,
        int $documentId,
        int $offerId,
        array $serverRows,
        array $requestItems
    ): int {
        $mapped = $this->store->findMappedElement($operationId, $localItemId, $documentId);
        if ($mapped) {
            return (int)$mapped['bitrix_element_id'];
        }
        $steps = $this->store->steps('D:' . $operationId . ':' . $documentId);
        foreach (array_reverse($steps) as $step) {
            if (($step['local_item_id'] ?? '') === $localItemId && !empty($step['remote_entity_id'])) {
                return (int)$step['remote_entity_id'];
            }
        }
        $sameOfferRequestCount = 0;
        foreach ($requestItems as $item) {
            if ((int)$item['offerId'] === $offerId) {
                $sameOfferRequestCount++;
            }
        }
        if ($sameOfferRequestCount > 1) {
            return 0;
        }
        return 0;
    }

    private function listElements(int $documentId): array
    {
        $resp = $this->bitrix->call('catalog.document.element.list', [
            'filter' => ['docId' => $documentId],
        ]);
        if (!$resp['ok'] || !$resp['verified']) {
            throw new TsdGuardException(
                'Не удалось получить строки документа для сверки.',
                'RECONCILIATION_REQUIRED',
                409
            );
        }
        $result = $resp['result'];
        if (!is_array($result) || !array_key_exists('documentElements', $result)
            || !is_array($result['documentElements'])) {
            throw new TsdGuardException(
                'Ответ о строках документа неполон; создание новых строк заблокировано до сверки.',
                'RECONCILIATION_REQUIRED',
                409
            );
        }
        return $result['documentElements'];
    }

    private function alreadyConductedResult(string $operationId, int $documentId, array $items): array
    {
        $synced = [];
        $elementIds = [];
        foreach ($items as $item) {
            $localItemId = (string)($item['localItemId'] ?? '');
            $mapped = $this->store->findMappedElement($operationId, $localItemId, $documentId);
            $rowId = $mapped ? (int)$mapped['bitrix_element_id'] : 0;
            $synced[] = [
                'localItemId' => $localItemId,
                'offerId' => (int)($item['offerId'] ?? 0),
                'elementId' => $rowId,
                'quantity' => (float)($item['quantity'] ?? 0),
            ];
            if ($rowId > 0) {
                $elementIds[] = $rowId;
            }
        }
        return [
            'documentId' => $documentId,
            'status' => 'Y',
            'alreadyConducted' => true,
            'elementIds' => $elementIds,
            'items' => $synced,
            'operationId' => $operationId,
        ];
    }

    private function requireOk(string $method, array $params, string $errorCode)
    {
        $resp = $this->bitrix->call($method, $params);
        if (!$resp['ok'] || !$resp['verified']) {
            throw new TsdGuardException(
                $resp['errorDescription'] ?: $method . ' failed',
                $errorCode,
                500
            );
        }
        return $resp['result'];
    }

    private function writeStarted(string $opKey, string $operationId, int $documentId, string $payloadHash, string $step, array $progress): void
    {
        $this->store->saveOperation([
            'op_key' => $opKey,
            'operation_id' => $operationId,
            'kind' => 'DOCUMENT',
            'document_id' => $documentId,
            'payload_hash' => $payloadHash,
            'state' => 'STARTED',
            'step' => $step,
            'offer_id' => null,
            'progress_json' => $progress,
            'result_json' => null,
        ]);
    }

    private function saveCommitted(string $opKey, string $operationId, int $documentId, string $payloadHash, array $result): void
    {
        $this->store->saveOperation([
            'op_key' => $opKey,
            'operation_id' => $operationId,
            'kind' => 'DOCUMENT',
            'document_id' => $documentId,
            'payload_hash' => $payloadHash,
            'state' => 'COMMITTED',
            'step' => 'COMMITTED',
            'offer_id' => null,
            'progress_json' => $result,
            'result_json' => $result,
        ]);
        $this->step($opKey, 'COMMITTED', 'SUCCEEDED', $documentId, null, 'document conducted');
    }

    private function step(string $opKey, string $type, string $status, $remoteId, $localItemId, $details): void
    {
        $this->store->appendStep([
            'op_key' => $opKey,
            'step_type' => $type,
            'status' => $status,
            'remote_entity_id' => $remoteId,
            'local_item_id' => $localItemId,
            'details' => $details,
        ]);
    }
}
