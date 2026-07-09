ALTER TABLE plan_item_events
    ADD COLUMN before_block_type VARCHAR(20) NULL,
    ADD COLUMN after_block_type  VARCHAR(20) NULL,
    ADD COLUMN before_start_time DATETIME NULL,
    ADD COLUMN after_start_time  DATETIME NULL,
    ADD COLUMN before_end_time   DATETIME NULL,
    ADD COLUMN after_end_time    DATETIME NULL;
