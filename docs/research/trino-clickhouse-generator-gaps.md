# Trino->ClickHouse generator-mapping gaps — 2026-07-13

## Rename found + probed (14) — brikk should emit the ClickHouse name
- `bitwise_left_shift` -> `bitShiftLeft`
- `bitwise_right_shift` -> `bitShiftRight`
- `cbrt` -> `cbrt`
- `cosh` -> `cosh`
- `day_of_month` -> `toDayOfMonth`
- `day_of_week` -> `toDayOfWeek`
- `day_of_year` -> `toDayOfYear`
- `dot_product` -> `dotProduct`
- `is_finite` -> `isFinite`
- `is_infinite` -> `isInfinite`
- `sequence` -> `range`
- `sinh` -> `sinh`
- `translate` -> `translate`
- `xxhash64` -> `xxHash64`

## Unresolved CH-errors (89) — duckdb-specific/no rename found/needs manual mapping
`at_timezone`, `bar`, `beta_cdf`, `bit_count`, `bitwise_and`, `bitwise_not`, `bitwise_or`, `bitwise_right_shift_arithmetic`, `codepoint`, `color`, `cosine_similarity`, `create_hll`, `custom_add`, `date_add`, `date_diff`, `date_parse`, `date_trunc`, `dow`, `doy`, `format_datetime`, `format_number`, `from_base`, `from_base32`, `from_base64`, `from_base64url`, `from_big_endian_32`, `from_big_endian_64`, `from_ieee754_32`, `from_ieee754_64`, `from_unixtime_nanos`, `from_utf8`, `hamming_distance`, `hmac_md5`, `hmac_sha1`, `hmac_sha256`, `hmac_sha512`, `human_readable_seconds`, `inverse_beta_cdf`, `inverse_normal_cdf`, `is_json_scalar`, `json_array_contains`, `json_array_get`, `json_array_length`, `json_format`, `json_parse`, `luhn_check`, `murmur3`, `normal_cdf`, `normalize`, `parse_data_size`, `parse_duration`, `parse_presto_data_size`, `render`, `rgb`, `split_part`, `split_to_map`, `split_to_multimap`, `spooky_hash_v2_32`, `spooky_hash_v2_64`, `t_cdf`, `t_pdf`, `timezone`, `timezone_hour`, `timezone_minute`, `to_base`, `to_base32`, `to_base64url`, `to_big_endian_32`, `to_big_endian_64`, `to_ieee754_32`, `to_ieee754_64`, `to_iso8601`, `to_utf8`, `url_decode`, `url_encode`, `url_extract_fragment`, `url_extract_host`, `url_extract_parameter`, `url_extract_path`, `url_extract_port`, `url_extract_protocol`, `url_extract_query`, `width_bucket`, `wilson_interval_lower`, `wilson_interval_upper`, `with_timezone`, `word_stem`, `year_of_week`, `yow`
