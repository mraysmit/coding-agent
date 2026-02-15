# APEX YAML Example Files Index — Research Report

> Generated: 2026-02-15  
> Source: `c:\Users\mraysmit\dev\idea-projects\apex-rules-engine`  
> Directories surveyed: 3  
> Total files analyzed: **589 YAML files**

---

## 1. Summary Statistics

### Files Per Directory

| Directory | File Count |
|---|---|
| `apex-playground/examples/` | 71 |
| `apex-demo/src/test/resources/` | 377 |
| `apex-core/src/test/resources/` | 141 |
| **Total** | **589** |

### Document Type (from `metadata.type`)

| Type | Count | Notes |
|---|---|---|
| `rule-config` | ~510 | Most common; includes files with `NONE` that have `type: "rule-config"` in metadata |
| `pipeline-config` | 6 | ETL pipeline definitions |
| `external-data-config` | 4 | External data source configs |
| `transformation-config` | 2 | Transformation-only configs |
| `enrichment` | ~15 | Files typed as enrichment in metadata |
| `scenario` | ~15 | Scenario definition files |
| `scenario-registry` | ~15 | Scenario registry/routing files |
| `inheritance-demo` | 1 | Category inheritance demo |
| `documentation` | 1 | Documentation file |

> Note: Many files have `type: "rule-config"` in their metadata block but were detected as NONE by the initial regex (570 NONE). On content inspection, ~95% of the NONE files do contain a `metadata.type` value of `rule-config`, `enrichment`, `scenario`, or `scenario-registry`.

### Feature Usage (files containing each top-level section)

| Feature | File Count | % of Total |
|---|---|---|
| `enrichments` | 264 | 44.8% |
| `rules` | 218 | 37.0% |
| `data-sources` | 129 | 21.9% |
| `pipeline` | 63 | 10.7% |
| `rule-groups` | 58 | 9.8% |
| `enrichment-groups` | 46 | 7.8% |
| `scenario` (single) | 44 | 7.5% |
| `data-sinks` | 33 | 5.6% |
| `error-recovery` | 30 | 5.1% |
| `scenarios` (registry list) | 28 | 4.8% |
| `transformations` | 16 | 2.7% |
| `categories` | 11 | 1.9% |
| `rule-chains` | 11 | 1.9% |
| `components` | 5 | 0.8% |
| `templates` | 3 | 0.5% |

### Additional Feature Counts

| Feature | File Count |
|---|---|
| `failure-policy` usage | 63 |
| `routing` section | 15 |
| `processing-stages` | 31 |
| `config-file` references | 58 |
| `source` references (to .yaml) | 43 |
| `enrichment-refs` / `rule-refs` sections | 42 |

### Enrichment Sub-Types

| Enrichment Type | File Count |
|---|---|
| `lookup-enrichment` | 124 |
| `calculation-enrichment` | 112 |
| `field-enrichment` | 80 |

### Data Source Types Referenced

| Data Source Type | File Count |
|---|---|
| H2 in-memory/file database | 59 |
| Inline datasets | 52 |
| CSV files | 47 |
| PostgreSQL | 38 |
| JSON files | 28 |
| REST API | 22 |
| XML files | 3 |

### SpEL Pattern Usage

| Pattern | File Count | Description |
|---|---|---|
| `T()` (any) | 78 | Static type references |
| `T(java.*)` | 43 | Java stdlib calls (Math, BigDecimal, LocalDate, etc.) |
| `new-obj` (`new SomeClass`) | 31 | Object instantiation |
| `.matches()` | 11 | Regex matching |
| `#ruleResults` | 10 | Cross-rule result references |
| `#root` | 6 | Root context reference |
| `?.` (safe navigation) | 6 | Null-safe property access |
| `?:` (elvis) | 5 | Elvis/default operator |
| `#lookup` | 4 | Lookup result references |
| `#enriched` | 2 | Enrichment result references |

### Line Count Distribution

| Range | Count | % |
|---|---|---|
| Small (≤50 lines) | 267 | 45.3% |
| Medium (51–150 lines) | 275 | 46.7% |
| Large (>150 lines) | 47 | 8.0% |
| **Min** | 9 | |
| **Average** | 69.7 | |
| **Max** | 423 | |

