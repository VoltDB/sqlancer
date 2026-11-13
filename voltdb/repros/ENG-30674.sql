-- ticket: ENG-30674
-- summary: @ValidatePartitioning passes a client's malformed hash configuration to the EE unchecked, and the server crashes (SIGSEGV in ElasticHashinator::newInstance)
-- bug: crash
-- fixed: contains: (1 rows)
CREATE TABLE t (k INTEGER NOT NULL, PRIMARY KEY (k));
PARTITION TABLE t ON COLUMN k;
-- check
exec @ValidatePartitioning 'AB';
SELECT COUNT(*) FROM t;
