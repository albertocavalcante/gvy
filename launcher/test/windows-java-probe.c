#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>

// Preserve literal argv: MinGW globbing otherwise treats the '?' in \\?\ paths
// as a wildcard.
int _dowildcard = 0;

static void write_bytes(HANDLE handle, const void *bytes, DWORD size) {
  const unsigned char *cursor = bytes;
  while (size > 0) {
    DWORD written = 0;
    if (!WriteFile(handle, cursor, size, &written, NULL) || written == 0) {
      ExitProcess(90);
    }
    cursor += written;
    size -= written;
  }
}

static void write_utf8(HANDLE handle, const wchar_t *value) {
  int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1, NULL,
                                 0, NULL, NULL);
  if (size == 0)
    ExitProcess(91);
  char *bytes = malloc((size_t)size);
  if (bytes == NULL)
    ExitProcess(92);
  if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1, bytes, size,
                          NULL, NULL) == 0)
    ExitProcess(91);
  write_bytes(handle, bytes, (DWORD)size - 1);
  free(bytes);
}

static wchar_t *environment(const wchar_t *name) {
  DWORD size = GetEnvironmentVariableW(name, NULL, 0);
  if (size == 0)
    return NULL;
  wchar_t *value = calloc(size, sizeof(wchar_t));
  if (value == NULL)
    ExitProcess(92);
  if (GetEnvironmentVariableW(name, value, size) == 0)
    ExitProcess(93);
  return value;
}

static DWORD start_descendant(void) {
  wchar_t executable[32768];
  wchar_t command[32768];
  if (GetModuleFileNameW(NULL, executable, 32768) == 0)
    ExitProcess(94);
  if (swprintf(command, 32768, L"\"%ls\" --descendant", executable) < 0)
    ExitProcess(94);
  STARTUPINFOW startup = {0};
  PROCESS_INFORMATION process = {0};
  startup.cb = sizeof(startup);
  if (!CreateProcessW(executable, command, NULL, NULL, FALSE, 0, NULL, NULL,
                      &startup, &process))
    ExitProcess(95);
  DWORD pid = process.dwProcessId;
  CloseHandle(process.hThread);
  CloseHandle(process.hProcess);
  return pid;
}

static HANDLE ctrl_event;

static BOOL WINAPI receive_ctrl(DWORD event) {
  if (event != CTRL_C_EVENT)
    return FALSE;
  write_bytes(GetStdHandle(STD_OUTPUT_HANDLE), "ctrlc-received\n", 15);
  SetEvent(ctrl_event);
  return TRUE;
}

static BOOL WINAPI keep_console_host(DWORD event) {
  return event == CTRL_C_EVENT || event == CTRL_BREAK_EVENT;
}

static DWORD receive_console_ctrl(void) {
  ctrl_event = CreateEventW(NULL, TRUE, FALSE, NULL);
  wchar_t *clear_ignore = environment(L"GVY_CONSOLE_CLEAR_IGNORE");
  if (clear_ignore != NULL) {
    free(clear_ignore);
    if (!SetConsoleCtrlHandler(NULL, FALSE))
      return 97;
  }
  if (ctrl_event == NULL || !SetConsoleCtrlHandler(receive_ctrl, TRUE))
    return 97;
  wchar_t *name = environment(L"GVY_CONSOLE_READY");
  if (name == NULL)
    return 98;
  HANDLE ready = OpenEventW(EVENT_MODIFY_STATE, FALSE, name);
  free(name);
  if (ready == NULL)
    return 99;
  write_bytes(GetStdHandle(STD_OUTPUT_HANDLE), "ctrlc-ready\n", 12);
  SetEvent(ready);
  CloseHandle(ready);
  DWORD result = WaitForSingleObject(ctrl_event, 10000);
  CloseHandle(ctrl_event);
  return result == WAIT_OBJECT_0 ? 73 : 100;
}

static HANDLE duplicate_stream(DWORD identifier) {
  HANDLE duplicate = NULL;
  if (!DuplicateHandle(GetCurrentProcess(), GetStdHandle(identifier),
                       GetCurrentProcess(), &duplicate, 0, TRUE,
                       DUPLICATE_SAME_ACCESS))
    ExitProcess(101);
  return duplicate;
}

