# ClickHouse generator-mapping gaps (DuckDB source) — 2026-07-13

Auto-probe found DuckDB scalar functions whose brikk clickhouse transpilation emits an
unmapped/same-name call that ClickHouse rejects. Two classes:

## A. RENAME FOUND — fix the generator to emit the ClickHouse name (29 functions)
These are semantically equivalent (probed live); brikk just needs the rename. Fixing each
turns it into working transpilation (the hazard entries are already recorded with the target).

| duckdb | clickhouse | verdict | current |
|---|---|---|---|
| `acosh` | `acosh` | identical | brikk emits `acosh` (unmapped) |
| `array_intersect` | `arrayIntersect` | identical | brikk emits `array_intersect` (unmapped) |
| `array_reverse_sort` | `arrayReverseSort` | identical | brikk emits `array_reverse_sort` (unmapped) |
| `array_sort` | `arraySort` | identical | brikk emits `array_sort` (unmapped) |
| `asinh` | `asinh` | identical | brikk emits `asinh` (unmapped) |
| `bit_count` | `bitCount` | identical | brikk emits `bit_count` (unmapped) |
| `cbrt` | `cbrt` | identical | brikk emits `cbrt` (unmapped) |
| `cosh` | `cosh` | identical | brikk emits `cosh` (unmapped) |
| `dayname` | `toDayOfWeek` | divergent | brikk emits `dayname` (unmapped) |
| `dayofmonth` | `DAYOFMONTH` | identical | brikk emits `dayofmonth` (unmapped) |
| `dayofyear` | `DAYOFYEAR` | identical | brikk emits `dayofyear` (unmapped) |
| `gamma` | `tgamma` | identical | brikk emits `gamma` (unmapped) |
| `greatest_common_divisor` | `gcd` | identical | brikk emits `greatest_common_divisor` (unmapped) |
| `isfinite` | `isFinite` | identical | brikk emits `isfinite` (unmapped) |
| `jaro_similarity` | `jaroSimilarity` | identical | brikk emits `jaro_similarity` (unmapped) |
| `least_common_multiple` | `lcm` | identical | brikk emits `least_common_multiple` (unmapped) |
| `list_dot_product` | `arrayDotProduct` | identical | brikk emits `list_dot_product` (unmapped) |
| `list_element` | `arrayElement` | divergent | brikk emits `list_element` (unmapped) |
| `list_extract` | `arrayElement` | divergent | brikk emits `list_extract` (unmapped) |
| `list_has_all` | `hasAll` | identical | brikk emits `list_has_all` (unmapped) |
| `list_has_any` | `hasAny` | identical | brikk emits `list_has_any` (unmapped) |
| `list_intersect` | `arrayIntersect` | identical | brikk emits `list_intersect` (unmapped) |
| `list_reverse_sort` | `arrayReverseSort` | identical | brikk emits `list_reverse_sort` (unmapped) |
| `list_sort` | `arraySort` | identical | brikk emits `list_sort` (unmapped) |
| `list_unique` | `arrayUniq` | identical | brikk emits `list_unique` (unmapped) |
| `range` | `range` | identical | brikk emits `range` (unmapped) |
| `sinh` | `sinh` | identical | brikk emits `sinh` (unmapped) |
| `translate` | `translateUTF8` | identical | brikk emits `translate` (unmapped) |
| `weekday` | `toDayOfWeek` | divergent | brikk emits `weekday` (unmapped) |

## B. NO RENAME FOUND — likely genuinely DuckDB-specific OR needs manual mapping (236 functions)
Mostly DuckDB-internal (list_* vector ops, parse_*, json_serialize_*, grapheme, make_*, to_<unit>s
interval builders, session/introspection). Review case-by-case; not ingested as hazards.

