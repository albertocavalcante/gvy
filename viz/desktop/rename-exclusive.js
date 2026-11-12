// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// macOS supplies osascript's Objective-C bridge; no compiler is needed at install time.
ObjC.import('Foundation');
ObjC.bindFunction('renamex_np', ['int', ['char *', 'char *', 'unsigned int']]);
ObjC.bindFunction('perror', ['void', ['char *']]);

function run(args) {
    if (args.length === 1 && args[0] === '--check') {
        return;
    }
    if (args.length !== 2) {
        throw Error('expected source and destination paths');
    }
    // RENAME_EXCL from macOS sys/stdio.h: fail if the destination already exists.
    const renameExcl = 0x00000004;
    if ($.renamex_np(args[0], args[1], renameExcl) !== 0) {
        $.perror('renamex_np');
        throw Error('exclusive rename failed');
    }
}
