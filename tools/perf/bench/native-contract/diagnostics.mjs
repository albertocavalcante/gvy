const nativeCodes = new Set([
  'ENGINE_INVALID', 'EVENT_LIMIT', 'GRAMMAR_ABI_UNSUPPORTED', 'HIGHLIGHT_BALANCE',
  'HIGHLIGHT_CONFIG_FAILED', 'HIGHLIGHT_FAILED', 'HIGHLIGHT_INDEX', 'INCOMPLETE_HIGHLIGHTS',
  'INJECTION_CONTROL_FAILED', 'INJECTION_REJECTION_FAILED', 'INPUT_INVALID', 'INPUT_LIMIT',
  'IDENTIFIER_BOUNDARY_FAILED_PRIMARY_VARIABLE', 'IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE',
  'MALFORMED_LOCATION_FAILED', 'ORACLE_INVALID', 'PARSE_FAILED', 'PREDICATE_ORACLE_FAILED',
  'PREDICATE_REJECTION_FAILED', 'QUERY_INVALID', 'QUERY_LIMIT', 'QUERY_SEMANTICS_UNSUPPORTED',
  'RECOVERED_HIGHLIGHT_FAILED', 'SOURCE_COVERAGE', 'UNSUPPORTED_INJECTION', 'VALID_PARSE_FAILED',
]);
const predicateCases = new Set(['PRIMARY_BUILTIN', 'PRIMARY_TYPE', 'SECONDARY_CONSTANT', 'SECONDARY_VARIABLE']);
const categoryClasses = ['CONSTANT', 'CONSTANT_BUILTIN', 'VARIABLE', 'VARIABLE_PARAMETER', 'VARIABLE_MEMBER', 'TYPE', 'TYPE_BUILTIN', 'FUNCTION', 'OTHER', 'UNCOLORED'];
const observations = new Set(['MISSING', 'INEXACT_MIXED', ...categoryClasses.flatMap(name => [`EXACT_${name}`, `INEXACT_${name}`])]);

/** Decode only a complete known fixed error line; arbitrary stderr never becomes evidence. */
export function classifyNativeFailure(stderr) {
  if (typeof stderr !== 'string' || stderr.length > 128) return 'NATIVE_PROBE_FAILED';
  const predicate = /^NATIVE_CONTRACT_FAILED:PREDICATE_ORACLE_FAILED:([A-Z_]+):([A-Z_]+)\r?\n?$/.exec(stderr);
  if (predicate && predicate[0] === stderr && predicateCases.has(predicate[1]) && observations.has(predicate[2])) return `NATIVE_PROBE_PREDICATE_ORACLE_FAILED_${predicate[1]}_${predicate[2]}`;
  const match = /^NATIVE_CONTRACT_FAILED:([A-Z_]+)\r?\n?$/.exec(stderr);
  return match && match[0] === stderr && nativeCodes.has(match[1]) ? `NATIVE_PROBE_${match[1]}` : 'NATIVE_PROBE_FAILED';
}
