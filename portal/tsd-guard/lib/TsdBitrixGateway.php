<?php

/**
 * Catalog/document operations used by TSD guard. Production talks to Bitrix;
 * tests inject a fake. HTTP 200 is never treated as success by itself.
 */
interface TsdBitrixGateway
{
    /**
     * @return array{ok:bool,result:mixed,error:?string,errorDescription:?string,httpCode:int,verified:bool}
     */
    public function call(string $method, array $params): array;
}

class TsdBitrixRestGateway implements TsdBitrixGateway
{
    public function call(string $method, array $params): array
    {
        try {
            $raw = $this->invoke($method, $params);
        } catch (\Throwable $e) {
            return [
                'ok' => false,
                'result' => null,
                'error' => 'NETWORK_OR_RUNTIME',
                'errorDescription' => $e->getMessage(),
                'httpCode' => 500,
                'verified' => false,
            ];
        }
        if ($raw === null) {
            return [
                'ok' => false,
                'result' => null,
                'error' => 'EMPTY_RESPONSE',
                'errorDescription' => 'Bitrix returned an empty response',
                'httpCode' => 500,
                'verified' => false,
            ];
        }
        if (is_array($raw) && (!empty($raw['error']) || !empty($raw['error_description']))) {
            return [
                'ok' => false,
                'result' => $raw,
                'error' => (string)($raw['error'] ?? 'BITRIX_ERROR'),
                'errorDescription' => (string)($raw['error_description'] ?? $raw['error'] ?? ''),
                'httpCode' => 500,
                'verified' => true,
            ];
        }
        $result = is_array($raw) && array_key_exists('result', $raw) ? $raw['result'] : $raw;
        return [
            'ok' => true,
            'result' => $result,
            'error' => null,
            'errorDescription' => null,
            'httpCode' => 200,
            'verified' => true,
        ];
    }

    private function invoke(string $method, array $params)
    {
        if (class_exists('CRest')) {
            return \CRest::call($method, $params);
        }
        throw new TsdGuardException(
            'Neither CRest nor a test gateway is available. Install rest/crest or inject TsdBitrixGateway.',
            'CREST_MISSING',
            500
        );
    }
}