### Complexity Distribution

| Level | Count | % | Criteria |
|---|---|---|---|
| Simple | 203 | 34.5% | Basic rules, single feature section |
| Moderate | 319 | 54.2% | Enrichments + rules, 2+ feature sections |
| Complex | 67 | 11.4% | Rule-groups + scenarios + cross-refs, 3+ features |

---

## 2. Subdirectory Breakdown

### apex-playground/examples/ (71 files)

| Subdirectory | Count |
|---|---|
| roundtrip | 14 |
| enrichment | 7 |
| basic | 5 |
| conditional | 5 |
| lookups | 4 |
| validation | 3 |
| templates | 3 |
| rules | 3 |
| transformations | 3 |
| scenario | 3 |
| enrichments | 3 |
| configuration | 3 |
| components | 3 |
| error-recovery | 3 |
| lookup | 3 |
| etl | 2 |
| data-sources | 2 |
| rulegroups | 1 |
| transformation | 1 |

### apex-demo/src/test/resources/ (377 files)

| Subdirectory | Count |
|---|---|
| sequencing | 71 |
| lookup | 46 |
| scenario | 41 |
| errorhandling | 41 |
| conditional | 34 |
| etl | 32 |
| enrichmentgroups | 14 |
| enrichment | 13 |
| basic | 13 |
| datasources | 11 |
| rulegroups | 9 |
| severity | 8 |
| metrics | 7 |
| categories | 7 |
| config | 6 |
| codes | 5 |
| database | 4 |
| logging | 4 |
| business | 4 |
| transformation | 2 |
| examples | 2 |
| root | 2 |
| test | 1 |

### apex-core/src/test/resources/ (141 files)

| Subdirectory | Count |
|---|---|
| dev (engine/service tests) | 38 |
| scenario | 33 |
| config | 19 |
| dbschema | 14 |
| component-classpath-test | 8 |
| lookups | 7 |
| pipeline-step-data | 6 |
| search-path-test | 6 |
| builder-test | 3 |
| error-handling | 3 |
| tracing | 2 |
| rulegroups | 2 |

---

## 3. Representative Sample (40 files with full details)

### SIMPLE EXAMPLES

#### 1. `apex-playground/examples/basic/minimal-rule.yaml`
- **Type:** `rule-config` | **Lines:** 37 | **Complexity:** Simple
- **Features:** `rules`
- **SpEL:** basic (`#age >= 18`)
- **Cross-refs:** none
- **Description:** Absolute minimal single-rule example. 1 rule, no enrichments, no groups.

#### 2. `apex-demo/src/test/resources/dev/mars/apex/demo/basic/MinimalRuleTest.yaml`
- **Type:** `rule-config` | **Lines:** 16 | **Complexity:** Simple
- **Features:** `rules`
- **SpEL:** basic (`#age >= 18`)
- **Cross-refs:** none
- **Description:** Cleanest possible APEX rule — 16 lines total. Metadata + 1 rule.

#### 3. `apex-playground/examples/basic/simple-age-validation.yaml`
- **Type:** `rule-config` | **Lines:** 50 | **Complexity:** Simple
- **Features:** `rules`
- **SpEL:** basic conditions, mustache templates (`{{#age}}`)
- **Cross-refs:** none
- **Description:** Two rules with severity, priority, no-match-message. Good intro example.

#### 4. `apex-playground/examples/validation/basic-rules-test.yaml`
- **Type:** `rule-config` | **Lines:** 24 | **Complexity:** Simple
- **Features:** `rules`
- **SpEL:** basic
- **Cross-refs:** none

#### 5. `apex-core/src/test/resources/tracing/trace-test.yaml`
- **Type:** `rule-config` | **Lines:** 18 | **Complexity:** Simple
- **Features:** `rules`, `item-order`
- **SpEL:** `"true"` (always-match)
- **Cross-refs:** none
- **Description:** Minimal tracing test — 1 rule + item-order section.

### ENRICHMENT-FOCUSED EXAMPLES

