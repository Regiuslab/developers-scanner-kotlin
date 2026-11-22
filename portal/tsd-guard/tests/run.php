<?php

$root = dirname(__DIR__);
require_once $root . '/lib/TsdGuardException.php';
require_once $root . '/lib/TsdBitrixGateway.php';
require_once $root . '/lib/TsdPersistence.php';
require_once $root . '/lib/TsdVariationEngine.php';
require_once $root . '/lib/TsdDocumentEngine.php';
require_once $root . '/tests/InMemorySupport.php';

$failures = 0;
$passes = 0;

function t_assert($cond, $message)
{
    global $failures, $passes;
    if ($cond) {
        $passes++;
        echo "  PASS  {$message}\n";
        return;
    }
    $failures++;
    echo "  FAIL  {$message}\n";
}

function engines(FakeBitrixGateway $bx, InMemoryOperationStore $store = null, InMemoryLockManager $locks = null): array
{
    $store = $store ?: new InMemoryOperationStore();
    $locks = $locks ?: new InMemoryLockManager();
    return [
        new TsdVariationEngine($store, $locks, $bx),
        new TsdDocumentEngine($store, $locks, $bx),
        $store,
        $locks,
        $bx,
    ];
}

function variationQuery($op, $parent, $barcode, $local = 'item-1'): array
{
    return [
        'operationId' => $op,
        'parentProductId' => $parent,
        'barcode' => $barcode,
        'localItemId' => $local,
        'offerFields' => ['iblockId' => 15, 'name' => 'Труба 63', 'measure' => 1],
    ];
}

function docItem($local, $offerId, $qty = 3, $store = 1, $batch = null): array
{
    return [
        'localItemId' => $local,
        'offerId' => $offerId,
        'quantity' => $qty,
        'storeTo' => $store,
        'batchNumber' => $batch,
    ];
}

echo "=== TSD guard PHP tests ===\n";

echo "\n[VARIATION recovery]\n";
$bx = new FakeBitrixGateway();
[$v] = engines($bx);
$r1 = $v->resolveOrCreate(variationQuery('op-v1', 51, '46001'));
t_assert($r1['offerId'] > 0, 'creates variation');
t_assert(count($bx->offers) === 1, 'one offer after create');
$r2 = $v->resolveOrCreate(variationQuery('op-v1', 51, '46001'));
t_assert($r1['offerId'] === $r2['offerId'] && count($bx->offers) === 1, 'idempotent same operationId');

$bx = new FakeBitrixGateway();
$store = new InMemoryOperationStore();
$locks = new InMemoryLockManager();
$v = new TsdVariationEngine($store, $locks, $bx);
$bx->crashAfter = 'none';
$v->resolveOrCreate(variationQuery('op-started', 51, '46002'));
$store->operations['V:op-started:item-1']['state'] = 'STARTED';
$store->operations['V:op-started:item-1']['step'] = 'STARTED';
$store->operations['V:op-started:item-1']['result_json'] = null;
$store->operations['V:op-started:item-1']['offer_id'] = null;
$resume = (new TsdVariationEngine($store, $locks, $bx))->resolveOrCreate(variationQuery('op-started', 51, '46002'));
t_assert($resume['offerId'] > 0 && count($bx->offers) === 1, 'resume after STARTED with nothing created reuses/create once');

$bx = new FakeBitrixGateway();
$store = new InMemoryOperationStore();
$locks = new InMemoryLockManager();
$v = new TsdVariationEngine($store, $locks, $bx);
$bx->crashAfter = 'catalog.product.offer.add';
try {
    $v->resolveOrCreate(variationQuery('op-offer-crash', 51, '46003'));
    t_assert(false, 'crash after offer.add throws');
} catch (\RuntimeException $e) {
    t_assert(strpos($e->getMessage(), 'crash after') !== false, 'crash after offer.add');
}
t_assert(count($bx->offers) === 1, 'offer exists after crash');
$op = $store->loadOperation('V:op-offer-crash:item-1');
t_assert($op['step'] === 'OFFER_ADD_PENDING' && empty($op['offer_id']), 'add result not confirmed in journal');
try {
    (new TsdVariationEngine($store, $locks, $bx))->resolveOrCreate(variationQuery('op-offer-crash', 51, '46003'));
    t_assert(false, 'must not create a second offer when add result is unknown');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'RECONCILIATION_REQUIRED' && count($bx->offers) === 1, 'resume after unknown offer.add blocks and keeps one offer');
}

