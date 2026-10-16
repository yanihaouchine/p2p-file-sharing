#include "log.h"
#include <stdio.h>
#include <stdarg.h>
#include <pthread.h>

static pthread_mutex_t log_mutex = PTHREAD_MUTEX_INITIALIZER;
static int log_verbosity = 1; /* default: info */

void log_set_verbosity(int level)
{
    log_verbosity = level;
}

static void log_print(const char *prefix, const char *fmt, va_list args)
{
    pthread_mutex_lock(&log_mutex);

    printf("LOG : %s ", prefix);
    vprintf(fmt, args);
    printf("\n");

    fflush(stdout);

    pthread_mutex_unlock(&log_mutex);
}

void log_request(const char *fmt, ...)
{
    if (log_verbosity < 1) return;
    va_list args;
    va_start(args, fmt);
    log_print("<", fmt, args);
    va_end(args);
}

void log_response(const char *fmt, ...)
{
    if (log_verbosity < 1) return;
    va_list args;
    va_start(args, fmt);
    log_print(">", fmt, args);
    va_end(args);
}

void log_info(const char *fmt, ...)
{
    if (log_verbosity < 1) return;
    pthread_mutex_lock(&log_mutex);

    printf("LOG : ");

    va_list args;
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);

    printf("\n");
    fflush(stdout);

    pthread_mutex_unlock(&log_mutex);
}

void log_debug(const char *fmt, ...)
{
    if (log_verbosity < 2) return;
    pthread_mutex_lock(&log_mutex);

    printf("DEBUG : ");

    va_list args;
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);

    printf("\n");
    fflush(stdout);

    pthread_mutex_unlock(&log_mutex);
}

void log_error(const char *fmt, ...)
{
    /* errors always logged */
    pthread_mutex_lock(&log_mutex);

    fprintf(stderr, "ERROR : ");

    va_list args;
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    va_end(args);

    fprintf(stderr, "\n");
    fflush(stderr);

    pthread_mutex_unlock(&log_mutex);
}