#### 6. `apex-playground/examples/enrichment/constant-value-enrichment.yaml`
- **Type:** `rule-config` | **Lines:** 145 | **Complexity:** Moderate
- **Features:** `enrichments`
- **SpEL:** string literals (`'ACTIVE'`, `'e1'`), expression vs transformation patterns
- **Cross-refs:** none
- **Description:** Comprehensive demo of constant value assignment patterns. Tests expression, transformation, source-field="constant" usage. Multiple enrichment entries.

#### 7. `apex-playground/examples/enrichment/comprehensive-financial-settlement.yaml`
- **Type:** `rule-config` | **Lines:** 154 | **Complexity:** Complex
- **Features:** `enrichments`, `rules`, `rule-groups`
- **SpEL:** ternary expressions for classification
- **Cross-refs:** none
- **Description:** Multi-asset settlement processing. Calculation-enrichments for classification + priority. Rule groups for validation. Financial domain.

#### 8. `apex-playground/examples/lookup/dynamic-pricing.yaml`
- **Type:** `enrichment` | **Lines:** 51 | **Complexity:** Moderate
- **Features:** `enrichments` (lookup-enrichment)
- **SpEL:** string concatenation in lookup key (`#customerTier + '_PRICING'`)
- **Cross-refs:** none
- **Description:** Inline dataset lookup with dynamic key construction. Tier-based pricing.

#### 9. `apex-playground/examples/lookup/math-calculations.yaml`
- **Type:** `enrichment` | **Lines:** 45 | **Complexity:** Moderate
- **Features:** `enrichments` (calculation-enrichment)
- **SpEL:** `T(java.lang.Math).sqrt()`, `T(java.lang.Math).pow()`, `T(java.lang.Math).round()`
- **Cross-refs:** none
- **Description:** Math function showcase using T() static type references.

#### 10. `apex-demo/src/test/resources/dev/mars/apex/demo/enrichment/CustomSchemaEnrichmentTest.yaml`
- **Type:** `enrichment` | **Lines:** 248 | **Complexity:** Complex
- **Features:** `enrichments`, `data-sources`
- **SpEL:** SQL queries with parameter binding
- **Cross-refs:** none
- **Description:** Database enrichment with dynamic schema config. PostgreSQL data-source with schema setting. Multiple enrichments using database lookup.

#### 11. `apex-demo/src/test/resources/dev/mars/apex/demo/lookup/ComprehensiveLookupTest.yaml`
- **Type:** `enrichment` | **Lines:** 175 | **Complexity:** Complex
- **Features:** `enrichments`, `data-sources`
- **SpEL:** `#lookup`
- **Cross-refs:** none
- **Description:** Consolidated test for all lookup operations. H2 database data-source + database lookup queries with parameter binding.

#### 12. `apex-demo/src/test/resources/dev/mars/apex/demo/lookup/CurrencyMarketMappingTest.yaml`
- **Type:** `enrichment` | **Lines:** 195 | **Complexity:** Complex
- **Features:** `enrichments` (lookup-enrichment)
- **SpEL:** `new-obj`
- **Cross-refs:** none
- **Description:** Maps currencies to stock exchanges. Large inline dataset. Good real-world business example.

### ENRICHMENT GROUPS

#### 13. `apex-demo/src/test/resources/dev/mars/apex/demo/enrichmentgroups/composite-groups.yaml`
- **Type:** `rule-config` | **Lines:** 93 | **Complexity:** Moderate
- **Features:** `enrichment-groups`
- **SpEL:** `new-obj`
- **Cross-refs:** none
- **Description:** Hierarchical composite enrichment groups. AND/OR operators, stop-on-first-failure, priority, enrichment-group-references for composition.

#### 14. `apex-core/src/test/resources/config/composite-rulegroup-enrichmentgroup.yaml`
- **Type:** `rule-config` | **Lines:** 83 | **Complexity:** Complex
- **Features:** `enrichments`, `rules`, `rule-groups`, `enrichment-groups`
- **SpEL:** basic
- **Cross-refs:** yes — `enrichment-refs` to `config/test.yaml`
- **Description:** Mixed rule-groups + enrichment-groups in one file. Has external enrichment-refs. Tests composite structures.

