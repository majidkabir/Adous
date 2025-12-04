# SQL Server Features Support Matrix

This document categorizes SQL Server database objects and features, identifying which are currently supported by Adous and which are not.

## Summary

- **Supported Features:** 10 object types
- **Can Be Added:** 8 feature categories
- **Cannot Be Supported:** 5 feature categories (by design or technical limitation)

---

## ✅ Currently Supported Features

These SQL Server features are fully implemented and operational in Adous.

### 1. **Tables**
- **Status:** ✅ Fully Supported
- **Implementation:**
  - Column definitions with data types
  - IDENTITY properties
  - NULL/NOT NULL constraints
  - Default values
  - Primary key constraints
  - Unique constraints
  - Foreign key constraints (with FK dependency tracking)
  - Check constraints
  - Regular indexes (Clustered, Non-Clustered, Unique)
  - Columnstore indexes (Clustered and Non-Clustered)
- **Method:** Extract via `sys.tables`, `sys.columns`, `sys.indexes`, `sys.foreign_keys`, `sys.constraints`
- **Application:** ALTER TABLE for data preservation (when possible), DROP/CREATE fallback

### 2. **Stored Procedures**
- **Status:** ✅ Fully Supported
- **Features:**
  - Parameters (IN, OUT, INOUT)
  - Stored procedure definitions
  - SET options (ANSI_NULLS, QUOTED_IDENTIFIER)
- **Method:** Extract via `sys.procedures` and `sys.sql_modules`
- **Application:** DROP/CREATE pattern

### 3. **Scalar Functions**
- **Status:** ✅ Fully Supported
- **Types Supported:**
  - User-defined scalar functions (UDF)
  - CLR functions
- **Method:** Extract via `sys.objects` where `type = 'FN'`
- **Application:** DROP/CREATE pattern

### 4. **Table-Valued Functions (TVF)**
- **Status:** ✅ Fully Supported
- **Types:**
  - Inline table-valued functions (ITF)
  - Multi-statement table-valued functions (MSTVF)
- **Method:** Extract via `sys.objects` where `type IN ('IF', 'TF')`
- **Application:** DROP/CREATE pattern

### 5. **Views**
- **Status:** ✅ Fully Supported
- **Features:**
  - View definitions
  - Dependency tracking between views
  - Topological sorting for application order
  - SQL parsing to detect view-to-view dependencies
- **Method:** Extract via `sys.views` and `sys.sql_modules`
- **Application:** DROP/CREATE with dependency ordering

### 6. **Triggers**
- **Status:** ✅ Fully Supported
- **Types Supported:**
  - DML triggers (INSERT, UPDATE, DELETE)
  - DDL triggers (schema and server-level)
- **Method:** Extract via `sys.triggers` and `sys.sql_modules`
- **Application:** DROP/CREATE pattern

### 7. **Sequences**
- **Status:** ✅ Fully Supported
- **Features:**
  - START WITH value
  - INCREMENT BY value
  - MINVALUE and MAXVALUE
  - CYCLE/NO CYCLE option
  - CACHE/NO CACHE configuration
- **Method:** Extract via `sys.sequences`
- **Application:** DROP/CREATE pattern

### 8. **Synonyms**
- **Status:** ✅ Fully Supported
- **Features:**
  - Synonym creation with target object reference
- **Method:** Extract via `sys.synonyms`
- **Application:** DROP/CREATE pattern

### 9. **User-Defined Table Types (UDTT)**
- **Status:** ✅ Fully Supported
- **Features:**
  - Column definitions
  - Identity properties
  - Nullable columns
  - Data types
- **Method:** Extract via `sys.table_types` and `sys.columns`
- **Application:** DROP/CREATE pattern

### 10. **User-Defined Scalar Types (UDST)**
- **Status:** ✅ Fully Supported
- **Features:**
  - Base type definition
  - Nullability
  - Default bindings
  - Rule bindings
- **Method:** Extract via `sys.types` where `is_user_defined = 1` and `is_table_type = 0`
- **Application:** DROP/CREATE pattern

### 11. **Full-Text Catalogs**
- **Status:** ✅ Fully Supported
- **Features:**
  - Catalog creation
  - Accent sensitivity settings
  - Default catalog designation
- **Method:** Extract via `sys.fulltext_catalogs`
- **Application:** DROP/CREATE pattern
- **Note:** Full-text indexes are NOT supported (see below)

### 12. **Database Schemas**
- **Status:** ✅ Fully Supported
- **Features:**
  - Schema creation
  - Automatic creation for missing schemas
- **Method:** Create via `CREATE SCHEMA` if not exists