$bx = new FakeBitrixGateway();
$store = new InMemoryOperationStore();
$v = new TsdVariationEngine($store, new InMemoryLockManager(), $bx);
$bx->crashAfter = 'barcode.setbyproductid.json';
try {
    $v->resolveOrCreate(variationQuery('op-bc-crash', 51, '46004'));
} catch (\RuntimeException $e) {
}
t_assert(count($bx->offers) === 1 && $bx->barcodes['46004'] > 0, 'barcode bound before crash');
$resume = (new TsdVariationEngine($store, new InMemoryLockManager(), $bx))->resolveOrCreate(variationQuery('op-bc-crash', 51, '46004'));
t_assert($resume['offerId'] === $bx->barcodes['46004'] && count($bx->offers) === 1, 'resume after barcode bind');

$bx = new FakeBitrixGateway();
$store = new InMemoryOperationStore();
$v = new TsdVariationEngine($store, new InMemoryLockManager(), $bx);
$ok = $v->resolveOrCreate(variationQuery('op-precommit', 51, '46005'));
$store->operations['V:op-precommit:item-1']['state'] = 'STARTED';
$store->operations['V:op-precommit:item-1']['step'] = 'BARCODE_BOUND';
$store->operations['V:op-precommit:item-1']['result_json'] = null;
$resume = (new TsdVariationEngine($store, new InMemoryLockManager(), $bx))->resolveOrCreate(variationQuery('op-precommit', 51, '46005'));
t_assert($resume['offerId'] === $ok['offerId'] && count($bx->offers) === 1, 'resume before COMMITTED write');

$bx = new FakeBitrixGateway();
$store = new InMemoryOperationStore();
$v = new TsdVariationEngine($store, new InMemoryLockManager(), $bx);
$bx->crashBefore = 'catalog.product.offer.add';
try {
    $v->resolveOrCreate(variationQuery('op-pending', 51, '46006'));
} catch (\RuntimeException $e) {
}
$op = $store->loadOperation('V:op-pending:item-1');
t_assert($op['step'] === 'OFFER_ADD_PENDING' && empty($op['offer_id']), 'OFFER_ADD_PENDING recorded');
try {
    (new TsdVariationEngine($store, new InMemoryLockManager(), $bx))->resolveOrCreate(variationQuery('op-pending', 51, '46006'));
    t_assert(false, 'unknown offer.add must not create again');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'RECONCILIATION_REQUIRED' && count($bx->offers) === 0, 'RECONCILIATION_REQUIRED if add result unknown');
}

$bx = new FakeBitrixGateway();
$bx->seedOffer(77, 88, 'foreign');
$v = new TsdVariationEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $v->resolveOrCreate(variationQuery('op-foreign', 51, 'foreign'));
    t_assert(false, 'foreign barcode rejected');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'BARCODE_FOREIGN_CONFLICT', 'BARCODE_FOREIGN_CONFLICT');
}

$bx = new FakeBitrixGateway();
$locks = new InMemoryLockManager();
$locks->heldByOther['tsd_barcode_46007'] = true;
$v = new TsdVariationEngine(new InMemoryOperationStore(), $locks, $bx);
try {
    $v->resolveOrCreate(variationQuery('op-busy', 51, '46007'));
    t_assert(false, 'in progress');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'OPERATION_IN_PROGRESS' && count($bx->mutating) === 0, 'OPERATION_IN_PROGRESS no mutations');
}