### RULE GROUPS

#### 15. `apex-playground/examples/rulegroups/inline-groups.yaml`
- **Type:** `rule-config` | **Lines:** 61 | **Complexity:** Moderate
- **Features:** `rules`, `rule-groups`
- **SpEL:** `#username.length()`, `.contains('@')`
- **Cross-refs:** none
- **Description:** 3 inline rules + 2 rule groups with AND/OR operators. Rule-group-references pattern.

#### 16. `apex-demo/src/test/resources/dev/mars/apex/demo/severity/SeverityComprehensiveTest.yaml`
- **Type:** `rule-config` | **Lines:** 124 | **Complexity:** Moderate
- **Features:** `rules`, `rule-groups`
- **SpEL:** basic string/null checks
- **Cross-refs:** none
- **Description:** Tests all severity levels (ERROR, WARNING, INFO) across rules and rule groups. Good reference for severity patterns.

### CONDITIONAL / RULE RESULTS

#### 17. `apex-demo/src/test/resources/dev/mars/apex/demo/conditional/RuleResultReferencesTest.yaml`
- **Type:** `scenario` | **Lines:** 154 | **Complexity:** Complex
- **Features:** `enrichments`, `rules`, `rule-groups`
- **SpEL:** `T()`, `#ruleResults`
- **Cross-refs:** none
- **Description:** Phase 1 conditional mapping via rule result references. Individual + group rule results. Field-mapping with `#ruleResults['ruleId'].matched` patterns.

#### 18. `apex-playground/examples/conditional/fx-transaction-processing.yaml`
- **Type:** `rule-config` | **Lines:** 225 | **Complexity:** Complex
- **Features:** `enrichments`
- **SpEL:** `T()`, `elvis`, `safe-nav`, `new-obj`, `T(java)`
- **Cross-refs:** none
- **Description:** Advanced SpEL FX transaction processing. Lookup-enrichment with inline datasets, complex ternary/elvis expressions, safe navigation, T(java.time) calls.

### RULE CHAINS

#### 19. `apex-playground/examples/conditional/waterfall-approval.yaml`
- **Type:** `rule-config` | **Lines:** 111 | **Complexity:** Complex
- **Features:** `enrichments`, `transformations`, `rule-chains`
- **SpEL:** trigger-rule/conditional-rules patterns
- **Cross-refs:** none
- **Description:** Waterfall pattern using rule-chains. Sequential conditional chaining: trigger-rule → conditional-rules (on-trigger/on-no-trigger). 3-level approval.

#### 20. `apex-demo/src/test/resources/dev/mars/apex/demo/conditional/RouterPatternTest.yaml`
- **Type:** `rule-config` | **Lines:** 81 | **Complexity:** Complex
- **Features:** `rule-chains`
- **SpEL:** nested ternary in router-rule condition
- **Cross-refs:** none
- **Description:** Router pattern using result-based-routing. Central router-rule evaluates complex condition → routes to named paths (APPROVE/REJECT/REFER).

### SCENARIOS

#### 21. `apex-playground/examples/roundtrip/scenario-roundtrip-test.yaml`
- **Type:** `scenario` | **Lines:** 51 | **Complexity:** Moderate
- **Features:** `scenario`
- **SpEL:** `#trade['productType']` map access
- **Cross-refs:** references config-file paths in processing-stages
- **Description:** FX forward scenario definition. classification-rule, data-types, processing-stages with stage-name, execution-order, failure-policy.

#### 22. `apex-playground/examples/scenario/hybrid-classification-test.yaml`
- **Type:** `rule-config` | **Lines:** 49 | **Complexity:** Moderate
- **Features:** `enrichments`, `rules`, `scenario`
- **SpEL:** `#riskLevel in {'HIGH', 'MEDIUM', 'LOW'}` (set containment)
- **Cross-refs:** classification rules-ref to external .yaml
- **Description:** Hybrid scenario with both classification routing and inline rules. `classification.field-name` + `classifications` list.

