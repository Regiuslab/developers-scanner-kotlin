<?php

class InMemoryLockManager implements TsdLockManager
{
    public $held = [];
    public $heldByOther = [];
    public $acquireCalls = 0;
    public $releaseCalls = 0;

    public function acquire(string $name, int $timeoutSeconds = 10): bool
    {
        $this->acquireCalls++;
        if (!empty($this->heldByOther[$name])) {
            return false;
        }
        $this->held[$name] = true;
        return true;
    }

    public function release(string $name): void
    {
        $this->releaseCalls++;
        unset($this->held[$name]);
    }

    public function isHeldByOther(string $name): bool
    {
        return !empty($this->heldByOther[$name]);
    }
}

class InMemoryOperationStore implements TsdOperationStore
{
    public $operations = [];
    public $steps = [];
    public $items = [];

    public function loadOperation(string $opKey): ?array
    {
        return $this->operations[$opKey] ?? null;
    }

    public function saveOperation(array $record): void
    {
        $progress = $record['progress_json'] ?? null;
        if (is_array($progress)) {
            $progress = json_encode($progress, JSON_UNESCAPED_UNICODE);
        }
        $result = $record['result_json'] ?? null;
        if (is_array($result)) {
            $result = json_encode($result, JSON_UNESCAPED_UNICODE);
        }
        $this->operations[$record['op_key']] = [
            'op_key' => $record['op_key'],
            'operation_id' => $record['operation_id'],
            'kind' => $record['kind'],
            'document_id' => $record['document_id'] ?? null,
            'payload_hash' => $record['payload_hash'],
            'state' => $record['state'],
            'step' => $record['step'] ?? '',
            'offer_id' => $record['offer_id'] ?? null,
            'progress_json' => $progress,
            'result_json' => $result,
            'updated_at' => date('Y-m-d H:i:s'),
        ];
    }

    public function appendStep(array $entry): void
    {
        $this->steps[$entry['op_key']][] = $entry;
    }

    public function steps(string $opKey): array
    {
        return $this->steps[$opKey] ?? [];
    }

    public function findMappedElement(string $operationId, string $localItemId, int $documentId): ?array
    {
        $key = $operationId . '|' . $localItemId . '|' . $documentId;
        return $this->items[$key] ?? null;
    }

    public function mapElement(string $operationId, string $localItemId, int $documentId, int $elementId, int $offerId): void
    {
        $key = $operationId . '|' . $localItemId . '|' . $documentId;
        $this->items[$key] = [
            'operation_id' => $operationId,
            'local_item_id' => $localItemId,
            'document_id' => $documentId,
            'bitrix_element_id' => $elementId,
            'offer_id' => $offerId,
        ];
    }
}

class FakeBitrixGateway implements TsdBitrixGateway
{
    public $offers = [];
    public $barcodes = [];
    public $documents = [];
    public $elements = [];
    public $stores = [['id' => 1]];
    public $nextOfferId = 5000;
    public $nextElementId = 8000;
    public $mutating = [];
    public $calls = [];
    public $crashBefore = null;
    public $crashAfter = null;
    public $failMethod = null;
    public $unverifiedMethod = null;
    public $omitDocumentsKey = false;
    public $nullDocumentStatus = false;
    public $http500 = false;
    public $timeout = false;
    public $stockMoved = [];

    public function seedDocument(int $id, string $status = 'N'): void
    {
        $this->documents[$id] = ['id' => $id, 'docType' => 'A', 'status' => $status];
        $this->elements[$id] = $this->elements[$id] ?? [];
    }

    public function seedOffer(int $id, int $parentId, $barcode = null): void
    {
        $this->offers[$id] = [
            'id' => $id,
            'parentId' => ['value' => $parentId],
            'name' => 'Offer ' . $id,
        ];
        if ($barcode) {
            $this->barcodes[$barcode] = $id;
        }
    }