$bx = new FakeBitrixGateway();
$store = new InMemoryOperationStore();
$v = new TsdVariationEngine($store, new InMemoryLockManager(), $bx);
$first = $v->resolveOrCreate(variationQuery('op-payload', 51, '46008'));
try {
    $v->resolveOrCreate(variationQuery('op-payload', 99, '46008'));
    t_assert(false, 'payload conflict');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'OPERATION_PAYLOAD_CONFLICT' && count($bx->offers) === 1, 'payload conflict keeps one offer');
}

echo "\n[DOCUMENT status]\n";
$bx = new FakeBitrixGateway();
$bx->seedDocument(10, 'N');
$bx->seedOffer(432, 51);
$bx->nullDocumentStatus = true;
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd1', 'documentId' => 10, 'items' => [docItem('a', 432)]]);
    t_assert(false, 'null status blocked');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'DOCUMENT_STATUS_UNVERIFIED' && count($bx->mutating) === 0, 'null status: zero mutations');
}

$bx = new FakeBitrixGateway();
$bx->seedDocument(11, 'N');
$bx->seedOffer(432, 51);
$bx->omitDocumentsKey = true;
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd2', 'documentId' => 11, 'items' => [docItem('a', 432)]]);
    t_assert(false, 'empty list shape blocked');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'DOCUMENT_STATUS_UNVERIFIED' && count($bx->mutating) === 0, 'missing documents key: zero mutations');
}

$bx = new FakeBitrixGateway();
$bx->seedOffer(432, 51);
$bx->http500 = true;
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd3', 'documentId' => 12, 'items' => [docItem('a', 432)]]);
    t_assert(false, 'http 500 blocked');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'DOCUMENT_STATUS_UNVERIFIED' && count($bx->mutating) === 0, 'HTTP 500: zero mutations');
}

$bx = new FakeBitrixGateway();
$bx->seedOffer(432, 51);
$bx->timeout = true;
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd4', 'documentId' => 13, 'items' => [docItem('a', 432)]]);
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'DOCUMENT_STATUS_UNVERIFIED' && count($bx->mutating) === 0, 'timeout: zero mutations');
}

$bx = new FakeBitrixGateway();
$bx->seedOffer(432, 51);
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd5', 'documentId' => 999, 'items' => [docItem('a', 432)]]);
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'DOCUMENT_NOT_FOUND' && count($bx->mutating) === 0, 'missing document');
}

$bx = new FakeBitrixGateway();
$bx->seedDocument(14, 'C');
$bx->seedOffer(432, 51);
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd6', 'documentId' => 14, 'items' => [docItem('a', 432)]]);
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'DOCUMENT_CANCELLED' && count($bx->mutating) === 0, 'cancelled document');
}

$bx = new FakeBitrixGateway();
$bx->seedDocument(15, 'Y');
$bx->seedOffer(432, 51);
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
$already = $d->syncAndConduct(['operationId' => 'd7', 'documentId' => 15, 'items' => [docItem('a', 432)]]);
t_assert($already['alreadyConducted'] === true && count($bx->mutating) === 0, 'already conducted: zero mutations');

$bx = new FakeBitrixGateway();
$bx->seedDocument(16, 'N');
$bx->seedOffer(432, 51);
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
$ok = $d->syncAndConduct(['operationId' => 'd8', 'documentId' => 16, 'items' => [docItem('a', 432, 3)]]);
t_assert($ok['status'] === 'Y' && ($bx->stockMoved[16] ?? 0) === 3.0, 'valid draft conducts once');

$bx = new FakeBitrixGateway();
$bx->seedDocument(17, 'X');
$bx->seedOffer(432, 51);
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd-status-x', 'documentId' => 17, 'items' => [docItem('a', 432)]]);
    t_assert(false, 'unknown status X blocked');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'DOCUMENT_STATUS_UNVERIFIED' && count($bx->mutating) === 0, 'status=X: zero mutations');
}

$bx = new FakeBitrixGateway();
$bx->seedDocument(18, 'N');
$bx->seedOffer(432, 51);
$bx->omitDocumentType = true;
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd-no-type', 'documentId' => 18, 'items' => [docItem('a', 432)]]);
    t_assert(false, 'missing docType blocked');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'INVALID_REQUEST' && count($bx->mutating) === 0, 'missing docType: zero mutations');
}

