<?php

/**
 * Server-side lock + idempotency for TSD incoming documents.
 * Deploy to the Bitrix portal; do not treat as live until installed.
 */
class TsdGuardService
{
    public function getLock(string $name, int $timeoutSeconds = 10): bool
    {
        global $DB;
        $name = $DB->ForSql($name);
        $res = $DB->Query("SELECT GET_LOCK('{$name}', {$timeoutSeconds}) AS l");
        $row = $res ? $res->Fetch() : false;
        return $row && (int)$row['l'] === 1;
    }

    public function releaseLock(string $name): void
    {
        global $DB;
        $name = $DB->ForSql($name);
        $DB->Query("SELECT RELEASE_LOCK('{$name}')");
    }

    public function loadOperation(string $opKey): ?array
    {
        global $DB;
        $opKey = $DB->ForSql($opKey);
        $res = $DB->Query("SELECT * FROM tsd_operation WHERE op_key = '{$opKey}' LIMIT 1");
        $row = $res ? $res->Fetch() : false;
        return $row ?: null;
    }

    public function saveOperation(
        string $opKey,
        string $operationId,
        string $kind,
        ?int $documentId,
        string $payloadHash,
        string $state,
        $result
    ): void {
        global $DB;
        $now = date('Y-m-d H:i:s');
        $json = $result === null ? 'NULL' : "'" . $DB->ForSql(json_encode($result, JSON_UNESCAPED_UNICODE)) . "'";
        $doc = $documentId === null ? 'NULL' : (int)$documentId;
        $existing = $this->loadOperation($opKey);
        if ($existing) {
            $DB->Query(
                "UPDATE tsd_operation SET payload_hash='{$DB->ForSql($payloadHash)}', state='{$DB->ForSql($state)}', " .
                "result_json={$json}, updated_at='{$now}' WHERE op_key='{$DB->ForSql($opKey)}'"
            );
            return;
        }
        $DB->Query(
            "INSERT INTO tsd_operation (op_key, operation_id, kind, document_id, payload_hash, state, result_json, created_at, updated_at) " .
            "VALUES ('{$DB->ForSql($opKey)}', '{$DB->ForSql($operationId)}', '{$DB->ForSql($kind)}', {$doc}, " .
            "'{$DB->ForSql($payloadHash)}', '{$DB->ForSql($state)}', {$json}, '{$now}', '{$now}')"
        );
    }

    public function findMappedElement(string $operationId, string $localItemId, int $documentId): ?int
    {
        global $DB;
        $res = $DB->Query(
            "SELECT bitrix_element_id FROM tsd_document_item WHERE operation_id='{$DB->ForSql($operationId)}' " .
            "AND local_item_id='{$DB->ForSql($localItemId)}' AND document_id=" . (int)$documentId . " LIMIT 1"
        );
        $row = $res ? $res->Fetch() : false;
        return $row ? (int)$row['bitrix_element_id'] : null;
    }

    public function mapElement(string $operationId, string $localItemId, int $documentId, int $elementId, int $offerId): void
    {
        global $DB;
        $nowOp = $DB->ForSql($operationId);
        $nowLocal = $DB->ForSql($localItemId);
        $DB->Query(
            "INSERT INTO tsd_document_item (operation_id, local_item_id, document_id, bitrix_element_id, offer_id) " .
            "VALUES ('{$nowOp}', '{$nowLocal}', " . (int)$documentId . ", " . (int)$elementId . ", " . (int)$offerId . ") " .
            "ON DUPLICATE KEY UPDATE bitrix_element_id=" . (int)$elementId . ", offer_id=" . (int)$offerId
        );
    }
}
