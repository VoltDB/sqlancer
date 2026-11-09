-- ticket: ENG-30505
-- summary: IN on a VARCHAR(4) primary key fails when a list value is longer than 4 (= works)
-- bug: contains: exceeds the size of the VARCHAR(4) column
-- fixed: contains: (1 rows)
CREATE TABLE vk (c VARCHAR(4) NOT NULL, PRIMARY KEY (c));
INSERT INTO vk VALUES ('ab');
-- check
SELECT c FROM vk WHERE c IN ('ab', 'abcdefgh');
