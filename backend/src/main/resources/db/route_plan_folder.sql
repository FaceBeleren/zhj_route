-- Route plan folders. Run once in the database that stores the route plan tables.
CREATE TABLE IF NOT EXISTS zhj_route_plan_folder (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'folder primary key',
  folder_name VARCHAR(128) NOT NULL COMMENT 'folder name',
  unit_id VARCHAR(64) DEFAULT NULL COMMENT 'company id',
  unit_name VARCHAR(128) DEFAULT NULL COMMENT 'company name',
  remark VARCHAR(512) DEFAULT NULL COMMENT 'remark',
  been_deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'deleted flag',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
  PRIMARY KEY (id),
  KEY idx_folder_unit (unit_id),
  KEY idx_folder_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='route plan folders';

ALTER TABLE zhj_route_plan_group
  ADD COLUMN folder_id BIGINT DEFAULT NULL COMMENT 'parent folder id' AFTER id;

ALTER TABLE zhj_route_plan_group
  ADD KEY idx_route_plan_folder (folder_id);
