CREATE DATABASE dbtest1;

USE dbtest1;

CREATE TABLE table1 (id INT PRIMARY KEY, name VARCHAR(255) NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP);

CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 1 executed' AS Message; END;
CREATE PROCEDURE proc2 AS BEGIN SELECT 'Procedure 2 executed' AS Message; END;
CREATE PROCEDURE prefix1_proc1 AS BEGIN SELECT 'Prefix1 procedure 1 executed' AS Message; END;
CREATE PROCEDURE prefix2_proc1 AS BEGIN SELECT 'Prefix2 procedure 1 executed' AS Message; END;
CREATE PROCEDURE prefix3_proc1 AS BEGIN SELECT 'Prefix3 procedure 1 executed' AS Message; END;
CREATE PROCEDURE prefix4_proc1 AS BEGIN SELECT 'Prefix4 procedure 1 executed' AS Message; END;

CREATE TRIGGER trigger1 ON table1 AFTER INSERT AS BEGIN INSERT INTO table1 (name) VALUES ('test name 1'); END;
CREATE TRIGGER trigger2 ON table1 AFTER UPDATE AS BEGIN INSERT INTO table1 (name) VALUES ('test name 2'); END;
CREATE TRIGGER prefix1_trigger1 ON table1 AFTER DELETE AS BEGIN INSERT INTO table1 (name) VALUES ('test name prefix 1'); END;
CREATE TRIGGER prefix2_trigger1 ON table1 AFTER DELETE AS BEGIN INSERT INTO table1 (name) VALUES ('test name prefix 2'); END;
CREATE TRIGGER prefix3_trigger1 ON table1 AFTER DELETE AS BEGIN INSERT INTO table1 (name) VALUES ('test name prefix 3'); END;
CREATE TRIGGER prefix4_trigger1 ON table1 AFTER DELETE AS BEGIN INSERT INTO table1 (name) VALUES ('test name prefix 4'); END;

CREATE FUNCTION func1() RETURNS INT BEGIN RETURN 1 END;
CREATE FUNCTION func2() RETURNS INT BEGIN RETURN 2 END;
CREATE FUNCTION prefix1_func1() RETURNS INT BEGIN RETURN 11 END;
CREATE FUNCTION prefix2_func1() RETURNS INT BEGIN RETURN 12 END;
CREATE FUNCTION prefix3_func1() RETURNS INT BEGIN RETURN 13 END;
CREATE FUNCTION prefix4_func1() RETURNS INT BEGIN RETURN 14 END;

CREATE VIEW view1 AS SELECT id FROM table1;
CREATE VIEW view2 AS SELECT id, name FROM table1;
CREATE VIEW prefix1_view1 AS SELECT id, name, 'p1' AS prefix FROM table1;
CREATE VIEW prefix2_view1 AS SELECT id, name, 'p2' AS prefix FROM table1;
CREATE VIEW prefix3_view1 AS SELECT id, name, 'p3' AS prefix FROM table1;
CREATE VIEW prefix4_view1 AS SELECT id, name, 'p4' AS prefix FROM table1;

CREATE TYPE tt_UserList AS TABLE (UserId INT NOT NULL, UserName NVARCHAR(100) NOT NULL);
CREATE TYPE tt_OrderDetails AS TABLE (OrderId INT NOT NULL, ProductId INT NOT NULL, Quantity INT NOT NULL);
CREATE TYPE prefix1_tt_List AS TABLE (Id INT NOT NULL, Value NVARCHAR(50) NOT NULL);

CREATE SYNONYM syn_Table1 FOR dbo.table1;
CREATE SYNONYM syn_RemoteTable FOR [RemoteServer].[RemoteDB].[dbo].[RemoteTable];
CREATE SYNONYM prefix1_syn_Table FOR dbo.table1;

-- New sequences (seq_orderid differs between db1 and db2; prefix1_seq_temp identical)
CREATE SEQUENCE Seq_OrderId AS INT START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 1000 NO CYCLE NO CACHE;
CREATE SEQUENCE Prefix1_Seq_Temp AS BIGINT START WITH 100 INCREMENT BY 10 MINVALUE 0 MAXVALUE 100000 NO CYCLE CACHE 20;

-- New scalar types (phonenumber differs length, countrycode identical)
CREATE TYPE PhoneNumber FROM VARCHAR(20) NOT NULL;
CREATE TYPE CountryCode FROM CHAR(2) NOT NULL;

