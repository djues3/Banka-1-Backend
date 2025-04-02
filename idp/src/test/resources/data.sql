-- Delete previous data
TRUNCATE TABLE "user" CASCADE;
TRUNCATE TABLE reset_password CASCADE;
TRUNCATE TABLE set_password CASCADE;


ALTER SEQUENCE user_id_seq RESTART WITH 200;

-- ============== Loading Data ==============

-- Admin user password: admin123
INSERT INTO "user" (id, user_type, first_name, last_name, email, password, is_admin, phone_number,
                    birth_date, gender, department, position, active, address,
                    username)
VALUES (1, 'employee', 'Admin', 'Admin', 'admin@admin.com',
        '{bcrypt}$2a$12$a6UwueemopNy4KUDyA4A/u9H6UvzdkgaXicqmG0xeyWmlDXoE9YA6', TRUE, '1234567890',
        '2000-01-01', 'MALE', 'HR', 'DIRECTOR', TRUE, 'Admin Address',
        'admin123');

-- Insert all permissions for admin
INSERT INTO user_permissions (user_id, permission)
SELECT 1,
       unnest(ARRAY [
           'CREATE_CUSTOMER', 'DELETE_CUSTOMER', 'LIST_CUSTOMER', 'EDIT_CUSTOMER', 'READ_CUSTOMER',
           'SET_CUSTOMER_PERMISSION', 'SET_EMPLOYEE_PERMISSION', 'DELETE_EMPLOYEE', 'EDIT_EMPLOYEE',
           'LIST_EMPLOYEE', 'READ_EMPLOYEE', 'CREATE_EMPLOYEE'
           ]);

-- Employee 1: Petar password: Per@12345
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, address, gender, position, department, active,
                    is_admin, password)
VALUES (2, 'employee', 'Petar', 'Petrović', 'petar.petrovic@banka.com', 'perica',
        '+381641001000', '1990-07-07', 'Knez Mihailova 5', 'MALE', 'MANAGER',
        'IT', TRUE, FALSE, '{bcrypt}$2a$12$701A1x4twfow4aFeVFYqu.o2nwIqIz97meHqccPDWlkv18Jr4Vzke');

-- Permissions for Petar
INSERT INTO user_permissions (user_id, permission)
SELECT 2,
       unnest(ARRAY [
           'CREATE_CUSTOMER', 'DELETE_CUSTOMER', 'LIST_CUSTOMER', 'EDIT_CUSTOMER', 'READ_CUSTOMER',
           'SET_CUSTOMER_PERMISSION', 'SET_EMPLOYEE_PERMISSION', 'DELETE_EMPLOYEE', 'EDIT_EMPLOYEE',
           'LIST_EMPLOYEE', 'READ_EMPLOYEE', 'CREATE_EMPLOYEE'
           ]);

-- Employee 2: Jovana password: Jovan@12345
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, address, gender, position, department, active,
                    is_admin, password)
VALUES (3, 'employee', 'Jovana', 'Jovanović', 'jovana.jovanovic@banka.com', 'jjovanaa',
        '+381641001001', '2000-10-10', 'Knez Mihailova 6', 'FEMALE', 'WORKER',
        'HR', TRUE, FALSE, '{bcrypt}$2a$12$O1E9ReFjhbBZlsaKqknetekARUHzFMEdgzrJGRv2ymFTgdRlHIBla');

-- Permissions for Jovana
INSERT INTO user_permissions (user_id, permission)
SELECT 3,
       unnest(ARRAY [
           'READ_CUSTOMER', 'CREATE_CUSTOMER', 'DELETE_CUSTOMER', 'LIST_CUSTOMER', 'EDIT_CUSTOMER'
           ]);

-- Employee 3: Nikolina
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, address, gender, position, department, active,
                    is_admin, password)
VALUES (4, 'employee', 'Nikolina', 'Jovanović', 'nikolina.jovanovic@banka.com', 'nikolinaaa',
        '+381641001001', '2000-10-10', 'Knez Mihailova 6', 'FEMALE', 'WORKER',
        'SUPERVISOR', TRUE, FALSE, '{bcrypt}$2a$12$O1E9ReFjhbBZlsaKqknetekARUHzFMEdgzrJGRv2ymFTgdRlHIBla');

-- Permissions for Nikolina password: Jovan@12345
INSERT INTO user_permissions (user_id, permission)
SELECT 4,
       unnest(ARRAY [
           'READ_CUSTOMER', 'CREATE_CUSTOMER', 'DELETE_CUSTOMER', 'LIST_CUSTOMER', 'EDIT_CUSTOMER'
           ]);

-- Employee 4: Milica password: Jovan@12345
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, address, gender, position, department, active,
                    is_admin, password)
VALUES (5, 'employee', 'Milica', 'Jovanović', 'milica.jovanovic@banka.com', 'milicaaaa',
        '+381641001001', '2000-10-10', 'Knez Mihailova 6', 'FEMALE', 'WORKER',
        'AGENT', false, FALSE, '{bcrypt}$2a$12$9R.rU0qh9hsrf9qyMBanuueW8nXc4hjbXW7oySmWs6AFSxd7/.il6');

-- Permissions for Milica
INSERT INTO user_permissions (user_id, permission)
SELECT 5,
       unnest(ARRAY [
           'READ_CUSTOMER', 'CREATE_CUSTOMER', 'DELETE_CUSTOMER', 'LIST_CUSTOMER', 'EDIT_CUSTOMER'
           ]);