#### 23. `apex-demo/src/test/resources/dev/mars/apex/demo/scenario/ConditionalStageExecutionTest.yaml`
- **Type:** `scenario-registry` | **Lines:** 17 | **Complexity:** Simple
- **Features:** `scenarios`
- **SpEL:** none
- **Cross-refs:** `config-file` to scenario .yaml
- **Description:** Minimal scenario registry. References scenario config-file with routing strategy.

#### 24. `apex-demo/src/test/resources/dev/mars/apex/demo/scenario/InputDataClassificationPhase1Test.yaml`
- **Type:** `scenario-registry` | **Lines:** 19 | **Complexity:** Simple
- **Features:** `scenarios`
- **SpEL:** none
- **Cross-refs:** `config-file` to scenario .yaml, `routing.default-scenario`
- **Description:** Type-based routing scenario registry with default-scenario.

#### 25. `apex-core/src/test/resources/scenario/complex-rules-scenario.yaml`
- **Type:** `scenario` | **Lines:** 64 | **Complexity:** Complex
- **Features:** `scenario`
- **SpEL:** `T(java.math.BigDecimal).valueOf(#amount).scale()` — BigDecimal precision check
- **Cross-refs:** none
- **Description:** Complex scenario with embedded rule-configurations, rule-groups, and rules. Uses `data-types: java.util.Map`. Demonstrates inline scenario rule definition.

### ETL / PIPELINE

#### 26. `apex-playground/examples/etl/customer-pipeline.yaml`
- **Type:** `pipeline-config` | **Lines:** 109 | **Complexity:** Complex
- **Features:** `enrichments`, `data-sources`, `pipeline`, `data-sinks`
- **SpEL:** `new-obj`
- **Cross-refs:** none
- **Description:** ETL pipeline: extract CSV→ transform with enrichment → load to H2 → audit to file. Steps with depends-on, execution modes.

#### 27. `apex-demo/src/test/resources/dev/mars/apex/demo/etl/PipelineEtlTest.yaml`
- **Type:** pipeline-config (implied) | **Lines:** 210 | **Complexity:** Complex
- **Features:** `data-sources`, `pipeline`, `data-sinks`
- **SpEL:** none
- **Cross-refs:** none
- **Description:** Advanced ETL pipeline. Multi-source extraction, multi-target loading, conditional steps, error handling with retry, performance monitoring.

#### 28. `apex-demo/src/test/resources/dev/mars/apex/demo/etl/SimplePipelineTest.yaml`
- **Type:** pipeline-config (implied) | **Lines:** 127 | **Complexity:** Moderate
- **Features:** `data-sources`, `pipeline`, `data-sinks`
- **SpEL:** `new-obj`
- **Cross-refs:** none
- **Description:** Minimal ETL pipeline. Single CSV extract step + H2 database sink. Good starter example.

#### 29. `apex-core/src/test/resources/dev/mars/apex/engine/config/RulesEnginePipelineIntegrationTest_Simple.yaml`
- **Type:** `pipeline-config` | **Lines:** 77 | **Complexity:** Moderate
- **Features:** `data-sources`, `pipeline`
- **SpEL:** none
- **Cross-refs:** none
- **Description:** Simplest pipeline integration test. H2 in-memory data source + single extract step.

### ERROR RECOVERY

#### 30. `apex-playground/examples/error-recovery/retry-strategies-test.yaml`
- **Type:** `rule-config` | **Lines:** 67 | **Complexity:** Moderate
- **Features:** `enrichments`, `error-recovery`
- **SpEL:** none
- **Cross-refs:** none
- **Description:** Comprehensive retry configuration. Exponential/linear/fixed backoff strategies, circuit-breaker config, per-error-type policies.

#### 31. `apex-core/src/test/resources/error-handling/yaml-error-recovery-test.yaml`
- **Type:** `rule-config` | **Lines:** 72 | **Complexity:** Moderate
- **Features:** `enrichments`, `rules`, `error-recovery`
- **SpEL:** `new-obj`
- **Cross-refs:** none
- **Description:** Error recovery section with severity-policies (ERROR=FAIL_FAST, WARNING=CONTINUE_WITH_DEFAULT). Metrics-enabled.

