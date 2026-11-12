#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
desktop_dir=$(dirname "${script_dir}")
installer="${desktop_dir}/install-app.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/gvy-install-test.XXXXXX")
trap 'rm -rf "${test_root}"' 0

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_content() {
    content=$(cat "$1") || fail "cannot read $1"
    [ "${content}" = "$2" ] || fail "$3"
}

assert_empty_directory() {
    entries=$(ls -A "$1") || fail "cannot list $1"
    [ -z "${entries}" ] || fail "$2"
}

# Inspect the recipe without executing Gradle or installing anything.
install_plan=$(make -n -C "${desktop_dir}" install)
if printf '%s\n' "${install_plan}" | grep -q 'sudo rm -rf'; then
    fail 'install deletes the existing app before copying its replacement'
fi
if ! printf '%s\n' "${install_plan}" | grep -q ':viz:desktop:createDistributable'; then
    fail 'install does not build the application image'
fi
if printf '%s\n' "${install_plan}" | grep -q ':viz:desktop:packageDistributionForCurrentOS'; then
    fail 'install unnecessarily builds a native installer'
fi

real_cp=$(command -v cp)
real_osascript=$(command -v osascript)
export real_cp real_osascript
rename_helper="${desktop_dir}/rename-exclusive.js"

# Exercise the real native primitive, including an empty target that ordinary rename can replace.
mkdir -p "${test_root}/native/source" "${test_root}/native/empty-target"
printf 'original\n' >"${test_root}/native/source/value"
if "${real_osascript}" -l JavaScript "${rename_helper}" "${test_root}/native/source" "${test_root}/native/empty-target" >"${test_root}/native-empty.log" 2>&1; then
    fail 'exclusive rename replaced an empty directory'
fi
[ -f "${test_root}/native/source/value" ] && [ -d "${test_root}/native/empty-target" ] || fail 'empty target was changed'
assert_empty_directory "${test_root}/native/empty-target" 'empty target received source data'
ln -s missing "${test_root}/native/dangling"
if "${real_osascript}" -l JavaScript "${rename_helper}" "${test_root}/native/source" "${test_root}/native/dangling" >"${test_root}/native-link.log" 2>&1; then
    fail 'exclusive rename replaced a dangling symlink'
fi
[ -L "${test_root}/native/dangling" ] && [ -f "${test_root}/native/source/value" ] || fail 'dangling symlink changed'
if "${real_osascript}" -l JavaScript "${rename_helper}" "${test_root}/native/missing" "${test_root}/native/not-created" >"${test_root}/native-missing.log" 2>&1; then
    fail 'exclusive rename accepted a missing source'
fi
[ ! -e "${test_root}/native/not-created" ] || fail 'missing source created a target'
mkdir "${test_root}/native/😀 source"
printf 'unicode\n' >"${test_root}/native/😀 source/value"
"${real_osascript}" -l JavaScript "${rename_helper}" "${test_root}/native/😀 source" "${test_root}/native/競合 target"
assert_content "${test_root}/native/競合 target/value" unicode 'Unicode rename failed'

mkdir "${test_root}/native/contender-a" "${test_root}/native/contender-b"
printf 'a\n' >"${test_root}/native/contender-a/value"
printf 'b\n' >"${test_root}/native/contender-b/value"
"${real_osascript}" -l JavaScript "${rename_helper}" "${test_root}/native/contender-a" "${test_root}/native/winner" >"${test_root}/native-a.log" 2>&1 &
first_pid=$!
"${real_osascript}" -l JavaScript "${rename_helper}" "${test_root}/native/contender-b" "${test_root}/native/winner" >"${test_root}/native-b.log" 2>&1 &
second_pid=$!
first_ok=0
second_ok=0
if wait "${first_pid}"; then first_ok=1; fi
if wait "${second_pid}"; then second_ok=1; fi
[ "$((first_ok + second_ok))" -eq 1 ] || fail 'concurrent renames did not yield exactly one winner'
if [ "${first_ok}" -eq 1 ]; then
    assert_content "${test_root}/native/winner/value" a 'wrong winner content'
    [ -f "${test_root}/native/contender-b/value" ] || fail 'losing contender was lost'
else
    assert_content "${test_root}/native/winner/value" b 'wrong winner content'
    [ -f "${test_root}/native/contender-a/value" ] || fail 'losing contender was lost'
fi
mkdir "${test_root}/bin"
cat >"${test_root}/bin/cp" <<'WRAPPER'
#!/bin/sh
if [ "${fail_copy:-0}" = 1 ]; then
    mkdir -p "$3"
    echo partial > "$3/partial"
    exit 1
fi
exec "${real_cp}" "$@"
WRAPPER
cat >"${test_root}/bin/osascript" <<'WRAPPER'
#!/bin/sh
from=$4
if [ "${from}" = '--check' ]; then
  [ "${fail_bridge:-0}" = 1 ] && exit 1
  exec "${real_osascript}" "$@"
