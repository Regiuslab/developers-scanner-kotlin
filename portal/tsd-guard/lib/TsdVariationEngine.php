<?php

class TsdVariationEngine
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

    public function resolveOrCreate(array $query): array
    {
        $operationId = trim((string)($query['operationId'] ?? ''));
        $parentId = (int)($query['parentProductId'] ?? 0);
        $barcode = trim((string)($query['barcode'] ?? ''));
        $localItemId = trim((string)($query['localItemId'] ?? ''));
        $fields = is_array($query['offerFields'] ?? null) ? $query['offerFields'] : [];

        if ($operationId === '' || $parentId <= 0 || $localItemId === '') {
            throw new TsdGuardException('Invalid request', 'INVALID_REQUEST', 400);
        }

        $payloadHash = sha1($parentId . '|' . $barcode . '|' . $localItemId);
        $opKey = 'V:' . $operationId . ':' . $localItemId;
        $lockName = 'tsd_barcode_' . ($barcode !== '' ? $barcode : $localItemId);

        if ($this->locks->isHeldByOther($lockName)) {
            throw new TsdGuardException(
                'Операция по этому штрихкоду ещё выполняется другим процессом.',
                'OPERATION_IN_PROGRESS',
                409
            );
        }
        if (!$this->locks->acquire($lockName)) {
            throw new TsdGuardException('Lock timeout', 'LOCK_TIMEOUT', 423);
        }
        try {
            $existing = $this->store->loadOperation($opKey);
            if ($existing) {
                if ($existing['payload_hash'] !== $payloadHash && $existing['state'] === 'COMMITTED') {
                    throw new TsdGuardException(
                        'operationId reused with different payload',
                        'OPERATION_PAYLOAD_CONFLICT',
                        409
                    );
                }
                if ($existing['payload_hash'] !== $payloadHash && $existing['state'] !== 'COMMITTED') {
                    throw new TsdGuardException(
                        'operationId reused with different payload',
                        'OPERATION_PAYLOAD_CONFLICT',
                        409
                    );
                }
                if ($existing['state'] === 'COMMITTED') {
                    $saved = json_decode($existing['result_json'] ?? 'null', true);
                    if (!is_array($saved) || empty($saved['offerId'])) {
                        throw new TsdGuardException(
                            'Сохранённый результат операции повреждён. Требуется сверка.',
                            'RECONCILIATION_REQUIRED',
                            409
                        );
                    }
                    return $saved;
                }
                return $this->resume($existing, $parentId, $barcode, $fields, $operationId, $opKey, $payloadHash, $localItemId);
            }

            $this->writeOp($opKey, $operationId, $payloadHash, 'STARTED', 'STARTED', null, ['items' => []]);
            $this->step($opKey, 'STARTED', 'SUCCEEDED', null, $localItemId, 'operation registered');
            return $this->createOrReuse($opKey, $operationId, $parentId, $barcode, $fields, $payloadHash, $localItemId, null);
        } finally {
            $this->locks->release($lockName);
        }
    }

    private function resume(
        array $existing,
        int $parentId,
        string $barcode,
        array $fields,
        string $operationId,
        string $opKey,
        string $payloadHash,
        string $localItemId
    ): array {
        $step = (string)($existing['step'] ?? '');
        $progress = json_decode($existing['progress_json'] ?? 'null', true) ?: [];
        $knownOfferId = (int)($existing['offer_id'] ?? ($progress['offerId'] ?? 0));

        if ($barcode !== '') {
            $bound = $this->lookupOfferByBarcode($barcode);
            if ($bound['unverified']) {
                throw new TsdGuardException(
                    'Не удалось подтвердить владельца штрихкода. Изменения заблокированы до сверки.',
                    'RECONCILIATION_REQUIRED',
                    409
                );
            }
            if ($bound['offerId']) {
                $this->assertParent($bound['offerId'], $parentId);
                $result = $this->committedResult($bound['offerId'], $barcode, $operationId, false);
                $this->commit($opKey, $operationId, $payloadHash, $bound['offerId'], $result, $localItemId, 'reused barcode owner after interrupt');
                return $result;
            }
        }

        if ($knownOfferId > 0) {
            $this->assertParent($knownOfferId, $parentId);
            if ($barcode !== '') {
                $this->bindBarcode($knownOfferId, $barcode, $opKey, $localItemId);
            }
            $result = $this->committedResult($knownOfferId, $barcode, $operationId, true);
            $this->commit($opKey, $operationId, $payloadHash, $knownOfferId, $result, $localItemId, 'resumed known offerId');
            return $result;
        }

        if (in_array($step, ['OFFER_ADD_PENDING', 'OFFER_CREATED'], true) && $knownOfferId <= 0) {
            throw new TsdGuardException(
                'Предыдущая попытка создания вариации прервалась с неизвестным результатом. Новая вариация не создаётся до сверки.',
                'RECONCILIATION_REQUIRED',
                409
            );
        }

        return $this->createOrReuse($opKey, $operationId, $parentId, $barcode, $fields, $payloadHash, $localItemId, $existing);
    }

    private function createOrReuse(
        string $opKey,
        string $operationId,
        int $parentId,
        string $barcode,
        array $fields,
        string $payloadHash,
        string $localItemId,
        $existing
    ): array {
        if ($barcode !== '') {
            $bound = $this->lookupOfferByBarcode($barcode);
            if ($bound['unverified']) {
                throw new TsdGuardException(
                    'Не удалось проверить штрихкод на сервере Битрикс.',
                    'RECONCILIATION_REQUIRED',
                    409
                );
            }
            if ($bound['offerId']) {
                $this->assertParent($bound['offerId'], $parentId);
                $result = $this->committedResult($bound['offerId'], $barcode, $operationId, false);
                $this->commit($opKey, $operationId, $payloadHash, $bound['offerId'], $result, $localItemId, 'barcode already bound');
                return $result;
            }
        }

        $this->writeOp($opKey, $operationId, $payloadHash, 'STARTED', 'OFFER_ADD_PENDING', null, ['barcode' => $barcode]);
        $this->step($opKey, 'OFFER_ADD_PENDING', 'STARTED', null, $localItemId, 'calling catalog.product.offer.add');

        $fields['parentId'] = $parentId;
        $created = $this->requireOk('catalog.product.offer.add', ['fields' => $fields], 'OFFER_ADD_FAILED');
        $offerId = (int)($created['offer']['id'] ?? $created['id'] ?? 0);
        if ($offerId <= 0) {
            throw new TsdGuardException('offer.add did not return offer id', 'OFFER_ADD_FAILED', 500);
        }

        $this->writeOp($opKey, $operationId, $payloadHash, 'STARTED', 'OFFER_CREATED', $offerId, ['offerId' => $offerId]);
        $this->step($opKey, 'OFFER_CREATED', 'SUCCEEDED', $offerId, $localItemId, 'offer created');

        if ($barcode !== '') {
            $this->bindBarcode($offerId, $barcode, $opKey, $localItemId);
        }

        $result = $this->committedResult($offerId, $barcode, $operationId, true);
        $this->commit($opKey, $operationId, $payloadHash, $offerId, $result, $localItemId, 'created');
        return $result;
    }

    private function bindBarcode(int $offerId, string $barcode, string $opKey, string $localItemId): void
    {
        $again = $this->lookupOfferByBarcode($barcode);
        if ($again['offerId'] && $again['offerId'] !== $offerId) {
            $this->assertParent($again['offerId'], 0, true);
            throw new TsdGuardException(
                'Barcode belongs to another product',
                'BARCODE_FOREIGN_CONFLICT',
                409
            );
        }
        if ($again['offerId'] === $offerId) {
            $this->step($opKey, 'BARCODE_BOUND', 'SKIPPED_REUSED', $offerId, $localItemId, 'already bound');
            return;
        }
        $this->step($opKey, 'BARCODE_BIND_PENDING', 'STARTED', $offerId, $localItemId, 'calling barcode.setbyproductid');
        $bind = $this->bitrix->call('barcode.setbyproductid.json', [
            'productid' => $offerId,
            'barcode' => $barcode,
        ]);
        if (!$bind['ok'] || $bind['result'] === false) {
            $recheck = $this->lookupOfferByBarcode($barcode);
            if ($recheck['offerId'] === $offerId) {
                $this->step($opKey, 'BARCODE_BOUND', 'SUCCEEDED', $offerId, $localItemId, 'confirmed after uncertain bind');
                return;
            }
            if ($recheck['offerId'] && $recheck['offerId'] !== $offerId) {
                throw new TsdGuardException('Barcode belongs to another product', 'BARCODE_FOREIGN_CONFLICT', 409);
            }
            throw new TsdGuardException(
                $bind['errorDescription'] ?: 'barcode bind failed',
                'BARCODE_BIND_FAILED',
                409
            );
        }
        $this->step($opKey, 'BARCODE_BOUND', 'SUCCEEDED', $offerId, $localItemId, 'barcode bound');
    }

    /**
     * @return array{offerId:?int,unverified:bool}
     */
    private function lookupOfferByBarcode(string $barcode): array
    {
        $resp = $this->bitrix->call('barcode.getproductid.json', ['barcode' => $barcode]);
        if (!$resp['ok'] || !$resp['verified']) {
            return ['offerId' => null, 'unverified' => true];
        }
        $raw = $resp['result'];
        if ($raw === null || $raw === '' || $raw === '0' || $raw === false) {
            return ['offerId' => null, 'unverified' => false];
        }
        if (is_array($raw)) {
            $raw = $raw['result'] ?? $raw['id'] ?? null;
        }
        $id = (int)$raw;
        return ['offerId' => $id > 0 ? $id : null, 'unverified' => false];
    }

    private function assertParent(int $offerId, int $expectedParentId, bool $conflictOnly = false): void
    {
        $resp = $this->bitrix->call('catalog.product.get', ['id' => (string)$offerId]);
        if (!$resp['ok'] || !$resp['verified']) {
            throw new TsdGuardException(
                'Не удалось проверить принадлежность вариации.',
                'RECONCILIATION_REQUIRED',
                409
            );
        }
        $product = $resp['result']['product'] ?? $resp['result'];
        $parent = $product['parentId']['value'] ?? $product['parentId'] ?? null;
        $actual = $parent !== null ? (int)$parent : null;
        if ($conflictOnly) {
            return;
        }
        if ($actual !== null && $expectedParentId > 0 && $actual !== $expectedParentId) {
            throw new TsdGuardException('Barcode belongs to another product', 'BARCODE_FOREIGN_CONFLICT', 409);
        }
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

    private function committedResult(int $offerId, string $barcode, string $operationId, bool $created): array
    {
        return [
            'offerId' => $offerId,
            'barcode' => $barcode,
            'created' => $created,
            'operationId' => $operationId,
        ];
    }

    private function commit(
        string $opKey,
        string $operationId,
        string $payloadHash,
        int $offerId,
        array $result,
        string $localItemId,
        string $details
    ): void {
        $this->writeOp($opKey, $operationId, $payloadHash, 'COMMITTED', 'COMMITTED', $offerId, $result, $result);
        $this->step($opKey, 'COMMITTED', 'SUCCEEDED', $offerId, $localItemId, $details);
    }

    private function writeOp(
        string $opKey,
        string $operationId,
        string $payloadHash,
        string $state,
        string $step,
        $offerId,
        $progress,
        $result = null
    ): void {
        $this->store->saveOperation([
            'op_key' => $opKey,
            'operation_id' => $operationId,
            'kind' => 'VARIATION',
            'document_id' => null,
            'payload_hash' => $payloadHash,
            'state' => $state,
            'step' => $step,
            'offer_id' => $offerId,
            'progress_json' => $progress,
            'result_json' => $result,
        ]);
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
