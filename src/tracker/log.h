#ifndef LOG_H
#define LOG_H

void log_set_verbosity(int level);   /* 0 = errors, 1 = info, 2 = debug */

void log_request(const char *fmt, ...);
void log_response(const char *fmt, ...);

void log_info(const char *fmt, ...);
void log_debug(const char *fmt, ...);
void log_error(const char *fmt, ...);

#endif
