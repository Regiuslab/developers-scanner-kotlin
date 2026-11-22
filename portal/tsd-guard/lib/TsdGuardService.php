<?php

/**
 * Production persistence/lock helpers. Engines depend on interfaces, not this class.
 * Kept so include.php and older references continue to resolve.
 */
class TsdGuardService extends TsdMysqlOperationStore
{
    /** @var TsdMysqlLockManager */
    private $locks;

    public function __construct()
    {
        $this->locks = new TsdMysqlLockManager();
    }

    public function getLock(string $name, int $timeoutSeconds = 10): bool
    {
        return $this->locks->acquire($name, $timeoutSeconds);
    }

    public function releaseLock(string $name): void
    {
        $this->locks->release($name);
    }
}