-- Customer 1: Marko password: M@rko12345
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, gender, address, password)
VALUES (101, 'customer', 'Marko', 'Marković', 'marko.markovic@banka.com', 'okram',
        '+381641001002', '2005-12-12', 'MALE', 'Knez Mihailova 7',
        '{bcrypt}$2a$12$6BrRAsoQOf69SntpgmuWt.r885ax.3em4PUR9jRcGJ17vEutjLMfK');

-- Permissions for Marko
INSERT INTO user_permissions (user_id, permission)
VALUES (101, 'READ_EMPLOYEE');

-- Customer 2: Anastasija password: Anastas12345
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, gender, address, password)
VALUES (102, 'customer', 'Anastasija', 'Milinković', 'anastasija.milinkovic@banka.com', 'anastass',
        '+381641001003', '2001-02-02', 'FEMALE', 'Knez Mihailova 8',
        '{bcrypt}$2a$12$UHA34n9YFbIA9AuzsQak/..LBZVsNxW/BIKyasGDZiww6tsOvAxk2');

-- Permissions for Anastasija
INSERT INTO user_permissions (user_id, permission)
VALUES (102, 'READ_EMPLOYEE');

-- Customer 3: Jovan password: Jov@njovan1
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, gender, address, password)
VALUES (103, 'customer', 'Jovan', 'Pavlovic', 'jpavlovic6521rn@raf.rs', 'jovan',
        '+381641001003', '2001-02-02', 'MALE', 'Knez Mihailova 8',
        '{bcrypt}$2a$12$LJvObRdM1kyZrAvmfIb7bO/.D4uGbL6PMmz7gAs28iEWmFJj9Bd0m');

-- Permissions for Jovan
INSERT INTO user_permissions (user_id, permission)
VALUES (103, 'READ_EMPLOYEE');

-- Customer 4: Nemanja password: Nemanjanemanj@1
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, gender, address, password)
VALUES (104, 'customer', 'Nemanja', 'Marjanov', 'nmarjanov6121rn@raf.rs', 'nemanja',
        '+381641001123', '2001-02-02', 'MALE', 'Knez Mihailova 8',
        '{bcrypt}$2a$12$b.HusrqJJJKqgVgJ6Aya8OQFQ/Xbf40j46eWm4vGRySBhoxheeHGK');

-- Permissions for Nemanja
INSERT INTO user_permissions (user_id, permission)
VALUES (104, 'READ_EMPLOYEE');

-- Customer 5: Nikola password: Nikola12345
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, gender, address, password)
VALUES (105, 'customer', 'Nikola', 'Nikolic', 'primer@primer.rs', 'nikkola',
        '+381641001303', '2001-02-02', 'MALE', 'Knez Mihailova 8',
        '{bcrypt}$2a$12$DunYgycAgZyxgEXQaqZkMOYfApTQgBdw3MvWFOBByqEhSnFXmgXeG');

-- Permissions for Nikola
INSERT INTO user_permissions (user_id, permission)
VALUES (105, 'READ_EMPLOYEE');

-- Customer 6: Jelena password: nemanjanemanja
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, gender, address, password)
VALUES (106, 'customer', 'Jelena', 'Jovanovic', 'jelena@primer.rs', 'jelena',
        '+381621001003', '2001-02-02', 'FEMALE', 'Knez Mihailova 8',
        '{bcrypt}$2a$12$qFvNjtYakFs7y6M8qGuTIuv559SFVIBb.81RJiszxV3lsDFE47MLW');

-- Permissions for Jelena
INSERT INTO user_permissions (user_id, permission)
VALUES (106, 'READ_EMPLOYEE');

-- Customer: BANKA (email: bankabanka@banka1.com, password: nemanjanemanja)
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, gender, address, password)
VALUES (107, 'customer', 'Banka', 'Banka', 'bankabanka@banka1.com', 'bankabanka',
        '+381640000000', '2025-01-01', 'MALE', 'Bulevar Banka 1',
        '{bcrypt}$2a$12$o.3keL1nmgtvxa30G5qhRuL4.7KMjJVySvUMB20mJhJ70coF02TB2');

-- Permissions for BANKA
INSERT INTO user_permissions (user_id, permission)
VALUES (107, 'READ_EMPLOYEE');

-- Customer: DRZAVA (email: drzavadrzava@drzava1.com, password: nemanjanemanja)
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, gender, address, password)
VALUES (108, 'customer', 'Država', 'Država', 'drzavadrzava@drzava1.com', 'drzavadrzava',
        '+381640000001', '2025-01-01', 'FEMALE', 'Bulevar Država 1',
        '{bcrypt}$2a$12$nfFxp2gXNUf2nQ.NXFsClO/GKxC/edUjjSPFfnmZLT3XfmPHECLcq');

-- Permissions for DRZAVA
INSERT INTO user_permissions (user_id, permission)
VALUES (108, 'READ_EMPLOYEE');

-- ============== Data Loaded ==============

-- Employee 6: Milica password: Jovan@12345
INSERT INTO "user" (id, user_type, first_name, last_name, email, username, phone_number,
                    birth_date, address, gender, position, department, active,
                    is_admin, password)
VALUES (6, 'employee', 'Milica', 'Jovanović', 'milica.jovanovic2@banka.com', 'milicaaaa2',
        '+381641001001', '2000-10-10', 'Knez Mihailova 6', 'FEMALE', 'WORKER',
        'AGENT', TRUE, FALSE, '{bcrypt}$2a$12$9Ly8SlzVkRSiGmddWsKiJ.j8J/nki30qO9gaGJ6q5ZwZbRzi/UC.m');