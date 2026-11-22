<?php
/**
 * Registers TSD guard REST methods. Not active until this file is required from
 * /local/php_interface/init.php on the Bitrix portal.
 */
if (!defined('B_PROLOG_INCLUDED') || B_PROLOG_INCLUDED !== true) {
    // Allow loading from init.php after prologue.
}

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

require_once __DIR__ . '/lib/TsdGuardService.php';
require_once __DIR__ . '/lib/TsdGuardRest.php';
