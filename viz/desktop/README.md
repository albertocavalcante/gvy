# Desktop packaging and installation

On macOS, `make -C viz/desktop install` builds the application image and installs the app into `/Applications` by
default. The destination must already exist and be writable by the current user. Set `INSTALL_DIR` to choose a different
existing writable directory, for example `make -C viz/desktop install INSTALL_DIR="$HOME/Applications"`.

Installation copies the complete app into a staging directory on the destination filesystem before replacing an existing
app. Backup, promotion, and rollback use macOS `renamex_np` with `RENAME_EXCL` through the system `osascript` bridge. If
the filesystem does not support exclusive renames, installation fails rather than overwriting another destination.

If rollback cannot restore the previous app, the error reports the backup path. An uncatchable interruption can also
leave a `.gvy-visualizer-install.lock` directory and a `.gvy-visualizer-stage.*` directory without printing the backup
path. Before clearing a stale lock, confirm that no installation is running, inspect the staged `previous` directory,
and recover any prior app without overwriting another installation. The installer does not infer process state or remove
stale locks automatically.

Run `make -C viz/desktop test-install` on macOS for isolated installation and native exclusive-rename tests. The native
primitive tests have been run on macOS 26.7.1 (25G241) on APFS; other macOS versions and filesystems require their own
validation. This test does not install or change an app in `/Applications`.