#### 32. `apex-demo/src/test/resources/dev/mars/apex/demo/metrics/SimpleErrorRecoveryDemo.yaml`
- **Type:** `rule-config` | **Lines:** 113 | **Complexity:** Moderate
- **Features:** `enrichments`, `rules`, `error-recovery`
- **SpEL:** `matches()` (regex validation)
- **Cross-refs:** none
- **Description:** Error recovery demo with severity-policies, default value recovery. Rules use `.matches()` for regex patterns.

### FAILURE POLICY / SCENARIO REGISTRY

#### 33. `apex-demo/src/test/resources/dev/mars/apex/demo/errorhandling/SimpleFailurePolicyTerminateTest.yaml`
- **Type:** `scenario-registry` | **Lines:** 36 | **Complexity:** Moderate
- **Features:** `scenarios`, `routing`
- **SpEL:** none
- **Cross-refs:** `config-file` references scenario .yaml
- **Description:** Terminate failure policy demo. Scenario registry with routing configuration.

### CATEGORIES

#### 34. `apex-demo/src/test/resources/dev/mars/apex/demo/categories/inheritance-patterns/metadata-inheritance-examples.yaml`
- **Type:** `inheritance-demo` | **Lines:** 257 | **Complexity:** Complex
- **Features:** `enrichments`, `rules`, `rule-groups`, `enrichment-groups`, `categories`
- **SpEL:** none
- **Cross-refs:** none
- **Description:** Most feature-rich single file. Category definitions with priority, business-domain, effective-date, expiration-date, stop-on-first-failure, parallel-execution. Rules/enrichments/groups inherit from categories.

### TEMPLATES

#### 35. `apex-playground/examples/templates/template-inheritance-test.yaml`
- **Type:** `rule-config` | **Lines:** 102 | **Complexity:** Moderate
- **Features:** `rules`, `templates`
- **SpEL:** `T()`, `T(java)`
- **Cross-refs:** none
- **Description:** Template inheritance pattern. base-templates + derived-templates with `extends`, parameter definitions (name/type/required/default), rule-definition blocks.

### TRANSFORMATIONS

#### 36. `apex-playground/examples/transformations/custom-expressions-test.yaml`
- **Type:** `rule-config` | **Lines:** 57 | **Complexity:** Moderate
- **Features:** `rules`, `transformations`
- **SpEL:** `T()`, `T(java.time.LocalDate)`, `.replaceAll()`, `.substring()`
- **Cross-refs:** none
- **Description:** Expression-based transformations. String concat, math, string normalization, regex replace, date operations.

### MULTI-FILE / CROSS-REFERENCES

#### 37. `apex-demo/src/test/resources/dev/mars/apex/demo/conditional/UpdateStageFxTransactionMultiFileTest_main.yaml`
- **Type:** `scenario` | **Lines:** 125 | **Complexity:** Complex
- **Features:** `enrichments`, `rule-groups`
- **SpEL:** none
- **Cross-refs:** yes — `rule-refs` with `source:` pointing to `_groups_A.yaml`, `_groups_B.yaml`
- **Description:** Multi-file architecture main config. References external rule files via rule-refs section. Enrichments with inline lookup datasets.

### CODES

#### 38. `apex-demo/src/test/resources/dev/mars/apex/demo/codes/TradeValidationCodesDemo.yaml`
- **Type:** `rule-config` | **Lines:** 137 | **Complexity:** Complex
- **Features:** `enrichments`, `rules`
- **SpEL:** `T()`, `T(java)`
- **Cross-refs:** none
- **Description:** Success-code, error-code, and map-to-field features. OTC options trade validation with status codes for audit trails.

### SEQUENCING

#### 39. `apex-demo/src/test/resources/dev/mars/apex/demo/sequencing/ItemLevelProcessingOtcOptionsTest.yaml`
- **Type:** `rule-config` | **Lines:** 77 | **Complexity:** Moderate
- **Features:** `enrichments`, `rules`
- **SpEL:** none
- **Cross-refs:** none
- **Description:** Sequential processing mode. Item-level processing order: enrichments execute in document order, then rules. OTC options trade data with inline lookup datasets.