$bx = new FakeBitrixGateway();
$bx->seedDocument(19, 'N', 'M');
$bx->seedOffer(432, 51);
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd-type-m', 'documentId' => 19, 'items' => [docItem('a', 432)]]);
    t_assert(false, 'moving docType blocked');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'INVALID_REQUEST' && count($bx->mutating) === 0, 'docType=M: zero mutations');
}

$bx = new FakeBitrixGateway();
$bx->seedDocument(50, 'N');
$bx->seedOffer(432, 51);
$bx->stores = [];
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd-empty-stores', 'documentId' => 50, 'items' => [docItem('a', 432)]]);
    t_assert(false, 'empty store list blocked');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'INVALID_DOCUMENT_ITEM' && count($bx->mutating) === 0, 'empty stores: zero mutations');
}

$bx = new FakeBitrixGateway();
$bx->seedDocument(51, 'N');
$bx->seedOffer(432, 51);
$bx->omitDocumentElementsKey = true;
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd-no-elements', 'documentId' => 51, 'items' => [docItem('a', 432)]]);
    t_assert(false, 'missing documentElements blocked');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'RECONCILIATION_REQUIRED' && count($bx->mutating) === 0, 'missing documentElements: zero mutations');
}

$bx = new FakeBitrixGateway();
$bx->seedDocument(52, 'N');
$bx->seedOffer(432, 51);
$bx->documentElementsNotArray = true;
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'd-bad-elements', 'documentId' => 52, 'items' => [docItem('a', 432)]]);
    t_assert(false, 'non-array documentElements blocked');
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'RECONCILIATION_REQUIRED' && count($bx->mutating) === 0, 'documentElements not array: zero mutations');
}

echo "\n[DOCUMENT validation]\n";
$bx = new FakeBitrixGateway();
$bx->seedDocument(20, 'N');
for ($i = 1; $i <= 10; $i++) {
    $bx->seedOffer(6000 + $i, 51);
}
$items10 = [];
for ($i = 1; $i <= 10; $i++) {
    $items10[] = docItem('row-' . $i, 6000 + $i, $i);
}
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
$ok = $d->syncAndConduct(['operationId' => 'val-10', 'documentId' => 20, 'items' => $items10]);
t_assert(count($ok['items']) === 10 && count($bx->elements[20]) === 10, '10 valid items');

$bx = new FakeBitrixGateway();
$bx->seedDocument(21, 'N');
for ($i = 1; $i <= 10; $i++) {
    $bx->seedOffer(6100 + $i, 51);
}
$bad = $items10;
$bad[9]['offerId'] = 0;
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct(['operationId' => 'val-10th', 'documentId' => 21, 'items' => $bad]);
    t_assert(false, '10th invalid');
} catch (TsdGuardException $e) {
    t_assert(
        $e->errorCode === 'INVALID_DOCUMENT_ITEM' && (int)$e->extra['positionIndex'] === 10 && empty($bx->elements[21]) && count($bx->mutating) === 0,
        'error on 10th item mutates nothing'
    );
}

foreach (
    [
        [['localItemId' => '', 'offerId' => 432, 'quantity' => 1, 'storeTo' => 1], 'empty localItemId'],
        [docItem('x', 0), 'zero offerId'],
        [docItem('x', 432, -1), 'negative qty'],
        [docItem('x', 432, 0), 'zero qty'],
        [docItem('x', 432, 1, 99), 'missing store'],
    ] as $case
) {
    $bx = new FakeBitrixGateway();
    $bx->seedDocument(22, 'N');
    $bx->seedOffer(432, 51);
    $d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
    try {
        $d->syncAndConduct(['operationId' => 'val-' . $case[1], 'documentId' => 22, 'items' => [$case[0]]]);
        t_assert(false, $case[1]);
    } catch (TsdGuardException $e) {
        t_assert($e->errorCode === 'INVALID_DOCUMENT_ITEM' && count($bx->mutating) === 0, $case[1] . ' rejected');
    }
}