fi
to=$5
case "${to}" in
  */previous/*)
    [ "${fail_backup:-0}" = 1 ] && exit 1
    if [ "${move_then_fail:-0}" = 1 ]; then
      if ! "${real_osascript}" "$@"; then
        echo 'backup move failed before injection' >&2
        exit 2
      fi
      echo 'injected failure after successful backup move' >&2
      exit 1
    fi
    ;;
esac
case "${from}" in
  */incoming/*)
    [ "${fail_promotion:-0}" = 1 ] && exit 1
    if [ "${fail_unsupported:-0}" = 1 ]; then
      echo 'Operation not supported' >&2
      exit 1
    fi
    if [ "${create_race:-0}" = 1 ]; then
      mkdir "${to}"
    fi
    ;;
  */previous/*)
    [ "${fail_restore:-0}" = 1 ] && exit 1
    if [ "${create_restore_race:-0}" = 1 ]; then
      mkdir "${to}"
    fi
    ;;
esac
exec "${real_osascript}" "$@"
WRAPPER
chmod +x "${test_root}/bin/cp" "${test_root}/bin/osascript"
PATH="${test_root}/bin:${PATH}"
export PATH

app_name='Groovy AST Visualizer.app'
source_app="${test_root}/build/${app_name}"
mkdir -p "${source_app}/Contents"
printf 'new\n' >"${source_app}/Contents/Info.plist"
printf 'new payload\n' >"${source_app}/Contents/payload"
mkdir "${source_app}/Contents/MacOS"
printf '#!/bin/sh\nexit 0\n' >"${source_app}/Contents/MacOS/visualizer"
chmod +x "${source_app}/Contents/MacOS/visualizer"
ln -s payload "${source_app}/Contents/payload-link"

make_old_app() {
    mkdir -p "$1/${app_name}/Contents"
    printf 'old payload\n' >"$1/${app_name}/Contents/payload"
}
assert_old_app() {
    assert_content "$1/${app_name}/Contents/payload" 'old payload' 'previous app was changed'
}
assert_no_staging() {
    for candidate in "$1"/.gvy-visualizer-stage.*; do
        [ ! -e "${candidate}" ] || fail "staging directory left behind: ${candidate}"
    done
    [ ! -e "$1/.gvy-visualizer-install.lock" ] || fail 'install lock left behind'
}

mkdir "${test_root}/first install"
sh "${installer}" "${source_app}" "${test_root}/first install" >"${test_root}/first.log"
assert_content "${test_root}/first install/${app_name}/Contents/payload" 'new payload' 'first install failed'
assert_no_staging "${test_root}/first install"

mkdir "${test_root}/replace"
make_old_app "${test_root}/replace"
sh "${installer}" "${source_app}" "${test_root}/replace" >"${test_root}/replace.log"
assert_content "${test_root}/replace/${app_name}/Contents/payload" 'new payload' 'replacement failed'
[ -x "${test_root}/replace/${app_name}/Contents/MacOS/visualizer" ] || fail 'executable mode was not preserved'
[ -L "${test_root}/replace/${app_name}/Contents/payload-link" ] || fail 'internal symlink was not preserved'
assert_content "${test_root}/replace/${app_name}/Contents/payload-link" 'new payload' 'copied symlink is broken'
assert_no_staging "${test_root}/replace"

mkdir "${test_root}/copy failure"
make_old_app "${test_root}/copy failure"
if fail_copy=1 sh "${installer}" "${source_app}" "${test_root}/copy failure" >"${test_root}/copy.log" 2>&1; then
    fail 'injected copy failure returned success'
fi
assert_old_app "${test_root}/copy failure"
assert_no_staging "${test_root}/copy failure"

mkdir "${test_root}/backup failure"
make_old_app "${test_root}/backup failure"
if fail_backup=1 sh "${installer}" "${source_app}" "${test_root}/backup failure" >"${test_root}/backup.log" 2>&1; then
    fail 'injected backup move failure returned success'
fi
assert_old_app "${test_root}/backup failure"
assert_no_staging "${test_root}/backup failure"

mkdir "${test_root}/backup reports failure"
make_old_app "${test_root}/backup reports failure"
if move_then_fail=1 sh "${installer}" "${source_app}" "${test_root}/backup reports failure" >"${test_root}/backup-partial.log" 2>&1; then
    fail 'failed backup move returned success'
fi
grep -q 'injected failure after successful backup move' "${test_root}/backup-partial.log" ||
    fail 'backup did not move before the injected error'
assert_old_app "${test_root}/backup reports failure"
assert_no_staging "${test_root}/backup reports failure"

mkdir "${test_root}/promotion failure"
make_old_app "${test_root}/promotion failure"
if fail_promotion=1 sh "${installer}" "${source_app}" "${test_root}/promotion failure" >"${test_root}/promotion.log" 2>&1; then
    fail 'injected promotion failure returned success'
fi
assert_old_app "${test_root}/promotion failure"
assert_no_staging "${test_root}/promotion failure"

mkdir "${test_root}/unsupported filesystem"
make_old_app "${test_root}/unsupported filesystem"
if fail_unsupported=1 sh "${installer}" "${source_app}" "${test_root}/unsupported filesystem" >"${test_root}/unsupported.log" 2>&1; then
    fail 'unsupported exclusive rename returned success'
fi
assert_old_app "${test_root}/unsupported filesystem"
assert_no_staging "${test_root}/unsupported filesystem"

mkdir "${test_root}/restore failure"
make_old_app "${test_root}/restore failure"
if fail_promotion=1 fail_restore=1 sh "${installer}" "${source_app}" "${test_root}/restore failure" >"${test_root}/restore.log" 2>&1; then
    fail 'injected restore failure returned success'
fi
set -- "${test_root}/restore failure"/.gvy-visualizer-stage.*/previous/"${app_name}"/Contents/payload
[ "$#" -eq 1 ] && [ -f "$1" ] || fail 'failed rollback did not retain a backup'
assert_content "$1" 'old payload' 'failed rollback damaged the backup'
grep -q 'backup:' "${test_root}/restore.log" || fail 'failed rollback did not report the backup path'
[ ! -e "${test_root}/restore failure/.gvy-visualizer-install.lock" ] || fail 'install lock left behind'

