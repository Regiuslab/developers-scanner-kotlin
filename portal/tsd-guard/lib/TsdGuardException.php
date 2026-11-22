<?php

class TsdGuardException extends \RuntimeException
{
    /** @var string */
    public $errorCode;
    /** @var int */
    public $httpStatus;
    /** @var array */
    public $extra;

    public function __construct(string $message, string $errorCode, int $httpStatus = 400, array $extra = [])
    {
        parent::__construct($message);
        $this->errorCode = $errorCode;
        $this->httpStatus = $httpStatus;
        $this->extra = $extra;
    }

    public function toRestException()
    {
        $description = $this->getMessage();
        if (!empty($this->extra['positionIndex'])) {
            $description = 'Позиция №' . $this->extra['positionIndex'] . ': ' . $description;
        }
        if (class_exists('Bitrix\\Rest\\RestException')) {
            return new \Bitrix\Rest\RestException($description, $this->errorCode, $this->httpStatus);
        }
        return $this;
    }
}
