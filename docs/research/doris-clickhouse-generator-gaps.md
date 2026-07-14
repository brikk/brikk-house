# Doris->ClickHouse generator-mapping gaps — 2026-07-13

## Rename found + probed (51) — brikk should emit the ClickHouse name
- `acosh` -> `acosh`
- `array_avg` -> `arrayAvg`
- `array_compact` -> `arrayCompact`
- `array_count` -> `arrayCount`
- `array_cum_sum` -> `arrayCumSum`
- `array_difference` -> `arrayDifference`
- `array_enumerate` -> `arrayEnumerate`
- `array_enumerate_uniq` -> `arrayEnumerateUniq`
- `array_except` -> `arrayExcept`
- `array_exists` -> `arrayExists`
- `array_intersect` -> `arrayIntersect`
- `array_popback` -> `arrayPopBack`
- `array_popfront` -> `arrayPopFront`
- `array_product` -> `arrayProduct`
- `array_reverse_sort` -> `arrayReverseSort`
- `array_shuffle` -> `arrayShuffle`
- `array_sort` -> `arraySort`
- `array_union` -> `arrayUnion`
- `asinh` -> `asinh`
- `bit_count` -> `bitCount`
- `bit_shift_left` -> `bitShiftLeft`
- `bit_shift_right` -> `bitShiftRight`
- `bit_test` -> `bitTest`
- `cbrt` -> `cbrt`
- `cosh` -> `cosh`
- `count_substrings` -> `countSubstrings`
- `cut_to_first_significant_subdomain` -> `cutToFirstSignificantSubdomain`
- `dayname` -> `toDayOfWeek`
- `dayofyear` -> `toDayOfYear`
- `domain_without_www` -> `domainWithoutWWW`
- `extract_url_parameter` -> `extractURLParameter`
- `first_significant_subdomain` -> `firstSignificantSubdomain`
- `format` -> `format`
- `ipv4_num_to_string` -> `IPv4NumToString`
- `ipv4_string_to_num_or_default` -> `IPv4StringToNumOrDefault`
- `ipv6_string_to_num_or_default` -> `IPv6StringToNumOrDefault`
- `is_ipv4_string` -> `isIPv4String`
- `is_ipv6_string` -> `isIPv6String`
- `l1_distance` -> `L1Distance`
- `round_bankers` -> `roundBankers`
- `sinh` -> `sinh`
- `split_by_regexp` -> `splitByRegexp`
- `split_by_string` -> `splitByString`
- `to_ipv4_or_default` -> `toIPv4OrDefault`
- `to_ipv6_or_default` -> `toIPv6OrDefault`
- `to_monday` -> `toMonday`
- `top_level_domain` -> `topLevelDomain`
- `translate` -> `translate`
- `weekday` -> `toDayOfWeek`
- `xxhash_32` -> `xxHash32`
- `xxhash_64` -> `xxHash64`

