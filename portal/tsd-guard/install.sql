CREATE TABLE IF NOT EXISTS tsd_operation (
  op_key       VARCHAR(128) NOT NULL,
  operation_id VARCHAR(64)  NOT NULL,
  kind         VARCHAR(16)  NOT NULL,
  document_id  INT NULL,
  payload_hash VARCHAR(128) NOT NULL,
  state        VARCHAR(16)  NOT NULL,
  result_json  MEDIUMTEXT NULL,
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  PRIMARY KEY (op_key),
  KEY ix_tsd_operation_id (operation_id)
);

CREATE TABLE IF NOT EXISTS tsd_document_item (
  operation_id      VARCHAR(64) NOT NULL,
  local_item_id     VARCHAR(64) NOT NULL,
  document_id       INT NOT NULL,
  bitrix_element_id INT NOT NULL,
  offer_id          INT NOT NULL,
  PRIMARY KEY (operation_id, local_item_id, document_id)
);
