<?php

interface TsdLockManager
{
    public function acquire(string $name, int $timeoutSeconds = 10): bool;

    public function release(string $name): void;

    public function isHeldByOther(string $name): bool;
}

interface TsdOperationStore
{
    public function loadOperation(string $opKey): ?array;

    public function saveOperation(array $record): void;

    public function appendStep(array $entry): void;

    public function steps(string $opKey): array;

    public function findMappedElement(string $operationId, string $localItemId, int $documentId): ?array;

    public function mapElement(
        string $operationId,
        string $localItemId,
        int $documentId,
        int $elementId,
        int $offerId
    ): void;
}

class TsdMysqlLockManager implements TsdLockManager
{
    public function acquire(string $name, int $timeoutSeconds = 10): bool
    {
        global $DB;
        $name = $DB->ForSql($name);
        $res = $DB->Query("SELECT GET_LOCK('{$name}', {$timeoutSeconds}) AS l");
        $row = $res ? $res->Fetch() : false;
        return $row && (int)$row['l'] === 1;
    }

    public function release(string $name): void
    {
        global $DB;
        $name = $DB->ForSql($name);
        $DB->Query("SELECT RELEASE_LOCK('{$name}')");
    }

    public function isHeldByOther(string $name): bool
    {
        global $DB;
        $name = $DB->ForSql($name);
        $res = $DB->Query("SELECT IS_USED_LOCK('{$name}') AS owner, IS_USED_LOCK('{$name}') = CONNECTION_ID() AS mine");
        $row = $res ? $res->Fetch() : false;
        if (!$row || $row['owner'] === null || $row['owner'] === '') {
            return false;
        }
        return empty($row['mine']) || (int)$row['mine'] === 0;
    }
}

class TsdMysqlOperationStore implements TsdOperationStore
{
    public function loadOperation(string $opKey): ?array
    {
        global $DB;
        $key = $DB->ForSql($opKey);
        $res = $DB->Query("SELECT * FROM tsd_operation WHERE op_key = '{$key}' LIMIT 1");
        $row = $res ? $res->Fetch() : false;
        return $row ?: null;
    }

    public function saveOperation(array $record): void
    {
        global $DB;
        $now = date('Y-m-d H:i:s');
        $opKey = $DB->ForSql($record['op_key']);
        $progress = isset($record['progress_json'])
            ? "'" . $DB->ForSql(is_string($record['progress_json']) ? $record['progress_json'] : json_encode($record['progress_json'], JSON_UNESCAPED_UNICODE)) . "'"
            : 'NULL';
        $result = array_key_exists('result_json', $record) && $record['result_json'] !== null
            ? "'" . $DB->ForSql(is_string($record['result_json']) ? $record['result_json'] : json_encode($record['result_json'], JSON_UNESCAPED_UNICODE)) . "'"
            : 'NULL';
        $doc = isset($record['document_id']) && $record['document_id'] !== null ? (int)$record['document_id'] : 'NULL';
        $offer = isset($record['offer_id']) && $record['offer_id'] !== null ? (int)$record['offer_id'] : 'NULL';
        $existing = $this->loadOperation($record['op_key']);
        if ($existing) {
            $DB->Query(
                "UPDATE tsd_operation SET payload_hash='{$DB->ForSql($record['payload_hash'])}', " .
                "state='{$DB->ForSql($record['state'])}', step='{$DB->ForSql($record['step'] ?? $existing['step'] ?? '')}', " .
                "offer_id={$offer}, progress_json={$progress}, result_json={$result}, updated_at='{$now}' " .
                "WHERE op_key='{$opKey}'"
            );
            return;
        }
        $DB->Query(
            "INSERT INTO tsd_operation (op_key, operation_id, kind, document_id, payload_hash, state, step, offer_id, progress_json, result_json, created_at, updated_at) VALUES (" .
            "'{$opKey}', '{$DB->ForSql($record['operation_id'])}', '{$DB->ForSql($record['kind'])}', {$doc}, " .
            "'{$DB->ForSql($record['payload_hash'])}', '{$DB->ForSql($record['state'])}', '{$DB->ForSql($record['step'] ?? '')}', " .
            "{$offer}, {$progress}, {$result}, '{$now}', '{$now}')"
        );
    }

    public function appendStep(array $entry): void
    {
        global $DB;
        $now = date('Y-m-d H:i:s');
        $remote = isset($entry['remote_entity_id']) && $entry['remote_entity_id'] !== null ? (int)$entry['remote_entity_id'] : 'NULL';
        $local = isset($entry['local_item_id']) ? "'" . $DB->ForSql($entry['local_item_id']) . "'" : 'NULL';
        $details = isset($entry['details']) ? "'" . $DB->ForSql($entry['details']) . "'" : 'NULL';
        $DB->Query(
            "INSERT INTO tsd_operation_step (op_key, step_type, status, remote_entity_id, local_item_id, details, created_at) VALUES (" .
            "'{$DB->ForSql($entry['op_key'])}', '{$DB->ForSql($entry['step_type'])}', '{$DB->ForSql($entry['status'])}', " .
            "{$remote}, {$local}, {$details}, '{$now}')"
        );
    }

    public function steps(string $opKey): array
    {
        global $DB;
        $res = $DB->Query("SELECT * FROM tsd_operation_step WHERE op_key='{$DB->ForSql($opKey)}' ORDER BY id ASC");
        $out = [];
        while ($res && ($row = $res->Fetch())) {
            $out[] = $row;
        }
        return $out;
    }

    public function findMappedElement(string $operationId, string $localItemId, int $documentId): ?array
    {
        global $DB;
        $res = $DB->Query(
            "SELECT * FROM tsd_document_item WHERE operation_id='{$DB->ForSql($operationId)}' " .
            "AND local_item_id='{$DB->ForSql($localItemId)}' AND document_id=" . (int)$documentId . " LIMIT 1"
        );
        $row = $res ? $res->Fetch() : false;
        return $row ?: null;
    }

    public function mapElement(string $operationId, string $localItemId, int $documentId, int $elementId, int $offerId): void
    {
        global $DB;
        $DB->Query(
            "INSERT INTO tsd_document_item (operation_id, local_item_id, document_id, bitrix_element_id, offer_id) VALUES (" .
            "'{$DB->ForSql($operationId)}', '{$DB->ForSql($localItemId)}', " . (int)$documentId . ", " . (int)$elementId . ", " . (int)$offerId . ") " .
            "ON DUPLICATE KEY UPDATE bitrix_element_id=" . (int)$elementId . ", offer_id=" . (int)$offerId
        );
    }
}
