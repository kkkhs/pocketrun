/* PocketRun private talloc 2.5.0 Bionic-only build adapter.
 * talloc.c and talloc.h remain byte-for-byte upstream. No libc replacements.
 * These declarations/macros are validated by the NDK cross compilation.
 */
#ifndef POCKETRUN_TALLOC_REPLACE_H
#define POCKETRUN_TALLOC_REPLACE_H
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <string.h>
#include <strings.h>
#include <errno.h>
#include <limits.h>
#include <unistd.h>
#include <sys/types.h>
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 5
#define TALLOC_BUILD_VERSION_RELEASE 0
#ifndef MIN
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#endif
#endif