CREATE DATABASE dbtest2;

USE dbtest2;

CREATE TABLE table1 (id INT PRIMARY KEY, name VARCHAR(255) NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP);

CREATE PROCEDURE proc1 AS BEGIN SELECT 'Procedure 11 executed' AS Message; END;
CREATE PROCEDURE proc2 AS BEGIN SELECT 'Procedure 2 executed' AS Message; END;
CREATE PROCEDURE prefix1_proc2 AS BEGIN SELECT 'Should be ignored' AS Message; END;
CREATE PROCEDURE prefix2_proc1 AS BEGIN SELECT 'Prefix2 procedure 1 executed' AS Message; END;
CREATE PROCEDURE prefix3_proc1 AS BEGIN SELECT 'Prefix3 procedure 1 executed' AS Message; END;

CREATE TRIGGER trigger1 ON table1 AFTER INSERT AS BEGIN INSERT INTO table1 (name) VALUES ('test name 11'); END;
CREATE TRIGGER trigger2 ON table1 AFTER UPDATE AS BEGIN INSERT INTO table1 (name) VALUES ('test name 2'); END;
CREATE TRIGGER prefix1_trigger1 ON table1 AFTER DELETE AS BEGIN INSERT INTO table1 (name) VALUES ('test name prefix 1'); END;
CREATE TRIGGER prefix2_trigger2 ON table1 AFTER DELETE AS BEGIN INSERT INTO table1 (name) VALUES ('should be ignored'); END;
CREATE TRIGGER prefix3_trigger1 ON table1 AFTER DELETE AS BEGIN INSERT INTO table1 (name) VALUES ('test name prefix 3'); END;
CREATE TRIGGER prefix4_trigger1 ON table1 AFTER DELETE AS BEGIN INSERT INTO table1 (name) VALUES ('test name prefix 4'); END;

CREATE FUNCTION func1() RETURNS INT BEGIN RETURN 11 END;
CREATE FUNCTION func2() RETURNS INT BEGIN RETURN 2 END;
CREATE FUNCTION prefix1_func1() RETURNS INT BEGIN RETURN 11 END;
CREATE FUNCTION prefix2_func1() RETURNS INT BEGIN RETURN 12 END;
CREATE FUNCTION prefix3_func1() RETURNS INT BEGIN RETURN 13 END;
CREATE FUNCTION prefix4_func2() RETURNS INT BEGIN RETURN 144 END;

CREATE VIEW view1 AS SELECT id, 'p1' AS prefix FROM table1;
CREATE VIEW view2 AS SELECT id, name FROM table1;
CREATE VIEW prefix1_view1 AS SELECT id, name, 'p1' AS prefix FROM table1;
CREATE VIEW prefix2_view1 AS SELECT id, name, 'p2' AS prefix FROM table1;
CREATE VIEW prefix3_view1 AS SELECT id, name, 'ignored' AS prefix FROM table1;
CREATE VIEW prefix4_view1 AS SELECT id, name, 'p4' AS prefix FROM table1;

CREATE TYPE tt_UserList AS TABLE (UserId INT NOT NULL, UserName NVARCHAR(100) NOT NULL);
CREATE TYPE tt_OrderDetails AS TABLE (OrderId INT NOT NULL, ProductId INT NOT NULL, Quantity INT NOT NULL);
CREATE TYPE prefix1_tt_List AS TABLE (Id INT NOT NULL, Value NVARCHAR(50) NOT NULL);

CREATE SYNONYM syn_Table1 FOR dbo.table1;
CREATE SYNONYM syn_RemoteTable FOR [RemoteServer].[RemoteDB].[dbo].[RemoteTable];
CREATE SYNONYM prefix1_syn_Table FOR dbo.table1;

-- Sequences (difference in INCREMENT BY only for seq_orderid)
CREATE SEQUENCE Seq_OrderId AS INT START WITH 1 INCREMENT BY 2 MINVALUE 1 MAXVALUE 1000 NO CYCLE NO CACHE;
CREATE SEQUENCE Prefix1_Seq_Temp AS BIGINT START WITH 100 INCREMENT BY 10 MINVALUE 0 MAXVALUE 100000 NO CYCLE CACHE 20;

-- Scalar types (difference in varchar length only for phonenumber)
CREATE TYPE PhoneNumber FROM VARCHAR(25) NOT NULL;
CREATE TYPE CountryCode FROM CHAR(2) NOT NULL;