---

## 🔧 Can Be Added (Feasible to Implement)

These features are SQL Server database objects that are NOT currently supported but CAN reasonably be implemented.

### 1. **Full-Text Indexes**
- **Current Status:** ❌ Not Supported
- **Reason for Omission:** Full-text indexes depend on full-text catalogs, linguistic features, and stemmers. They require separate index creation and management logic.
- **Feasibility:** 🟢 **Easy to Add**
- **Implementation Approach:**
  - Query `sys.fulltext_indexes` for index metadata
  - Extract stoplist, language, change tracking settings
  - Generate `CREATE FULLTEXT INDEX` statements
  - Query `sys.fulltext_index_columns` for indexed columns
  - Handle dependencies: FT Catalog → FT Index → FT Columns
- **Estimated Complexity:** Low (straightforward metadata extraction)
- **Priority:** Medium (advanced search feature, not all projects need this)

### 2. **Computed Columns (Persisted)**
- **Current Status:** ❌ Not Supported (columns are extracted but computed property not preserved)
- **Reason for Omission:** Requires special handling during table creation and modification. Affects data preservation strategy.
- **Feasibility:** 🟢 **Easy to Add**
- **Implementation Approach:**
  - Detect computed columns via `sys.columns` where `is_computed = 1`
  - Extract formula via `sys.computed_columns`
  - Include in table definition: `[ColumnName] AS formula [PERSISTED]`
  - Handle in ALTER TABLE logic
- **Estimated Complexity:** Low (metadata extraction + table DDL template adjustment)
- **Priority:** Medium (useful for derived data, not commonly used in simple schemas)

### 3. **Filtered Indexes (WHERE clause)**
- **Current Status:** ❌ Partially Supported (index created but WHERE clause omitted)
- **Reason for Omission:** WHERE clause filtering not extracted from index metadata
- **Feasibility:** 🟢 **Easy to Add**
- **Implementation Approach:**
  - Query `sys.indexes` and `sys.index_columns`
  - Extract filter definition via `sys.sql_modules` where index has filter
  - Include `WHERE` clause in generated `CREATE INDEX` statement
  - Example: `CREATE INDEX idx ON table(col) WHERE status = 1`
- **Estimated Complexity:** Low (straightforward metadata extraction)
- **Priority:** Medium (useful for optimization, not essential)

### 4. **Statistics Objects**
- **Current Status:** ❌ Not Supported
- **Reason for Omission:** Statistics are auto-managed by SQL Server and typically not version-controlled
- **Feasibility:** 🟡 **Moderate to Add**
- **Implementation Approach:**
  - Query `sys.stats` for statistics metadata
  - Query `sys.stats_columns` for column associations
  - Generate `CREATE STATISTICS` statements
  - Optional: Include auto-update settings
- **Estimated Complexity:** Moderate (good practices debate: should stats be version-controlled?)
- **Priority:** Low (typically auto-managed; manual versioning is uncommon)

### 5. **Constraints: Default Constraints (at table level)**
- **Current Status:** ⚠️ Partially Supported (column-level defaults work, table-level constraints may be incomplete)
- **Reason for Omission:** Default constraints at column level are supported but named default constraints need review
- **Feasibility:** 🟢 **Easy to Add**
- **Implementation Approach:**
  - Ensure `sys.default_constraints` are fully extracted
  - Include in table DDL: `[Column] datatype DEFAULT constraint_name`
  - Generate separate `ADD CONSTRAINT` statements
- **Estimated Complexity:** Low (minor enhancements to existing code)
- **Priority:** Low (column defaults are already supported)

### 6. **Partitioned Tables and Indexes**
- **Current Status:** ❌ Not Supported
- **Reason for Omission:** Requires partition function and partition scheme definition and association
- **Feasibility:** 🟡 **Moderate to Add**
- **Implementation Approach:**
  - Query `sys.partition_functions` for partition functions
  - Query `sys.partition_schemes` for partition schemes
  - Query `sys.partitions` to map tables/indexes to partition schemes
  - Generate partition function, scheme, and table DDL with partitioning clause
  - Example: `CREATE TABLE t (id INT) ON ps_myscheme(id)`
- **Estimated Complexity:** Moderate (new object types + complex dependencies)
- **Priority:** Medium (advanced feature, needed for large data scenarios)