    public function call(string $method, array $params): array
    {
        $this->calls[] = $method;
        if ($this->timeout) {
            return ['ok' => false, 'result' => null, 'error' => 'TIMEOUT', 'errorDescription' => 'timeout', 'httpCode' => 0, 'verified' => false];
        }
        if ($this->http500 && $method === 'catalog.document.list') {
            return ['ok' => false, 'result' => null, 'error' => 'HTTP_500', 'errorDescription' => 'server', 'httpCode' => 500, 'verified' => false];
        }
        if ($this->unverifiedMethod === $method) {
            return ['ok' => false, 'result' => null, 'error' => 'UNVERIFIED', 'errorDescription' => 'unverified', 'httpCode' => 500, 'verified' => false];
        }
        if ($this->failMethod === $method) {
            return ['ok' => false, 'result' => ['error' => 'FAIL'], 'error' => 'FAIL', 'errorDescription' => 'forced', 'httpCode' => 500, 'verified' => true];
        }
        if ($this->crashBefore === $method) {
            $this->crashBefore = null;
            throw new \RuntimeException('crash before ' . $method);
        }

        $mutating = in_array($method, [
            'catalog.product.offer.add',
            'barcode.setbyproductid.json',
            'catalog.document.element.add',
            'catalog.document.element.update',
            'catalog.document.element.delete',
            'catalog.document.conduct',
        ], true);
        if ($mutating) {
            $this->mutating[] = $method;
        }

        $result = $this->dispatch($method, $params);

        if ($this->crashAfter === $method) {
            $this->crashAfter = null;
            throw new \RuntimeException('crash after ' . $method);
        }
        return [
            'ok' => true,
            'result' => $result,
            'error' => null,
            'errorDescription' => null,
            'httpCode' => 200,
            'verified' => true,
        ];
    }

    private function dispatch(string $method, array $params)
    {
        switch ($method) {
            case 'barcode.getproductid.json':
                $bc = (string)($params['barcode'] ?? '');
                return $this->barcodes[$bc] ?? '';
            case 'catalog.product.get':
                $id = (int)$params['id'];
                if (!isset($this->offers[$id])) {
                    return ['product' => null];
                }
                return ['product' => $this->offers[$id]];
            case 'catalog.product.offer.add':
                $id = $this->nextOfferId++;
                $parent = (int)($params['fields']['parentId'] ?? 0);
                $this->offers[$id] = [
                    'id' => $id,
                    'parentId' => ['value' => $parent],
                    'name' => $params['fields']['name'] ?? 'new',
                ];
                return ['offer' => ['id' => $id]];
            case 'barcode.setbyproductid.json':
                $bc = (string)$params['barcode'];
                $pid = (int)$params['productid'];
                if (isset($this->barcodes[$bc]) && $this->barcodes[$bc] !== $pid) {
                    return false;
                }
                $this->barcodes[$bc] = $pid;
                return true;
            case 'catalog.store.list':
                return ['stores' => $this->stores];
            case 'catalog.document.list':
                if ($this->omitDocumentsKey) {
                    return [];
                }
                $id = (int)($params['filter']['id'] ?? 0);
                if (!isset($this->documents[$id])) {
                    return ['documents' => []];
                }
                $doc = $this->documents[$id];
                if ($this->nullDocumentStatus) {
                    $doc['status'] = null;
                }
                return ['documents' => [$doc]];
            case 'catalog.document.element.list':
                $docId = (int)($params['filter']['docId'] ?? 0);
                return ['documentElements' => $this->elements[$docId] ?? []];
            case 'catalog.document.element.add':
                $docId = (int)$params['fields']['docId'];
                $id = $this->nextElementId++;
                $row = [
                    'id' => $id,
                    'docId' => $docId,
                    'elementId' => (int)$params['fields']['elementId'],
                    'amount' => (float)$params['fields']['amount'],
                    'storeTo' => (int)$params['fields']['storeTo'],
                ];
                $this->elements[$docId][] = $row;
                return ['documentElement' => ['id' => $id]];
            case 'catalog.document.element.update':
                $id = (int)$params['id'];
                $docId = (int)$params['fields']['docId'];
                foreach ($this->elements[$docId] as &$row) {
                    if ((int)$row['id'] === $id) {
                        $row['amount'] = (float)$params['fields']['amount'];
                        $row['storeTo'] = (int)$params['fields']['storeTo'];
                    }
                }
                unset($row);
                return true;
            case 'catalog.document.element.delete':
                $id = (int)$params['id'];
                $docId = (int)$params['fields']['docId'];
                $this->elements[$docId] = array_values(array_filter(
                    $this->elements[$docId] ?? [],
                    function ($row) use ($id) {
                        return (int)$row['id'] !== $id;
                    }
                ));
                return true;
            case 'catalog.document.conduct':
                $id = (int)$params['id'];
                if (($this->documents[$id]['status'] ?? '') === 'Y') {
                    return ['error' => 'DOCUMENT_ALREADY_CONDUCTED'];
                }
                $this->documents[$id]['status'] = 'Y';
                $sum = 0.0;
                foreach ($this->elements[$id] ?? [] as $row) {
                    $sum += (float)$row['amount'];
                }
                $this->stockMoved[$id] = $sum;
                return true;
            default:
                throw new \RuntimeException('unknown method ' . $method);
        }
    }
}