## Unresolved CH-errors (263) — Doris-specific / ai_* / no rename found
`aes_decrypt`, `aes_encrypt`, `ai_classify`, `ai_extract`, `ai_filter`, `ai_fixgrammar`, `ai_generate`, `ai_mask`, `ai_sentiment`, `ai_similarity`, `ai_summarize`, `ai_translate`, `append_trailing_char_if_absent`, `array_contains_all`, `array_cross_product`, `array_filter`, `array_first`, `array_first_index`, `array_last`, `array_last_index`, `array_match_all`, `array_match_any`, `array_range`, `array_reverse_split`, `array_sortby`, `array_split`, `arrays_overlap`, `assert_true`, `atanh`, `auto_partition_name`, `bit_length`, `bitmap_from_array`, `bitmap_from_base64`, `bitmap_from_string`, `bitmap_hash`, `bitmap_hash64`, `century`, `char`, `compress`, `convert_to`, `convert_tz`, `cosine_similarity`, `cot`, `csc`, `damerau_levenshtein_distance`, `datev2`, `day_ceil`, `day_floor`, `day_hour`, `day_microsecond`, `day_minute`, `day_second`, `days_add`, `days_sub`, `dceil`, `decode_as_varchar`, `dexp`, `dfloor`, `digital_masking`, `dlog10`, `dpow`, `dround`, `dsqrt`, `element_at`, `elt`, `embed`, `encode_as_bigint`, `encode_as_int`, `encode_as_largeint`, `encode_as_smallint`, `even`, `export_set`, `field`, `find_in_set`, `fmod`, `format_number`, `format_round`, `fpow`, `from_base64`, `from_base64_binary`, `from_microsecond`, `from_millisecond`, `from_second`, `g`, `get_format`, `hamming_distance`, `hll_from_base64`, `hll_hash`, `hour_ceil`, `hour_floor`, `hour_from_unixtime`, `hour_microsecond`, `hour_minute`, `hour_second`, `hours_add`, `hours_sub`, `inner_product`, `inner_product_approximate`, `int_to_uuid`, `interval`, `ipv4_string_to_num`, `ipv4_string_to_num_or_null`, `ipv6_from_uint128_string_or_null`, `ipv6_num_to_string`, `ipv6_string_to_num`, `ipv6_string_to_num_or_null`, `is_ipv4_compat`, `is_ipv4_mapped`, `is_uuid`, `is_valid_utf8`, `isinf`, `json_quote`, `json_unquote`, `json_valid`, `jsonb_parse`, `jsonb_parse_error_to_null`, `jsonb_valid`, `l2_distance_approximate`, `ltrim_in`, `make_set`, `maketime`, `mask`, `mask_first_n`, `mask_last_n`, `md5sum`, `microsecond`, `microsecond_timestamp`, `microseconds_add`, `millisecond_timestamp`, `minute_ceil`, `minute_floor`, `minute_from_unixtime`, `minute_microsecond`, `minute_second`, `minutes_add`, `minutes_sub`, `money_format`, `month_ceil`, `month_floor`, `monthname`, `months_add`, `months_between`, `months_sub`, `multi_match`, `multi_match_any`, `multi_search_all_positions`, `murmur_hash3_128`, `murmur_hash3_32`, `murmur_hash3_64`, `murmur_hash3_64_v2`, `murmur_hash3_u128`, `murmur_hash3_u64_v2`, `negative`, `next_day`, `ngram_search`, `normal_cdf`, `not_null_or_empty`, `null_or_empty`, `parse_data_size`, `parse_url`, `password`, `period_add`, `period_diff`, `positive`, `previous_day`, `quantile_state_from_base64`, `quarter_ceil`, `quarter_floor`, `quarters_add`, `quarters_sub`, `quote`, `random_bytes`, `regexp_count`, `regexp_extract`, `regexp_extract_all`, `regexp_extract_or_null`, `regexp_replace_one`, `replace_empty`, `rtrim_in`, `search`, `sec`, `sec_to_time`, `second_ceil`, `second_floor`, `second_from_unixtime`, `second_microsecond`, `second_timestamp`, `seconds_add`, `seconds_sub`, `sha2`, `signbit`, `sleep`, `sm3`, `sm3sum`, `sm4_decrypt`, `sm4_encrypt`, `split_part`, `st_angle`, `st_area_square_km`, `st_area_square_meters`, `st_asbinary`, `st_astext`, `st_aswkt`, `st_azimuth`, `st_circle`, `st_contains`, `st_disjoint`, `st_distance`, `st_geometries`, `st_geometryfromtext`, `st_geometryfromwkb`, `st_geometrytype`, `st_geomfromtext`, `st_geomfromwkb`, `st_intersects`, `st_length`, `st_linefromtext`, `st_linestringfromtext`, `st_numgeometries`, `st_numpoints`, `st_point`, `st_polyfromtext`, `st_polygon`, `st_polygonfromtext`, `st_touches`, `st_x`, `st_y`, `strcmp`, `sub_replace`, `substring_index`, `time`, `timestamp`, `to_binary`, `to_bitmap`, `to_bitmap_with_check`, `to_datev2`, `to_ipv4`, `to_ipv4_or_null`, `to_ipv6`, `to_ipv6_or_null`, `to_iso8601`, `to_quantile_state`, `to_seconds`, `tokenize`, `trim_in`, `uncompress`, `unhex_null`, `unicode_normalize`, `uniform`, `url_decode`, `url_encode`, `uuid_to_int`, `week_ceil`, `week_floor`, `weeks_add`, `weeks_sub`, `xpath_string`, `year_ceil`, `year_floor`, `year_month`, `year_of_week`, `years_add`, `years_sub`