### 7. **Indexed Views (Materialized Views)**
- **Current Status:** ❌ Not Supported
- **Reason for Omission:** Views with indexes require special handling (SET options, schema binding, WITH SCHEMABINDING clause)
- **Feasibility:** 🟡 **Moderate to Add**
- **Implementation Approach:**
  - Detect views with indexes via `sys.indexes` on view objects
  - Ensure view includes `WITH SCHEMABINDING`
  - Generate `CREATE UNIQUE CLUSTERED INDEX` on view
  - Add strict requirement checking (SET options, determinism)
- **Estimated Complexity:** Moderate (requires SET option validation)
- **Priority:** Medium (advanced optimization, not critical)

### 8. **CLR Objects (Assembly-based Types, Functions, Procedures)**
- **Current Status:** ⚠️ Partial Support (CLR functions extracted but assemblies not versioned)
- **Reason for Omission:** CLR objects require assembly versioning and deployment; cannot extract compiled code
- **Feasibility:** 🟡 **Moderate to Add**
- **Implementation Approach:**
  - Query `sys.assemblies` for CLR assemblies
  - Generate `CREATE ASSEMBLY` statements
  - Reference assembly in CLR function/procedure definitions
  - Challenge: Assembly binary must be re-provided or kept external
- **Estimated Complexity:** Moderate (assembly management, external file handling)
- **Priority:** Low (most projects use T-SQL only; CLR usage is declining)

---

## ❌ Cannot Be Supported (Design Limitations)

These SQL Server features CANNOT be supported by Adous due to architectural or technical constraints.

### 1. **Database-Level Objects (User, Role, Permission, Database Settings)**
- **What It Is:** Database users, roles, grants, permissions, database options
- **Why It Cannot Be Supported:**
  - **Architectural Reason:** Adous is object-schema versioning, not user/security versioning
  - **Security Risk:** Storing credentials and permissions in Git is a security anti-pattern
  - **Scope Mismatch:** Users and roles are environment-specific; schemas are portable
  - **Examples of What's Excluded:**
    - `CREATE USER`, `CREATE LOGIN`
    - `CREATE ROLE`, `ALTER ROLE ... ADD MEMBER`
    - `GRANT`, `DENY`, `REVOKE`
    - `ALTER DATABASE SET` options (recovery model, compatibility level, etc.)
- **Recommendation:** Manage these separately:
  - Use Azure AD or centralized identity management
  - Apply permissions via Infrastructure-as-Code (Terraform, Bicep, PowerShell)
  - Use separate deployment pipeline for security policies

### 2. **Instance-Level Objects (Logins, Server Roles, Server Configuration)**
- **What It Is:** SQL Server instance configuration, not database-level
- **Why It Cannot Be Supported:**
  - **Scope:** Adous operates at database level; instance config is out of scope
  - **Examples of What's Excluded:**
    - `CREATE LOGIN`
    - `ALTER SERVER ROLE`
    - Server-level permissions and auditing
    - Linked servers
    - Server-level triggers
- **Recommendation:** Use separate infrastructure automation (Terraform, Ansible, CloudFormation)

### 3. **Data (Rows and Values)**
- **What It Is:** Actual data rows in tables
- **Why It Cannot Be Supported:**
  - **By Design:** Adous is a schema versioning tool, NOT a data versioning tool
  - **Scale:** Tables can have millions of rows; Git isn't suitable for bulk data
  - **Privacy:** Storing production data in Git is a security/compliance risk
  - **Binary Size:** Large data dumps bloat repository
- **Recommendation:** 
  - Use ETL/data pipeline tools (Azure Data Factory, Talend, Informatica)
  - Use database backup/restore for data management
  - Use seeding strategies for test data only

### 4. **Replication and Synchronization Configuration**
- **What It Is:** SQL Server replication, change data capture (CDC), Always On availability groups configuration
- **Why It Cannot Be Supported:**
  - **Environmental:** These are deployment-specific, not part of schema
  - **Agent-Based:** Replication agents are external system components
  - **Complexity:** Replication topology is instance-specific
  - **Examples of What's Excluded:**
    - Publication/subscription definitions
    - Replication agents
    - CDC configuration
    - Always On failover group settings
- **Recommendation:** Configure via separate deployment orchestration layer

### 5. **Extended Properties and Metadata Annotations**
- **What It Is:** SQL Server extended properties, documentation, custom metadata
- **Why It Cannot Be Supported:**
  - **Complexity:** Requires separate metadata schema
  - **Fragmentation:** Extended properties are loosely attached to objects
  - **Maintainability:** Would require parsing and reapplying to every object
  - **Examples of What's Excluded:**
    - `sp_addextendedproperty` (documentation, descriptions)
    - Custom metadata values
    - Column descriptions and data lineage
    - Table documentation
