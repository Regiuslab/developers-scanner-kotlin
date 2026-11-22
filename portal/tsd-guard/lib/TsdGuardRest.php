<?php

/**
 * Thin REST adapter. Business rules live in TsdVariationEngine / TsdDocumentEngine.
 */
class TsdGuardRest
{
    /** @var callable|null */
    public static $engineFactory = null;

    public static function resolveOrCreateVariation(array $query, $n = null, $server = null)
    {
        try {
            return self::variationEngine()->resolveOrCreate($query);
        } catch (TsdGuardException $e) {
            throw $e->toRestException();
        }
    }

    public static function syncAndConductDocument(array $query, $n = null, $server = null)
    {
        try {
            return self::documentEngine()->syncAndConduct($query);
        } catch (TsdGuardException $e) {
            throw $e->toRestException();
        }
    }

    public static function variationEngine(): TsdVariationEngine
    {
        if (self::$engineFactory) {
            $built = call_user_func(self::$engineFactory, 'variation');
            if ($built instanceof TsdVariationEngine) {
                return $built;
            }
        }
        return new TsdVariationEngine(new TsdMysqlOperationStore(), new TsdMysqlLockManager(), new TsdBitrixRestGateway());
    }

    public static function documentEngine(): TsdDocumentEngine
    {
        if (self::$engineFactory) {
            $built = call_user_func(self::$engineFactory, 'document');
            if ($built instanceof TsdDocumentEngine) {
                return $built;
            }
        }
        return new TsdDocumentEngine(new TsdMysqlOperationStore(), new TsdMysqlLockManager(), new TsdBitrixRestGateway());
    }
}
