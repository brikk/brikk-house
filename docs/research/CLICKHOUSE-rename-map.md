# Cross-dialect ClickHouse-target rename map — 2026-07-13

Consolidated from the duckdb/trino/doris ->clickhouse mass probes. Each row is a SOURCE
function whose ClickHouse equivalent exists under a RENAME (probed live). These drive the
ClickHouse generator fixes (#1): brikk should emit the ClickHouse name. `identical` = safe
rename; `divergent` = rename exists but semantics still differ (keep the hazard).

Total rename findings: 94 across duckdb=29, trino=14, doris=51


## snake->camelCase (33)
| source | dialect(s) | clickhouse | verdict |
|---|---|---|---|
| `acosh` | doris,duckdb | `acosh` | identical |
| `asinh` | doris,duckdb | `asinh` | identical |
| `bit_count` | doris,duckdb | `bitCount` | identical |
| `bit_shift_left` | doris | `bitShiftLeft` | identical |
| `bit_shift_right` | doris | `bitShiftRight` | identical |
| `bit_test` | doris | `bitTest` | identical |
| `cbrt` | doris,duckdb,trino | `cbrt` | identical |
| `cosh` | doris,duckdb,trino | `cosh` | identical |
| `count_substrings` | doris | `countSubstrings` | identical |
| `cut_to_first_significant_subdomain` | doris | `cutToFirstSignificantSubdomain` | divergent |
| `dot_product` | trino | `dotProduct` | identical |
| `first_significant_subdomain` | doris | `firstSignificantSubdomain` | divergent |
| `format` | doris | `format` | identical |
| `is_finite` | trino | `isFinite` | identical |
| `is_infinite` | trino | `isInfinite` | identical |
| `jaro_similarity` | duckdb | `jaroSimilarity` | identical |
| `range` | duckdb | `range` | identical |
| `round_bankers` | doris | `roundBankers` | identical |
| `sinh` | doris,duckdb,trino | `sinh` | identical |
| `split_by_regexp` | doris | `splitByRegexp` | identical |
| `split_by_string` | doris | `splitByString` | identical |
| `top_level_domain` | doris | `topLevelDomain` | divergent |
| `translate` | doris,trino | `translate` | identical |

## other/explicit (23)
| source | dialect(s) | clickhouse | verdict |
|---|---|---|---|
| `bitwise_left_shift` | trino | `bitShiftLeft` | identical |
| `bitwise_right_shift` | trino | `bitShiftRight` | identical |
| `dayofmonth` | duckdb | `DAYOFMONTH` | identical |
| `dayofyear` | duckdb | `DAYOFYEAR` | identical |
| `domain_without_www` | doris | `domainWithoutWWW` | divergent |
| `extract_url_parameter` | doris | `extractURLParameter` | divergent |
| `gamma` | duckdb | `tgamma` | identical |
| `greatest_common_divisor` | duckdb | `gcd` | identical |
| `ipv4_num_to_string` | doris | `IPv4NumToString` | identical |
| `ipv4_string_to_num_or_default` | doris | `IPv4StringToNumOrDefault` | identical |
| `ipv6_string_to_num_or_default` | doris | `IPv6StringToNumOrDefault` | identical |
| `is_ipv4_string` | doris | `isIPv4String` | identical |
| `is_ipv6_string` | doris | `isIPv6String` | identical |
| `isfinite` | duckdb | `isFinite` | identical |
| `l1_distance` | doris | `L1Distance` | identical |
| `least_common_multiple` | duckdb | `lcm` | identical |
| `list_has_all` | duckdb | `hasAll` | identical |
| `list_has_any` | duckdb | `hasAny` | identical |
| `sequence` | trino | `range` | divergent |
| `translate` | duckdb | `translateUTF8` | identical |
| `xxhash64` | trino | `xxHash64` | conditionally-equivalent |
| `xxhash_32` | doris | `xxHash32` | conditionally-equivalent |
| `xxhash_64` | doris | `xxHash64` | conditionally-equivalent |

## array_*->array* (camel) (20)
| source | dialect(s) | clickhouse | verdict |
|---|---|---|---|
| `array_avg` | doris | `arrayAvg` | identical |
| `array_compact` | doris | `arrayCompact` | identical |
| `array_count` | doris | `arrayCount` | identical |
| `array_cum_sum` | doris | `arrayCumSum` | identical |
| `array_difference` | doris | `arrayDifference` | identical |
| `array_enumerate` | doris | `arrayEnumerate` | identical |
| `array_enumerate_uniq` | doris | `arrayEnumerateUniq` | identical |
| `array_except` | doris | `arrayExcept` | identical |
| `array_exists` | doris | `arrayExists` | divergent |
| `array_intersect` | doris,duckdb | `arrayIntersect` | divergent/identical |
| `array_popback` | doris | `arrayPopBack` | identical |
| `array_popfront` | doris | `arrayPopFront` | identical |
| `array_product` | doris | `arrayProduct` | identical |
| `array_reverse_sort` | doris,duckdb | `arrayReverseSort` | identical |
| `array_shuffle` | doris | `arrayShuffle` | divergent |
| `array_sort` | doris,duckdb | `arraySort` | identical |
| `array_union` | doris | `arrayUnion` | divergent |

## temporal/extract ->to<Part> (11)
| source | dialect(s) | clickhouse | verdict |
|---|---|---|---|
| `day_of_month` | trino | `toDayOfMonth` | identical |
| `day_of_week` | trino | `toDayOfWeek` | identical |
| `day_of_year` | trino | `toDayOfYear` | identical |
| `dayname` | doris,duckdb | `toDayOfWeek` | divergent |
| `dayofyear` | doris | `toDayOfYear` | identical |
| `to_ipv4_or_default` | doris | `toIPv4OrDefault` | identical |
| `to_ipv6_or_default` | doris | `toIPv6OrDefault` | identical |
| `to_monday` | doris | `toMonday` | identical |
| `weekday` | doris,duckdb | `toDayOfWeek` | divergent |

## list_*->array* (7)
| source | dialect(s) | clickhouse | verdict |
|---|---|---|---|
| `list_dot_product` | duckdb | `arrayDotProduct` | identical |
| `list_element` | duckdb | `arrayElement` | divergent |
| `list_extract` | duckdb | `arrayElement` | divergent |
| `list_intersect` | duckdb | `arrayIntersect` | identical |
| `list_reverse_sort` | duckdb | `arrayReverseSort` | identical |
| `list_sort` | duckdb | `arraySort` | identical |
| `list_unique` | duckdb | `arrayUniq` | identical |