- **Recommendation:**
  - Store documentation in separate wiki or documentation system
  - Use database documentation tools (SchemaCrawler, DataGrip docs)
  - Include comments in SQL files manually where critical

### 6. **Database Maintenance Objects (Jobs, Schedules, Alerts)** *(Bonus Entry)*
- **What It Is:** SQL Server Agent jobs, maintenance plans, alerts
- **Why It Cannot Be Supported:**
  - **Instance-Level:** Agent runs at instance level, not database level
  - **Environmental:** Jobs are environment-specific
  - **Operational:** These are ops/DevOps responsibility, not schema
- **Recommendation:** Use Azure Automation, AWS Systems Manager, or Jenkins for job orchestration

---

## Feature Matrix Summary Table

| Feature | Status | Difficulty | Priority | Notes |
|---------|--------|-----------|----------|-------|
| **Tables** | ✅ Yes | - | - | Full support with constraints, indexes, FKs |
| **Stored Procedures** | ✅ Yes | - | - | With SET options |
| **Scalar Functions** | ✅ Yes | - | - | T-SQL and CLR |
| **Table-Valued Functions** | ✅ Yes | - | - | Inline and multi-statement |
| **Views** | ✅ Yes | - | - | With dependency tracking |
| **Triggers** | ✅ Yes | - | - | DML and DDL triggers |
| **Sequences** | ✅ Yes | - | - | With all options |
| **Synonyms** | ✅ Yes | - | - | Object references |
| **User-Defined Table Types** | ✅ Yes | - | - | With column definitions |
| **User-Defined Scalar Types** | ✅ Yes | - | - | With defaults and rules |
| **Full-Text Catalogs** | ✅ Yes | - | - | Catalog infrastructure only |
| **Full-Text Indexes** | ❌ No | Easy | Medium | Can be added |
| **Computed Columns** | ❌ No | Easy | Medium | Can be added |
| **Filtered Indexes** | ❌ No | Easy | Medium | Can be added |
| **Statistics Objects** | ❌ No | Moderate | Low | Can be added |
| **Default Constraints** | ⚠️ Partial | Easy | Low | Minor enhancements |
| **Partitioned Tables** | ❌ No | Moderate | Medium | Can be added |
| **Indexed Views** | ❌ No | Moderate | Medium | Can be added |
| **CLR Assemblies** | ⚠️ Partial | Moderate | Low | Assemblies not versioned |
| **Database-Level Objects** | ❌ No | N/A | - | By design - security risk |
| **Instance-Level Objects** | ❌ No | N/A | - | Out of scope |
| **Data Rows** | ❌ No | N/A | - | By design - use ETL tools |
| **Replication Config** | ❌ No | N/A | - | Environmental/deployment config |
| **Extended Properties** | ❌ No | N/A | - | Metadata layer, separate tool needed |
| **Database Jobs** | ❌ No | N/A | - | Out of scope - use job scheduler |

---

## Implementation Roadmap (Suggested Priority)

### Phase 1: Low-Hanging Fruit (Easy Wins)
1. **Filtered Indexes** - 1-2 days, high utility
2. **Computed Columns** - 2-3 days, moderate utility
3. **Full-Text Indexes** - 2-3 days, medium utility

### Phase 2: Enhanced Scenarios (Medium Effort)
1. **Partitioned Tables** - 3-4 days, needed for large-scale scenarios
2. **Indexed Views** - 2-3 days, performance optimization
3. **Statistics Objects** - Optional, depends on team preference

### Phase 3: Advanced (Future Consideration)
1. **Additional CLR support** - Varies, lower priority

---

## Configuration Recommendations

### For Projects Using Unsupported Features

If your SQL Server database uses features from the "Cannot Be Supported" category:

1. **Database-Level Security:**
   - Implement Azure AD managed identities
   - Use separate RBAC configuration management (Terraform for Azure SQL, etc.)
   - Never store credentials in Git

2. **Data Management:**
   - Use ETL tools (Azure Data Factory, dbt) for data pipeline versioning
   - Use database seeding scripts for test data only
   - Store seed data separately from Adous-managed schema

3. **Documentation:**
   - Maintain separate documentation wiki (Confluence, GitHub Wiki)
   - Use inline SQL comments for critical logic
   - Generate documentation via automated tools

4. **Replication/HA:**
   - Manage via infrastructure automation (Terraform, Bicep)
   - Sync schema changes before configuring replication
   - Use separate deployment orchestration

---

## See Also

- [Architecture Documentation](architecture.md)
- [Usage Guide](usage.md)
- [Supported Object Types in Code](../src/main/java/app/majid/adous/synchronizer/model/DbObjectType.java)


