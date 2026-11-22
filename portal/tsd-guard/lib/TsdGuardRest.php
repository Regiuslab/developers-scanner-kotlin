<?php

use Bitrix\Rest\RestException;

/**
 * REST callbacks. Uses CRest / catalog REST inside GET_LOCK so two TSD devices
 * serialize on the portal, not on Android Mutex.
 */
class TsdGuardRest
{
    public static function resolveOrCreateVariation(array $query, $n, \CRestServer $server)
    {
        $svc = new TsdGuardService();
        $operationId = trim((string)($query['operationId'] ?? ''));
        $parentId = (int)($query['parentProductId'] ?? 0);
        $barcode = trim((string)($query['barcode'] ?? ''));
        $localItemId = trim((string)($query['localItemId'] ?? ''));
        $fields = is_array($query['offerFields'] ?? null) ? $query['offerFields'] : [];

        if ($operationId === '' || $parentId <= 0 || $localItemId === '') {
            throw new RestException('Invalid request', 'INVALID_REQUEST', 400);
        }

        $payloadHash = sha1($parentId . '|' . $barcode . '|' . $localItemId);
        $opKey = 'V:' . $operationId . ':' . $localItemId;
        $lockName = 'tsd_barcode_' . ($barcode !== '' ? $barcode : $localItemId);

        if (!$svc->getLock($lockName)) {
            throw new RestException('Lock timeout', 'LOCK_TIMEOUT', 423);
        }
        try {
            $existing = $svc->loadOperation($opKey);
            if ($existing && $existing['state'] === 'COMMITTED') {
                if ($existing['payload_hash'] !== $payloadHash) {
                    throw new RestException('operationId reused with different payload', 'OPERATION_PAYLOAD_CONFLICT', 409);
                }
                $saved = json_decode($existing['result_json'], true);
                return is_array($saved) ? $saved : $saved;
            }
            if ($existing && $existing['state'] === 'STARTED') {
                throw new RestException('Operation in progress', 'OPERATION_IN_PROGRESS', 409);
            }

            $svc->saveOperation($opKey, $operationId, 'VARIATION', null, $payloadHash, 'STARTED', null);

            $boundOfferId = null;
            if ($barcode !== '') {
                $boundOfferId = self::lookupOfferByBarcode($barcode);
            }
            if ($boundOfferId) {
                $boundParent = self::offerParentId($boundOfferId);
                if ($boundParent !== null && $boundParent !== $parentId) {
                    throw new RestException('Barcode belongs to another product', 'BARCODE_FOREIGN_CONFLICT', 409);
                }
                $result = [
                    'offerId' => $boundOfferId,
                    'barcode' => $barcode,
                    'created' => false,
                    'operationId' => $operationId,
                ];
                $svc->saveOperation($opKey, $operationId, 'VARIATION', null, $payloadHash, 'COMMITTED', $result);
                return $result;
            }

            $fields['parentId'] = $parentId;
            $created = self::rest('catalog.product.offer.add', ['fields' => $fields]);
            $offerId = (int)($created['offer']['id'] ?? $created['id'] ?? 0);
            if ($offerId <= 0) {
                throw new RestException('offer.add failed', 'OFFER_ADD_FAILED', 500);
            }
            if ($barcode !== '') {
                $bind = self::rest('barcode.setbyproductid.json', [
                    'productid' => $offerId,
                    'barcode' => $barcode,
                ]);
                if ($bind === false) {
                    throw new RestException('barcode bind failed', 'BARCODE_BIND_FAILED', 409);
                }
            }
            $result = [
                'offerId' => $offerId,
                'barcode' => $barcode,
                'created' => true,
                'operationId' => $operationId,
            ];
            $svc->saveOperation($opKey, $operationId, 'VARIATION', null, $payloadHash, 'COMMITTED', $result);
            return $result;
        } finally {
            $svc->releaseLock($lockName);
        }
    }