static DWORD host_private_console(void) {
  HANDLE input = duplicate_stream(STD_INPUT_HANDLE);
  HANDLE output = duplicate_stream(STD_OUTPUT_HANDLE);
  HANDLE error = duplicate_stream(STD_ERROR_HANDLE);
  FreeConsole();
  if (!AllocConsole())
    return 102;
  ShowWindow(GetConsoleWindow(), SW_HIDE);
  SetStdHandle(STD_INPUT_HANDLE, input);
  SetStdHandle(STD_OUTPUT_HANDLE, output);
  SetStdHandle(STD_ERROR_HANDLE, error);
  if (!SetConsoleCtrlHandler(NULL, TRUE) ||
      !SetConsoleCtrlHandler(keep_console_host, TRUE))
    return 103;

  wchar_t event_name[128];
  swprintf(event_name, 128, L"Local\\gvy-console-ready-%lu",
           (unsigned long)GetCurrentProcessId());
  HANDLE ready = CreateEventW(NULL, TRUE, FALSE, event_name);
  if (ready == NULL)
    return 104;
  wchar_t *executable = environment(L"GVY_CONSOLE_LAUNCHER");
  if (executable == NULL)
    return 105;
  SetEnvironmentVariableW(L"GVY_PROBE_MODE", L"ctrlc");
  SetEnvironmentVariableW(L"GVY_CONSOLE_READY", event_name);
  wchar_t command[32768];
  if (swprintf(command, 32768, L"\"%ls\"", executable) < 0)
    return 106;
  STARTUPINFOW startup = {0};
  startup.cb = sizeof(startup);
  startup.dwFlags = STARTF_USESTDHANDLES;
  startup.hStdInput = input;
  startup.hStdOutput = output;
  startup.hStdError = error;
  PROCESS_INFORMATION process = {0};
  BOOL created = CreateProcessW(executable, command, NULL, NULL, TRUE, 0, NULL,
                                NULL, &startup, &process);
  free(executable);
  if (!created)
    return 107;
  CloseHandle(process.hThread);
  DWORD code = 108;
  BOOL completed = FALSE;
  if (WaitForSingleObject(ready, 5000) == WAIT_OBJECT_0) {
    code = 109;
    if (GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0)) {
      code = 110;
      if (WaitForSingleObject(process.hProcess, 5000) == WAIT_OBJECT_0) {
        completed = GetExitCodeProcess(process.hProcess, &code);
      }
    }
  }
  if (!completed) {
    TerminateProcess(process.hProcess, code);
    WaitForSingleObject(process.hProcess, 5000);
  }
  CloseHandle(process.hProcess);
  CloseHandle(ready);
  CloseHandle(input);
  CloseHandle(output);
  CloseHandle(error);
  FreeConsole();
  return code;
}

int wmain(int argc, wchar_t **argv) {
  if (argc == 2 && wcscmp(argv[1], L"--descendant") == 0) {
    Sleep(30000);
    return 0;
  }
  HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
  wchar_t *mode = environment(L"GVY_PROBE_MODE");
  if (mode != NULL && wcscmp(mode, L"console-host") == 0) {
    free(mode);
    return (int)host_private_console();
  }
  if (mode != NULL && wcscmp(mode, L"ctrlc") == 0) {
    free(mode);
    return (int)receive_console_ctrl();
  }
  if (mode != NULL && wcscmp(mode, L"console") == 0) {
    write_bytes(output, GetConsoleWindow() == NULL ? "0\n" : "1\n", 2);
    free(mode);
    return 37;
  }
  if (mode != NULL) {
    DWORD descendant = wcscmp(mode, L"tree") == 0 ? start_descendant() : 0;
    char line[64];
    int length = snprintf(line, sizeof(line), "%lu %lu\n",
                          (unsigned long)GetCurrentProcessId(),
                          (unsigned long)descendant);
    if (length <= 0 || length >= (int)sizeof(line))
      ExitProcess(96);
    write_bytes(output, line, (DWORD)length);
    free(mode);
    Sleep(30000);
    return 0;
  }
  for (int i = 1; i < argc; i++) {
    write_utf8(output, argv[i]);
    write_bytes(output, "\0", 1);
  }
  unsigned char buffer[4096];
  DWORD size = 0;
  while (ReadFile(GetStdHandle(STD_INPUT_HANDLE), buffer, sizeof(buffer), &size,
                  NULL) &&
         size > 0) {
    write_bytes(output, buffer, size);
  }
  write_bytes(GetStdHandle(STD_ERROR_HANDLE), "stderr:", 7);
  wchar_t *java_home = environment(L"JAVA_HOME");
  if (java_home != NULL) {
    write_utf8(GetStdHandle(STD_ERROR_HANDLE), java_home);
    free(java_home);
  }
  wchar_t *exit_code = environment(L"GVY_PROBE_EXIT");
  if (exit_code != NULL) {
    DWORD code = (DWORD)wcstoul(exit_code, NULL, 0);
    free(exit_code);
    ExitProcess(code);
  }
  return 37;
}