$bx = new FakeBitrixGateway();
$bx->seedDocument(23, 'N');
$bx->seedOffer(432, 51);
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
try {
    $d->syncAndConduct([
        'operationId' => 'dup-local',
        'documentId' => 23,
        'items' => [docItem('same', 432, 1), docItem('same', 432, 2)],
    ]);
} catch (TsdGuardException $e) {
    t_assert($e->errorCode === 'INVALID_DOCUMENT_ITEM' && count($bx->mutating) === 0, 'duplicate localItemId');
}

echo "\n[DOCUMENT row recovery]\n";
$bx = new FakeBitrixGateway();
$bx->seedDocument(30, 'N');
$bx->seedOffer(432, 51);
$store = new InMemoryOperationStore();
$d = new TsdDocumentEngine($store, new InMemoryLockManager(), $bx);
$bx->crashBefore = 'catalog.document.element.add';
try {
    $d->syncAndConduct(['operationId' => 'row-before', 'documentId' => 30, 'items' => [docItem('r1', 432, 4)]]);
} catch (\RuntimeException $e) {
}
t_assert(empty($bx->elements[30]), 'crash before add: no rows');
$bx->crashBefore = null;
$ok = (new TsdDocumentEngine($store, new InMemoryLockManager(), $bx))->syncAndConduct(
    ['operationId' => 'row-before', 'documentId' => 30, 'items' => [docItem('r1', 432, 4)]]
);
t_assert(count($bx->elements[30]) === 1 && $ok['status'] === 'Y', 'retry after crash before add');

$bx = new FakeBitrixGateway();
$bx->seedDocument(31, 'N');
$bx->seedOffer(432, 51);
$store = new InMemoryOperationStore();
$d = new TsdDocumentEngine($store, new InMemoryLockManager(), $bx);
$bx->crashAfter = 'catalog.document.element.add';
try {
    $d->syncAndConduct(['operationId' => 'row-after-add', 'documentId' => 31, 'items' => [docItem('r1', 432, 5)]]);
} catch (\RuntimeException $e) {
}
t_assert(count($bx->elements[31]) === 1, 'row exists after add crash');
$adds = count(array_filter($bx->mutating, function ($m) { return $m === 'catalog.document.element.add'; }));
try {
    (new TsdDocumentEngine($store, new InMemoryLockManager(), $bx))->syncAndConduct(
        ['operationId' => 'row-after-add', 'documentId' => 31, 'items' => [docItem('r1', 432, 5)]]
    );
    t_assert(false, 'unknown element.add must not add again');
} catch (TsdGuardException $e) {
    $addsAfter = count(array_filter($bx->mutating, function ($m) { return $m === 'catalog.document.element.add'; }));
    t_assert(
        $e->errorCode === 'RECONCILIATION_REQUIRED' && count($bx->elements[31]) === 1 && $addsAfter === $adds,
        'retry after unknown element.add does not duplicate'
    );
}

$rowId = (int)$bx->elements[31][0]['id'];
$store->operations['D:row-after-add:31']['step'] = 'ELEMENT_CREATED';
$store->operations['D:row-after-add:31']['progress_json'] = json_encode([
    'items' => ['r1' => ['elementId' => $rowId, 'offerId' => 432, 'mapped' => false]],
]);
$addsBeforeMap = count(array_filter($bx->mutating, function ($m) { return $m === 'catalog.document.element.add'; }));
$mappedRetry = (new TsdDocumentEngine($store, new InMemoryLockManager(), $bx))->syncAndConduct(
    ['operationId' => 'row-after-add', 'documentId' => 31, 'items' => [docItem('r1', 432, 5)]]
);
$addsAfterMap = count(array_filter($bx->mutating, function ($m) { return $m === 'catalog.document.element.add'; }));
t_assert(
    $mappedRetry['status'] === 'Y' && count($bx->elements[31]) === 1 && $addsAfterMap === $addsBeforeMap,
    'resume with known elementId remaps and does not add'
);

