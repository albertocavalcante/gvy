// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

int marker = 42
new File(args[0]).text = marker.toString() // BREAKPOINT
while (!new File(args[1]).exists()) {
    Thread.sleep(20)
}
