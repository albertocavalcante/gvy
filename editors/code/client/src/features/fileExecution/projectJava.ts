// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export async function resolveProjectJavaHome(
  configured: string | undefined,
  inherited: string | undefined,
  usable: (home: string) => Promise<boolean>,
  probe: () => Promise<string>,
): Promise<string> {
  if (configured) {
    if (!(await usable(configured)))
      throw new Error(
        "The configured project Java home does not contain a usable Java executable.",
      );
    return configured;
  }
  if (inherited && (await usable(inherited))) return inherited;
  const settings = await probe();
  const home = settings.match(/^\s*java\.home\s*=\s*(.+?)\s*$/m)?.[1];
  if (!home || !(await usable(home)))
    throw new Error(
      "Could not determine a usable java.home from Java on PATH. Configure groovy.project.javaHome.",
    );
  return home;
}