$bx = new FakeBitrixGateway();
$bx->seedDocument(32, 'N');
$bx->seedOffer(432, 51);
$d = new TsdDocumentEngine(new InMemoryOperationStore(), new InMemoryLockManager(), $bx);
$two = $d->syncAndConduct([
    'operationId' => 'two-batches',
    'documentId' => 32,
    'items' => [docItem('p1', 432, 2, 1, 'P1'), docItem('p2', 432, 5, 1, 'P2')],
]);
t_assert(count($bx->elements[32]) === 2 && ($bx->stockMoved[32] ?? 0) === 7.0, 'two legitimate rows same offerId');

echo "\n[Concurrency and stress]\n";
$bx = new FakeBitrixGateway();
$store = new InMemoryOperationStore();
$locks = new InMemoryLockManager();
$v = new TsdVariationEngine($store, $locks, $bx);
$first = $v->resolveOrCreate(variationQuery('c1', 51, 'race-bc', 'a'));
$second = (new TsdVariationEngine($store, $locks, $bx))->resolveOrCreate(variationQuery('c2', 51, 'race-bc', 'b'));
t_assert($first['offerId'] === $second['offerId'] && count($bx->offers) === 1, '2 clients one barcode one offer');

$bx = new FakeBitrixGateway();
$store = new InMemoryOperationStore();
$created = [];
for ($n = 1; $n <= 10; $n++) {
    $v = new TsdVariationEngine($store, new InMemoryLockManager(), $bx);
    $created[] = $v->resolveOrCreate(variationQuery('cli-' . $n, 51, 'ten-bc', 'loc-' . $n));
}
$ids = array_unique(array_column($created, 'offerId'));
t_assert(count($ids) === 1 && count($bx->offers) === 1, '10 clients one barcode one offer');

$bx = new FakeBitrixGateway();
$store = new InMemoryOperationStore();
$created5 = [];
for ($n = 1; $n <= 5; $n++) {
    $v = new TsdVariationEngine($store, new InMemoryLockManager(), $bx);
    $created5[] = $v->resolveOrCreate(variationQuery('five-' . $n, 51, 'five-bc', 'l-' . $n));
}
t_assert(count(array_unique(array_column($created5, 'offerId'))) === 1 && count($bx->offers) === 1, '5 clients one barcode one offer');

$bx = new FakeBitrixGateway();
$bx->seedDocument(40, 'N');
$bx->seedOffer(432, 51);
$store = new InMemoryOperationStore();
$d = new TsdDocumentEngine($store, new InMemoryLockManager(), $bx);
$query = ['operationId' => 'repeat-100', 'documentId' => 40, 'items' => [docItem('only', 432, 3)]];
$d->syncAndConduct($query);
for ($i = 0; $i < 100; $i++) {
    $again = (new TsdDocumentEngine($store, new InMemoryLockManager(), $bx))->syncAndConduct($query);
}
t_assert(count($bx->elements[40]) === 1 && ($bx->stockMoved[40] ?? 0) === 3.0, '100 repeats stay one row stock 3');
$conducts = array_values(array_filter($bx->mutating, function ($m) {
    return $m === 'catalog.document.conduct';
}));
t_assert(count($conducts) === 1, '100 repeats one conduct');

$bx = new FakeBitrixGateway();
$bx->seedDocument(41, 'N');
$bx->seedOffer(432, 51);
$store = new InMemoryOperationStore();
$d = new TsdDocumentEngine($store, new InMemoryLockManager(), $bx);
$first = $d->syncAndConduct(['operationId' => 'tsdA', 'documentId' => 41, 'items' => [docItem('a', 432, 3)]]);
$second = (new TsdDocumentEngine($store, new InMemoryLockManager(), $bx))->syncAndConduct(
    ['operationId' => 'tsdB', 'documentId' => 41, 'items' => [docItem('b', 432, 3)]]
);
t_assert(
    $first['status'] === 'Y' && $second['alreadyConducted'] === true && count($bx->elements[41]) === 1 && ($bx->stockMoved[41] ?? 0) === 3.0,
    'two TSD one document: second does not add rows'
);

echo "\n=== {$passes} passed, {$failures} failed ===\n";
exit($failures === 0 ? 0 : 1);