`add`, `age`, `array_extract`, `array_grade_up`, `array_has_all`, `array_has_any`, `array_select`, `array_unique`, `array_where`, `base64`, `bit_length`, `century`, `contains`, `cot`, `damerau_levenshtein`, `decade`, `encode`, `epoch_ns`, `epoch_us`, `era`, `even`, `format`, `from_hex`, `generate_series`, `getvariable`, `grade_up`, `hamming`, `hour`, `icu_collate_af`, `icu_collate_am`, `icu_collate_ar`, `icu_collate_ar_sa`, `icu_collate_as`, `icu_collate_az`, `icu_collate_be`, `icu_collate_bg`, `icu_collate_bn`, `icu_collate_bo`, `icu_collate_br`, `icu_collate_bs`, `icu_collate_ca`, `icu_collate_ceb`, `icu_collate_chr`, `icu_collate_cs`, `icu_collate_cy`, `icu_collate_da`, `icu_collate_de`, `icu_collate_de_at`, `icu_collate_dsb`, `icu_collate_dz`, `icu_collate_ee`, `icu_collate_el`, `icu_collate_en`, `icu_collate_en_us`, `icu_collate_eo`, `icu_collate_es`, `icu_collate_et`, `icu_collate_fa`, `icu_collate_fa_af`, `icu_collate_ff`, `icu_collate_fi`, `icu_collate_fil`, `icu_collate_fo`, `icu_collate_fr`, `icu_collate_fr_ca`, `icu_collate_fy`, `icu_collate_ga`, `icu_collate_gl`, `icu_collate_gu`, `icu_collate_ha`, `icu_collate_haw`, `icu_collate_he`, `icu_collate_he_il`, `icu_collate_hi`, `icu_collate_hr`, `icu_collate_hsb`, `icu_collate_hu`, `icu_collate_hy`, `icu_collate_id`, `icu_collate_id_id`, `icu_collate_ig`, `icu_collate_is`, `icu_collate_it`, `icu_collate_ja`, `icu_collate_ka`, `icu_collate_kk`, `icu_collate_kl`, `icu_collate_km`, `icu_collate_kn`, `icu_collate_ko`, `icu_collate_kok`, `icu_collate_ku`, `icu_collate_ky`, `icu_collate_lb`, `icu_collate_lij`, `icu_collate_lkt`, `icu_collate_ln`, `icu_collate_lo`, `icu_collate_lt`, `icu_collate_lv`, `icu_collate_mk`, `icu_collate_ml`, `icu_collate_mn`, `icu_collate_mr`, `icu_collate_ms`, `icu_collate_mt`, `icu_collate_my`, `icu_collate_nb`, `icu_collate_nb_no`, `icu_collate_ne`, `icu_collate_nl`, `icu_collate_nn`, `icu_collate_noaccent`, `icu_collate_nso`, `icu_collate_om`, `icu_collate_or`, `icu_collate_pa`, `icu_collate_pa_in`, `icu_collate_pl`, `icu_collate_ps`, `icu_collate_pt`, `icu_collate_ro`, `icu_collate_ru`, `icu_collate_sa`, `icu_collate_se`, `icu_collate_si`, `icu_collate_sk`, `icu_collate_sl`, `icu_collate_smn`, `icu_collate_sq`, `icu_collate_sr`, `icu_collate_sr_ba`, `icu_collate_sr_me`, `icu_collate_sr_rs`, `icu_collate_st`, `icu_collate_sv`, `icu_collate_sw`, `icu_collate_ta`, `icu_collate_te`, `icu_collate_th`, `icu_collate_tk`, `icu_collate_tn`, `icu_collate_to`, `icu_collate_tr`, `icu_collate_ug`, `icu_collate_uk`, `icu_collate_ur`, `icu_collate_uz`, `icu_collate_vi`, `icu_collate_wae`, `icu_collate_wo`, `icu_collate_xh`, `icu_collate_yi`, `icu_collate_yo`, `icu_collate_yue`, `icu_collate_yue_cn`, `icu_collate_zh`, `icu_collate_zh_cn`, `icu_collate_zh_hk`, `icu_collate_zh_mo`, `icu_collate_zh_sg`, `icu_collate_zh_tw`, `icu_collate_zu`, `icu_sort_key`, `in_search_path`, `isinf`, `isodow`, `isoyear`, `jaccard`, `json_serialize_plan`, `json_serialize_sql`, `json_valid`, `julian`, `left_grapheme`, `length_grapheme`, `list_cosine_similarity`, `list_grade_up`, `list_inner_product`, `list_negative_dot_product`, `list_negative_inner_product`, `list_select`, `list_where`, `make_date`, `make_time`, `make_timestamp_ms`, `make_timestamp_ns`, `md5_number`, `microsecond`, `millennium`, `millisecond`, `minute`, `nanosecond`, `nextafter`, `nfc_normalize`, `normalized_interval`, `ord`, `parse_dirname`, `parse_dirpath`, `parse_filename`, `parse_path`, `prefix`, `regexp_escape`, `regexp_extract_all`, `regexp_full_match`, `regexp_split_to_array`, `right_grapheme`, `second`, `signbit`, `sleep_ms`, `strip_accents`, `strlen`, `strptime`, `substring_grapheme`, `subtract`, `time_bucket`, `to_base`, `to_binary`, `to_centuries`, `to_days`, `to_decades`, `to_hours`, `to_microseconds`, `to_millennia`, `to_milliseconds`, `to_minutes`, `to_months`, `to_quarters`, `to_seconds`, `to_weeks`, `to_years`, `try_strptime`, `unicode`, `url_decode`, `url_encode`, `uuid_extract_version`, `write_log`
