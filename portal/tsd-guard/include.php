<?php
/**
 * Registers TSD guard REST methods. Require from /local/php_interface/init.php
 * AFTER Bitrix prologue (B_PROLOG_INCLUDED).
 */
require_once __DIR__ . '/lib/TsdGuardException.php';
require_once __DIR__ . '/lib/TsdBitrixGateway.php';
require_once __DIR__ . '/lib/TsdPersistence.php';
require_once __DIR__ . '/lib/TsdVariationEngine.php';
require_once __DIR__ . '/lib/TsdDocumentEngine.php';
require_once __DIR__ . '/lib/TsdGuardService.php';
require_once __DIR__ . '/lib/TsdGuardRest.php';

if (function_exists('AddEventHandler')) {
    AddEventHandler('rest', 'OnRestServiceBuildDescription', static function () {
        return [
            'tsd' => [
                'tsd.variation.resolveOrCreate' => [
                    'callback' => ['TsdGuardRest', 'resolveOrCreateVariation'],
                    'options' => [],
                ],
                'tsd.document.syncAndConduct' => [
                    'callback' => ['TsdGuardRest', 'syncAndConductDocument'],
                    'options' => [],
                ],
            ],
        ];
    });
}
