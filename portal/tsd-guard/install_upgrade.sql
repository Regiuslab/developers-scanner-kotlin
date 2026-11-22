-- Apply on portals that already ran the previous install.sql.
ALTER TABLE tsd_operation
  ADD COLUMN step VARCHAR(32) NULL AFTER state,
  ADD COLUMN offer_id INT NULL AFTER step,
  ADD COLUMN progress_json MEDIUMTEXT NULL AFTER offer_id;

CREATE TABLE IF NOT EXISTS tsd_operation_step (
  id               BIGINT NOT NULL AUTO_INCREMENT,
  op_key           VARCHAR(128) NOT NULL,
  step_type        VARCHAR(32) NOT NULL,
  status           VARCHAR(16) NOT NULL,
  remote_entity_id INT NULL,
  local_item_id    VARCHAR(64) NULL,
  details          TEXT NULL,
  created_at       DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY ix_tsd_step_op (op_key)
);