mkdir "${test_root}/concurrent target"
make_old_app "${test_root}/concurrent target"
if create_race=1 sh "${installer}" "${source_app}" "${test_root}/concurrent target" >"${test_root}/race.log" 2>&1; then
    fail 'an unexpectedly created target was overwritten'
fi
[ -d "${test_root}/concurrent target/${app_name}" ] || fail 'new target changed'
assert_empty_directory "${test_root}/concurrent target/${app_name}" 'empty competing target was overwritten'
set -- "${test_root}/concurrent target"/.gvy-visualizer-stage.*/previous/"${app_name}"/Contents/payload
[ "$#" -eq 1 ] && [ -f "$1" ] || fail 'prior app not retained'
assert_content "$1" 'old payload' 'prior app not retained'

mkdir "${test_root}/restore race"
make_old_app "${test_root}/restore race"
if fail_promotion=1 create_restore_race=1 sh "${installer}" "${source_app}" "${test_root}/restore race" >"${test_root}/restore-race.log" 2>&1; then
    fail 'a competing restore target was overwritten'
fi
[ -d "${test_root}/restore race/${app_name}" ] || fail 'restore competitor missing'
assert_empty_directory "${test_root}/restore race/${app_name}" 'restore competitor was overwritten'
set -- "${test_root}/restore race"/.gvy-visualizer-stage.*/previous/"${app_name}"/Contents/payload
[ "$#" -eq 1 ] && [ -f "$1" ] || fail 'restore backup was not retained'
assert_content "$1" 'old payload' 'restore backup was not retained'

mkdir "${test_root}/bridge failure"
make_old_app "${test_root}/bridge failure"
if fail_bridge=1 sh "${installer}" "${source_app}" "${test_root}/bridge failure" >"${test_root}/bridge.log" 2>&1; then
    fail 'unavailable bridge was accepted'
fi
assert_old_app "${test_root}/bridge failure"
assert_no_staging "${test_root}/bridge failure"

mkdir "${test_root}/missing helper" "${test_root}/installer without helper"
make_old_app "${test_root}/missing helper"
cp "${installer}" "${test_root}/installer without helper/install-app.sh"
if sh "${test_root}/installer without helper/install-app.sh" "${source_app}" "${test_root}/missing helper" >"${test_root}/helper.log" 2>&1; then
    fail 'missing exclusive-rename helper was accepted'
fi
assert_old_app "${test_root}/missing helper"
assert_no_staging "${test_root}/missing helper"

mkdir "${test_root}/invalid source"
make_old_app "${test_root}/invalid source"
mkdir -p "${test_root}/bad.app"
if sh "${installer}" "${test_root}/bad.app" "${test_root}/invalid source" >"${test_root}/invalid.log" 2>&1; then
    fail 'an incomplete source bundle was accepted'
fi
assert_old_app "${test_root}/invalid source"
assert_no_staging "${test_root}/invalid source"

mkdir "${test_root}/locked"
make_old_app "${test_root}/locked"
mkdir "${test_root}/locked/.gvy-visualizer-install.lock"
if sh "${installer}" "${source_app}" "${test_root}/locked" >"${test_root}/locked.log" 2>&1; then
    fail 'an existing install lock was ignored'
fi
assert_old_app "${test_root}/locked"

echo 'PASS: native exclusive rename, contention, staged install, failure recovery, and lock handling'
