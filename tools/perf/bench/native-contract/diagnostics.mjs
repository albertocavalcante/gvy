const nativeCodes = new Set([
  'ENGINE_INVALID', 'EVENT_LIMIT', 'GRAMMAR_ABI_UNSUPPORTED', 'HIGHLIGHT_BALANCE',
  'HIGHLIGHT_CONFIG_FAILED', 'HIGHLIGHT_FAILED', 'HIGHLIGHT_INDEX', 'INCOMPLETE_HIGHLIGHTS',
  'INJECTION_CONTROL_FAILED', 'INJECTION_REJECTION_FAILED', 'INPUT_INVALID', 'INPUT_LIMIT',
  'MALFORMED_LOCATION_FAILED', 'ORACLE_INVALID', 'PARSE_FAILED', 'PREDICATE_ORACLE_FAILED',
  'PREDICATE_REJECTION_FAILED', 'QUERY_INVALID', 'QUERY_LIMIT', 'QUERY_SEMANTICS_UNSUPPORTED',
  'RECOVERED_HIGHLIGHT_FAILED', 'SOURCE_COVERAGE', 'UNSUPPORTED_INJECTION', 'VALID_PARSE_FAILED',
]);

/** Decode only a complete known fixed error line; arbitrary stderr never becomes evidence. */
export function classifyNativeFailure(stderr) {
  if (typeof stderr !== 'string' || stderr.length > 128) return 'NATIVE_PROBE_FAILED';
  const match = /^NATIVE_CONTRACT_FAILED:([A-Z_]+)\r?\n?$/.exec(stderr);
  return match && nativeCodes.has(match[1]) ? `NATIVE_PROBE_${match[1]}` : 'NATIVE_PROBE_FAILED';
}