### DATA SOURCES

#### 40. `apex-core/src/test/resources/lookups/data-lookup-integration-test.yaml`
- **Type:** `rule-config` | **Lines:** 114 | **Complexity:** Moderate
- **Features:** `data-sources`
- **SpEL:** none
- **Cross-refs:** none
- **Description:** Integration test for cache, file system, and database data sources. Cache config with TTL, eviction; H2 database; filesystem. Placeholder resolution (`${CACHE_TTL_SECONDS:1}`).

---

## 4. Cross-Reference Patterns Summary

| Pattern | File Count | Usage |
|---|---|---|
| `config-file:` in scenarios | 58 | Scenario → stage configuration files |
| `source:` to .yaml files | 43 | Rule/enrichment refs to external files |
| `enrichment-refs:` / `rule-refs:` sections | 42 | External file reference sections |
| `failure-policy:` | 63 | Terminate/continue/review policies on stages |
| `routing:` | 15 | Scenario registry routing configuration |

---

## 5. Key SpEL Patterns Catalog

### Type References
```
T(java.lang.Math).sqrt(#value)
T(java.lang.Math).pow(#base, #exponent)
T(java.lang.Math).round(#number)
T(java.math.BigDecimal).valueOf(#amount).scale()
T(java.time.LocalDate).now().plusDays(#days)
```

### Rule Result References
```
#ruleResults['high-value-rule'].matched
#ruleResults['premium-customer-rule'].matched
```

### Collection & Navigation
```
#trade['productType']           // Map access
#riskLevel in {'HIGH','LOW'}    // Set containment
#email.contains('@')            // String method
#username.length() >= 3         // String length
?.  (safe navigation)           // Null-safe property access
?:  (elvis operator)            // Default values
```

### Business Expressions
```
#assetClass != null && #assetClass.equals('EQUITY') ? 'EQUITY_SETTLEMENT' : ...
#creditScore <= 700 ? 'REJECT' : (#income <= 50000 ? 'REFER_INCOME' : 'APPROVE')
#customerTier + '_PRICING'      // Dynamic lookup key construction
#amount * (1 + #taxRate)        // Arithmetic
```

### Regex
```
#phone.replaceAll('[^0-9]', '')
#value.matches('^[A-Z]{3}$')
```

---

## 6. Data for JSON Index

Below is the complete per-file data in pipe-delimited format suitable for conversion to JSON. The full 589-row dataset is in `__yaml_analysis.csv`.

**Column definitions:**
```
relPath | directory | metadataType | features (comma-sep) | spelPatterns (comma-sep) | crossRef (true/false) | lineCount
```

**File:** `apex-rules-engine/__yaml_analysis.csv` (589 data rows + header)

### Suggested JSON Schema for Index

```json
{
  "generated": "2026-02-15",
  "totalFiles": 589,
  "directories": {
    "apex-playground/examples": 71,
    "apex-demo/src/test/resources": 377,
    "apex-core/src/test/resources": 141
  },
  "files": [
    {
      "path": "apex-playground/examples/basic/minimal-rule.yaml",
      "directory": "apex-playground/examples",
      "subdirectory": "basic",
      "metadataType": "rule-config",
      "features": ["rules"],
      "spelPatterns": [],
      "crossReferences": false,
      "lineCount": 37,
      "complexity": "simple",
      "enrichmentTypes": [],
      "dataSourceTypes": [],
      "description": "Minimal single-rule example"
    }
  ],
  "statistics": {
    "featureCounts": { "enrichments": 264, "rules": 218, "..." : "..." },
    "spelPatternCounts": { "T()": 78, "#ruleResults": 10, "..." : "..." },
    "complexityDistribution": { "simple": 203, "moderate": 319, "complex": 67 },
    "enrichmentTypeCounts": { "lookup-enrichment": 124, "calculation-enrichment": 112, "field-enrichment": 80 },
    "dataSourceTypeCounts": { "h2": 59, "inline": 52, "csv": 47, "postgresql": 38, "json": 28, "rest-api": 22, "xml": 3 }
  }
}
```