    public static function syncAndConductDocument(array $query, $n, \CRestServer $server)
    {
        $svc = new TsdGuardService();
        $operationId = trim((string)($query['operationId'] ?? ''));
        $documentId = (int)($query['documentId'] ?? 0);
        $items = is_array($query['items'] ?? null) ? $query['items'] : [];
        $deleted = is_array($query['deletedRemoteElementIds'] ?? null) ? $query['deletedRemoteElementIds'] : [];

        if ($operationId === '' || $documentId <= 0) {
            throw new RestException('Invalid request', 'INVALID_REQUEST', 400);
        }

        $payloadParts = [];
        foreach ($items as $item) {
            $payloadParts[] = ($item['localItemId'] ?? '') . ':' . ($item['offerId'] ?? '') . ':' .
                ($item['quantity'] ?? '') . ':' . ($item['storeTo'] ?? '') . ':' . ($item['batchNumber'] ?? '');
        }
        sort($payloadParts);
        $payloadHash = sha1(implode(';', $payloadParts));
        $opKey = 'D:' . $operationId . ':' . $documentId;
        $lockName = 'tsd_doc_' . $documentId;

        if (!$svc->getLock($lockName)) {
            throw new RestException('Lock timeout', 'LOCK_TIMEOUT', 423);
        }
        try {
            $existing = $svc->loadOperation($opKey);
            if ($existing && $existing['state'] === 'COMMITTED') {
                if ($existing['payload_hash'] !== $payloadHash) {
                    throw new RestException('operationId reused with different payload', 'OPERATION_PAYLOAD_CONFLICT', 409);
                }
                return json_decode($existing['result_json'], true);
            }

            $status = self::documentStatus($documentId);
            if ($status === 'Y') {
                $result = self::alreadyConductedResult($operationId, $documentId, $items, $svc);
                $svc->saveOperation($opKey, $operationId, 'DOCUMENT', $documentId, $payloadHash, 'COMMITTED', $result);
                return $result;
            }

            $svc->saveOperation($opKey, $operationId, 'DOCUMENT', $documentId, $payloadHash, 'STARTED', null);

            foreach ($deleted as $delId) {
                self::rest('catalog.document.element.delete', [
                    'id' => (int)$delId,
                    'fields' => ['docId' => $documentId],
                ]);
            }

            $synced = [];
            $elementIds = [];
            foreach ($items as $item) {
                $localItemId = (string)($item['localItemId'] ?? '');
                $offerId = (int)($item['offerId'] ?? 0);
                $qty = (float)($item['quantity'] ?? 0);
                $storeTo = (int)($item['storeTo'] ?? 1);
                if ($localItemId === '' || $offerId <= 0) {
                    continue;
                }
                $mapped = $svc->findMappedElement($operationId, $localItemId, $documentId);
                if ($mapped) {
                    self::rest('catalog.document.element.update', [
                        'id' => $mapped,
                        'fields' => [
                            'docId' => $documentId,
                            'storeTo' => $storeTo,
                            'amount' => (string)$qty,
                        ],
                    ]);
                    $rowId = $mapped;
                } else {
                    $added = self::rest('catalog.document.element.add', [
                        'fields' => [
                            'docId' => $documentId,
                            'storeFrom' => 0,
                            'storeTo' => $storeTo,
                            'elementId' => $offerId,
                            'amount' => (string)$qty,
                            'purchasingPrice' => 0,
                        ],
                    ]);
                    $rowId = (int)($added['documentElement']['id'] ?? $added['id'] ?? 0);
                    if ($rowId > 0) {
                        $svc->mapElement($operationId, $localItemId, $documentId, $rowId, $offerId);
                    }
                }
                $elementIds[] = $rowId;
                $synced[] = [
                    'localItemId' => $localItemId,
                    'offerId' => $offerId,
                    'elementId' => $rowId,
                    'quantity' => $qty,
                ];
            }

            self::rest('catalog.document.conduct', ['id' => $documentId]);
            $finalStatus = self::documentStatus($documentId);
            if ($finalStatus !== 'Y') {
                throw new RestException('Document status is not Y after conduct', 'CONDUCT_UNCONFIRMED', 500);
            }
            $result = [
                'documentId' => $documentId,
                'status' => 'Y',
                'alreadyConducted' => false,
                'elementIds' => $elementIds,
                'items' => $synced,
                'operationId' => $operationId,
            ];
            $svc->saveOperation($opKey, $operationId, 'DOCUMENT', $documentId, $payloadHash, 'COMMITTED', $result);
            return $result;
        } finally {
            $svc->releaseLock($lockName);
        }
    }

    private static function alreadyConductedResult(string $operationId, int $documentId, array $items, TsdGuardService $svc): array
    {
        $synced = [];
        $elementIds = [];
        foreach ($items as $item) {
            $localItemId = (string)($item['localItemId'] ?? '');
            $mapped = $svc->findMappedElement($operationId, $localItemId, $documentId) ?: 0;
            $synced[] = [
                'localItemId' => $localItemId,
                'offerId' => (int)($item['offerId'] ?? 0),
                'elementId' => $mapped,
                'quantity' => (float)($item['quantity'] ?? 0),
            ];
            if ($mapped > 0) {
                $elementIds[] = $mapped;
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

    private static function lookupOfferByBarcode(string $barcode): ?int
    {
        $raw = self::rest('barcode.getproductid.json', ['barcode' => $barcode]);
        if ($raw === null || $raw === '' || $raw === '0' || $raw === false) {
            return null;
        }
        if (is_array($raw)) {
            $raw = $raw['result'] ?? $raw['id'] ?? null;
        }
        $id = (int)$raw;
        return $id > 0 ? $id : null;
    }

    private static function offerParentId(int $offerId): ?int
    {
        $raw = self::rest('catalog.product.get', ['id' => (string)$offerId]);
        $product = $raw['product'] ?? $raw;
        $parent = $product['parentId']['value'] ?? $product['parentId'] ?? null;
        return $parent !== null ? (int)$parent : null;
    }

    private static function documentStatus(int $documentId): ?string
    {
        $raw = self::rest('catalog.document.list', [
            'filter' => ['id' => $documentId, 'docType' => 'A'],
        ]);
        $docs = $raw['documents'] ?? [];
        if (!$docs) {
            return null;
        }
        return (string)($docs[0]['status'] ?? '');
    }

    private static function rest(string $method, array $params)
    {
        if (!class_exists('CRest')) {
            throw new RestException('CRest is not available on this portal', 'CREST_MISSING', 500);
        }
        $out = \CRest::call($method, $params);
        if (!is_array($out)) {
            return $out;
        }
        if (!empty($out['error'])) {
            throw new RestException(
                (string)($out['error_description'] ?? $out['error']),
                (string)$out['error'],
                500
            );
        }
        return $out['result'] ?? $out;
    }
}
